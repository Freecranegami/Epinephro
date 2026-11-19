/* Layer-injected variable-rate shading (LAS_VRS = 0 off, 1 = 2x1, 2 = 2x2 fragments for chosen pipelines).
 *
 * Adreno's cost in a game is mostly fragment work and the memory traffic that goes with it. Transparent effects (smoke, fog sheets,
 * glows, decals, light volumes) are drawn at full resolution but are soft by nature. Their pipelines are recognised when the game
 * creates them (blending on, depth test on, depth write off, one sample, at least one colour output) and get a coarser fragment
 * size through the pipeline's own static state, which costs nothing per draw: no command is hooked and nothing is set while the
 * game records. UI and post-processing draw with the depth test off, so they keep full rate.
 * Needs VK_KHR_fragment_shading_rate with pipelineFragmentShadingRate and the chosen size among the driver's supported rates;
 * otherwise this stays off and logs why. */
#include <vulkan/vulkan.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GuestHost", __VA_ARGS__)
extern void *vkshim_real_proc(const char *name);

/* local copies of the small KHR structures, so the build does not depend on how new the Vulkan headers are */
#define ST_FEATURES   1000226003
#define ST_PIPE_STATE 1000226004
#define ST_RATE       1000226005
#define DYN_FSR       1000226000
#define DYN_DEPTH_TEST 1000267006
#define DYN_DEPTH_WRITE 1000267007
typedef struct { int32_t sType; void *pNext; VkBool32 pipeline, primitive, attachment; } VrsFeatures;
typedef struct { int32_t sType; void *pNext; VkExtent2D fragmentSize; int32_t combiner[2]; } VrsPipeState;
typedef struct { int32_t sType; void *pNext; uint32_t sampleCounts; VkExtent2D fragmentSize; } VrsRate;

static VkInstance g_inst;
static int g_level = -1;      /* 0 off, 1 = 2x1, 2 = 2x2 */
static int g_ok;              /* the device was set up with the feature and the size is supported */
static volatile unsigned g_seen, g_hit;
static __thread VkGraphicsPipelineCreateInfo *t_copy;
static __thread VrsPipeState *t_state;

static int level(void) {
    if (g_level < 0) { const char *e = getenv("LAS_VRS"); g_level = e ? atoi(e) : 0; if (g_level < 0 || g_level > 2) g_level = 0; }
    return g_level;
}

void vkshim_vrs_instance(long pinst, long res) { if (res == 0 && pinst) g_inst = *(VkInstance *)pinst; }

/* before vkCreateDevice: enable the extension and feature when the GPU has them */
void vkshim_vrs_predevice(long phys, long pci) {
    static const char *names[512];
    static VrsFeatures extra;
    if (!level() || !pci || !phys) return;
    VkDeviceCreateInfo *ci = (VkDeviceCreateInfo *)pci;
    typedef VkResult (*pfn_ext)(VkPhysicalDevice, const char *, uint32_t *, VkExtensionProperties *);
    typedef void (*pfn_f2)(VkPhysicalDevice, VkPhysicalDeviceFeatures2 *);
    typedef VkResult (*pfn_rates)(VkPhysicalDevice, uint32_t *, VrsRate *);
    typedef void *(*pfn_gipa)(VkInstance, const char *);
    pfn_ext ee = (pfn_ext)vkshim_real_proc("vkEnumerateDeviceExtensionProperties");
    pfn_f2 gf2 = (pfn_f2)vkshim_real_proc("vkGetPhysicalDeviceFeatures2");
    if (!gf2) gf2 = (pfn_f2)vkshim_real_proc("vkGetPhysicalDeviceFeatures2KHR");
    pfn_gipa gipa = (pfn_gipa)vkshim_real_proc("vkGetInstanceProcAddr");
    if (!ee || !gf2 || !gipa || !g_inst) { LOGI("vrs: off, the driver lacks the query functions"); return; }
    uint32_t n = 0; ee((VkPhysicalDevice)phys, NULL, &n, NULL);
    VkExtensionProperties *ep = n ? calloc(n, sizeof *ep) : NULL;
    int hasFsr = 0, hasRp2 = 0;
    if (ep && ee((VkPhysicalDevice)phys, NULL, &n, ep) >= 0)
        for (uint32_t i = 0; i < n; i++) { if (!strcmp(ep[i].extensionName, "VK_KHR_fragment_shading_rate")) hasFsr = 1;
                                           if (!strcmp(ep[i].extensionName, "VK_KHR_create_renderpass2")) hasRp2 = 1; }
    free(ep);
    if (!hasFsr) { LOGI("vrs: off, this GPU driver does not offer VK_KHR_fragment_shading_rate"); return; }
    VrsFeatures sf = { .sType = ST_FEATURES };
    VkPhysicalDeviceFeatures2 s2 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &sf };
    gf2((VkPhysicalDevice)phys, &s2);
    LOGI("vrs: driver features: pipeline rate %d, primitive rate %d, attachment rate %d", (int)sf.pipeline, (int)sf.primitive, (int)sf.attachment);
    if (!sf.pipeline) { LOGI("vrs: off, no per-pipeline shading rate"); return; }
    pfn_rates gr = (pfn_rates)gipa(g_inst, "vkGetPhysicalDeviceFragmentShadingRatesKHR");
    uint32_t want_w = 2, want_h = level() == 1 ? 1 : 2, nr = 0; int sup = 0;
    if (gr && gr((VkPhysicalDevice)phys, &nr, NULL) >= 0 && nr) {
        VrsRate *r = calloc(nr, sizeof *r); for (uint32_t i = 0; i < nr; i++) r[i].sType = ST_RATE;
        if (gr((VkPhysicalDevice)phys, &nr, r) >= 0)
            for (uint32_t i = 0; i < nr; i++) {
                LOGI("vrs: supported rate %ux%u (sample counts 0x%x)", r[i].fragmentSize.width, r[i].fragmentSize.height, r[i].sampleCounts);
                if (r[i].fragmentSize.width == want_w && r[i].fragmentSize.height == want_h && (r[i].sampleCounts & 1)) sup = 1;
            }
        free(r);
    }
    if (!sup) { LOGI("vrs: off, the driver does not support %ux%u shading with one sample", want_w, want_h); return; }
    /* extensions */
    uint32_t cnt = ci->enabledExtensionCount, k = 0; int gotFsr = 0, gotRp2 = 0;
    for (uint32_t i = 0; i < cnt && k < 500; i++) {
        const char *nm = ci->ppEnabledExtensionNames[i]; names[k++] = nm;
        if (!strcmp(nm, "VK_KHR_fragment_shading_rate")) gotFsr = 1;
        if (!strcmp(nm, "VK_KHR_create_renderpass2")) gotRp2 = 1;
    }
    if (!gotRp2 && hasRp2 && k < 510) names[k++] = "VK_KHR_create_renderpass2";
    if (!gotFsr && k < 511) names[k++] = "VK_KHR_fragment_shading_rate";
    ci->enabledExtensionCount = k; ci->ppEnabledExtensionNames = names;
    /* feature */
    VrsFeatures *have = NULL;
    for (VkBaseOutStructure *p = (VkBaseOutStructure *)ci->pNext; p; p = p->pNext) if (p->sType == (VkStructureType)ST_FEATURES) have = (VrsFeatures *)p;
    if (have) have->pipeline = VK_TRUE;
    else { extra = (VrsFeatures){ .sType = ST_FEATURES, .pNext = (void *)ci->pNext, .pipeline = VK_TRUE }; ci->pNext = &extra; }
    g_ok = 1;
    LOGI("vrs: on, transparent effect pipelines will shade %ux%u pixels per fragment", want_w, want_h);
}

/* before vkCreateGraphicsPipelines: work on a copy of the create infos and add the coarse rate to the matching ones */
long vkshim_vrs_pre(long dev, long count, long infos) {
    (void)dev;
    t_copy = NULL; t_state = NULL;
    if (!g_ok || count <= 0 || !infos) return infos;
    const VkGraphicsPipelineCreateInfo *src = (const VkGraphicsPipelineCreateInfo *)infos;
    VkExtent2D size = { 2, level() == 1 ? 1u : 2u };
    uint32_t hits = 0;
    unsigned char *mark = calloc((size_t)count, 1);
    if (!mark) return infos;
    for (long i = 0; i < count; i++) {
        const VkGraphicsPipelineCreateInfo *g = &src[i];
        if (g->sType != VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO) continue;
        if (g->flags & 0x800) continue;                                   /* pipeline library parts do not hold the whole state */
        int has = 0;
        for (const VkBaseInStructure *p = (const VkBaseInStructure *)g->pNext; p; p = p->pNext) if (p->sType == (VkStructureType)ST_PIPE_STATE) has = 1;
        if (has) continue;
        if (!g->pRasterizationState || g->pRasterizationState->rasterizerDiscardEnable) continue;
        if (g->pMultisampleState && g->pMultisampleState->rasterizationSamples != VK_SAMPLE_COUNT_1_BIT) continue;
        const VkPipelineDepthStencilStateCreateInfo *ds = g->pDepthStencilState;
        if (!ds || !ds->depthTestEnable || ds->depthWriteEnable) continue;      /* transparent: tests depth, does not write it */
        const VkPipelineColorBlendStateCreateInfo *cb = g->pColorBlendState;
        if (!cb || !cb->attachmentCount || !cb->pAttachments) continue;
        int blend = 0;
        for (uint32_t a = 0; a < cb->attachmentCount; a++) if (cb->pAttachments[a].blendEnable) blend = 1;
        if (!blend) continue;
        int dyn = 0;
        if (g->pDynamicState)
            for (uint32_t d = 0; d < g->pDynamicState->dynamicStateCount; d++) {
                int s = (int)g->pDynamicState->pDynamicStates[d];
                if (s == DYN_FSR || s == DYN_DEPTH_TEST || s == DYN_DEPTH_WRITE) dyn = 1;
            }
        if (dyn) continue;
        mark[i] = 1; hits++;
    }
    __sync_fetch_and_add(&g_seen, (unsigned)count);
    if (!hits) { free(mark); unsigned s = g_seen; if (s <= (unsigned)count || (s / 200) != ((s - (unsigned)count) / 200)) LOGI("vrs: %u graphics pipelines seen, %u coarse so far", s, g_hit); return infos; }
    VkGraphicsPipelineCreateInfo *copy = malloc((size_t)count * sizeof *copy);
    VrsPipeState *st = malloc((size_t)hits * sizeof *st);
    if (!copy || !st) { free(copy); free(st); free(mark); return infos; }
    memcpy(copy, src, (size_t)count * sizeof *copy);
    uint32_t j = 0;
    for (long i = 0; i < count; i++) if (mark[i]) {
        st[j] = (VrsPipeState){ .sType = ST_PIPE_STATE, .pNext = (void *)copy[i].pNext, .fragmentSize = size, .combiner = { 0, 0 } };   /* KEEP, KEEP */
        copy[i].pNext = &st[j++];
    }
    free(mark);
    unsigned h = __sync_add_and_fetch(&g_hit, hits);
    unsigned s = g_seen;
    if (h <= hits || (s / 200) != ((s - (unsigned)count) / 200)) LOGI("vrs: %u graphics pipelines seen, %u coarse so far", s, h);
    t_copy = copy; t_state = st;
    return (long)copy;
}

void vkshim_vrs_post(void) { free(t_copy); free(t_state); t_copy = NULL; t_state = NULL; }
