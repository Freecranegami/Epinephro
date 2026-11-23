/* GPU cost of every upscaler, measured without a game.
 *
 * Opens the chosen Vulkan driver (the system one or the selected custom one, exactly as the driver test does), draws each
 * upscaler's real fragment shader over a screen-sized target with GPU timestamps around many repeats, and reports the
 * milliseconds one pass costs. The shaders are the same SPIR-V the shim uses, with the same push constants for a game
 * rendering at in_w x in_h shown at out_w x out_h. The picture is a synthetic scene (flat sky, sharp-edged buildings, noisy
 * ground), because some shaders do more work on edges. It measures the shader, not the whole game frame: no game, no
 * swapchain, nothing else on the GPU. */
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include <time.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include "scale_spv.h"
#include "sgsr_spv.h"
#include "sgsrq_spv.h"
#include "fsr_spv.h"
#include "mwsr_spv.h"
#include "egsr_spv.h"
#include "egsr_cas_spv.h"
#include "hdr_spv.h"

#define BLOG(...) __android_log_print(ANDROID_LOG_INFO, "GuestHost", __VA_ARGS__)

void *vkshim_bench_open(const char *tmp, const char *hook, const char *dir, const char *name, int custom);   /* probe.c */

typedef struct {
    const char *name; const uint32_t *vs; size_t vsz; const uint32_t *fs; size_t fsz;
    uint32_t pushsz; float push[20]; int src;       /* src: 0 = the game-sized image, 1 = the screen-sized one */
    int is2;                                         /* second pass of a pair */
} Pass;

static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000.0 + t.tv_nsec / 1e6; }
static int cmpd(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }

#define LDI(n) PFN_vk##n n = (PFN_vk##n)gipa(inst, "vk" #n)
#define LDD(n) PFN_vk##n n = (PFN_vk##n)gdpa(dev, "vk" #n)

static uint32_t memtype(VkPhysicalDeviceMemoryProperties *mp, uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < mp->memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp->memoryTypes[i].propertyFlags & want) == want) return i;
    for (uint32_t i = 0; i < mp->memoryTypeCount; i++) if (bits & (1u << i)) return i;
    return 0;
}

/* the test picture: flat gradient sky, a skyline of sharp-edged blocks with windows, noisy textured ground */
static void make_scene(uint8_t *p, int w, int h) {
    uint32_t s = 12345;
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        uint8_t *o = p + 4 * ((size_t)y * w + x);
        float fy = (float)y / h, r, g, b;
        s = s * 1664525u + 1013904223u;
        float n = ((s >> 16) & 255) / 255.0f;
        if (fy < 0.45f) { r = 0.35f + 0.3f * fy; g = 0.55f + 0.3f * fy; b = 0.9f; }                       /* sky: flat, smooth */
        else if (fy < 0.7f) {                                                                               /* skyline: hard edges */
            int bx = x / (w / 14 + 1), top = (int)(h * (0.5f + 0.12f * (float)((bx * 37) % 5) / 4.0f));
            if (y > top) { int win = ((x / 9) + (y / 11)) % 3 == 0; r = win ? 0.95f : 0.30f; g = win ? 0.85f : 0.28f; b = win ? 0.55f : 0.32f; }
            else { r = 0.35f + 0.3f * fy; g = 0.55f + 0.3f * fy; b = 0.9f; }
        } else { float t = 0.5f + 0.5f * sinf(x * 0.31f) * cosf(y * 0.27f); r = 0.25f + 0.3f * n * t; g = 0.35f + 0.3f * n; b = 0.15f + 0.2f * t; }   /* ground: fine texture */
        o[0] = (uint8_t)(fminf(r, 1.f) * 255); o[1] = (uint8_t)(fminf(g, 1.f) * 255); o[2] = (uint8_t)(fminf(b, 1.f) * 255); o[3] = 255;
    }
}

#define CK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { snprintf(out, on, "Vulkan error %d at %s", (int)r_, #x); goto done; } } while (0)

static void bench_run(char *out, size_t on, void *vk, int iw, int ih, int ow, int oh, int reps) {
    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)dlsym(vk, "vkGetInstanceProcAddr");
    if (!gipa) { snprintf(out, on, "the driver has no vkGetInstanceProcAddr"); return; }
    VkInstance inst = VK_NULL_HANDLE; VkDevice dev = VK_NULL_HANDLE;
    VkApplicationInfo ai = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "upscaler-bench", .apiVersion = VK_API_VERSION_1_0 };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &ai };
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    if (!create) { snprintf(out, on, "the driver has no vkCreateInstance"); return; }
    CK(create(&ici, NULL, &inst));
    LDI(EnumeratePhysicalDevices); LDI(GetPhysicalDeviceProperties); LDI(GetPhysicalDeviceQueueFamilyProperties);
    LDI(GetPhysicalDeviceMemoryProperties); LDI(CreateDevice); LDI(GetDeviceProcAddr); LDI(DestroyInstance);
    PFN_vkGetDeviceProcAddr gdpa = GetDeviceProcAddr;
    uint32_t np = 1; VkPhysicalDevice pd;
    CK(EnumeratePhysicalDevices(inst, &np, &pd));
    if (!np) { snprintf(out, on, "no Vulkan device"); goto done; }
    VkPhysicalDeviceProperties props; GetPhysicalDeviceProperties(pd, &props);
    VkPhysicalDeviceMemoryProperties mp; GetPhysicalDeviceMemoryProperties(pd, &mp);
    uint32_t nq = 0; GetPhysicalDeviceQueueFamilyProperties(pd, &nq, NULL);
    VkQueueFamilyProperties qf[16]; if (nq > 16) nq = 16; GetPhysicalDeviceQueueFamilyProperties(pd, &nq, qf);
    uint32_t fam = nq; for (uint32_t i = 0; i < nq; i++) if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { fam = i; break; }
    if (fam == nq) { snprintf(out, on, "no graphics queue"); goto done; }
    if (!qf[fam].timestampValidBits) { snprintf(out, on, "%s: the GPU gives no timestamps on its graphics queue, so the cost can't be measured", props.deviceName); goto done; }
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = fam, .queueCount = 1, .pQueuePriorities = &prio };
    VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1, .pQueueCreateInfos = &qci };
    CK(CreateDevice(pd, &dci, NULL, &dev));
    LDD(GetDeviceQueue); LDD(CreateImage); LDD(GetImageMemoryRequirements); LDD(AllocateMemory); LDD(BindImageMemory); LDD(CreateImageView);
    LDD(CreateSampler); LDD(CreateDescriptorSetLayout); LDD(CreatePipelineLayout); LDD(CreateDescriptorPool); LDD(AllocateDescriptorSets);
    LDD(UpdateDescriptorSets); LDD(CreateRenderPass); LDD(CreateFramebuffer); LDD(CreateShaderModule); LDD(CreateGraphicsPipelines);
    LDD(CreateCommandPool); LDD(AllocateCommandBuffers); LDD(BeginCommandBuffer); LDD(EndCommandBuffer); LDD(CmdBeginRenderPass);
    LDD(CmdEndRenderPass); LDD(CmdBindPipeline); LDD(CmdBindDescriptorSets); LDD(CmdPushConstants); LDD(CmdDraw); LDD(CmdPipelineBarrier);
    LDD(CreateQueryPool); LDD(CmdResetQueryPool); LDD(CmdWriteTimestamp); LDD(GetQueryPoolResults); LDD(QueueSubmit); LDD(QueueWaitIdle);
    LDD(CreateBuffer); LDD(GetBufferMemoryRequirements); LDD(BindBufferMemory); LDD(MapMemory); LDD(UnmapMemory); LDD(CmdCopyBufferToImage);
    LDD(CreateFence); LDD(WaitForFences); LDD(ResetFences); LDD(DeviceWaitIdle); LDD(ResetCommandBuffer);
    VkQueue q; GetDeviceQueue(dev, fam, 0, &q);

    /* images: the game-sized picture (a), a screen-sized one (b, also the target of the first pass of a pair), the target (c) */
    VkExtent2D ex[3] = { { (uint32_t)iw, (uint32_t)ih }, { (uint32_t)ow, (uint32_t)oh }, { (uint32_t)ow, (uint32_t)oh } };
    VkImage img[3]; VkDeviceMemory mem[3]; VkImageView view[3]; VkFramebuffer fb[3];
    for (int i = 0; i < 3; i++) {
        VkImageCreateInfo ii = { .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
            .extent = { ex[i].width, ex[i].height, 1 }, .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
            .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED };
        CK(CreateImage(dev, &ii, NULL, &img[i]));
        VkMemoryRequirements mr; GetImageMemoryRequirements(dev, img[i], &mr);
        VkMemoryAllocateInfo ma = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = memtype(&mp, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) };
        CK(AllocateMemory(dev, &ma, NULL, &mem[i]));
        CK(BindImageMemory(dev, img[i], mem[i], 0));
        VkImageViewCreateInfo vi = { .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = img[i], .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        CK(CreateImageView(dev, &vi, NULL, &view[i]));
    }
    /* upload the scene into a (game size) and b (screen size, so the second pass of a pair and the HDR pass see real content) */
    VkDeviceSize sa = (VkDeviceSize)iw * ih * 4, sb = (VkDeviceSize)ow * oh * 4;
    VkBuffer sbuf; VkDeviceMemory sbm;
    VkBufferCreateInfo bi = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = sa + sb, .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT, .sharingMode = VK_SHARING_MODE_EXCLUSIVE };
    CK(CreateBuffer(dev, &bi, NULL, &sbuf));
    VkMemoryRequirements bmr; GetBufferMemoryRequirements(dev, sbuf, &bmr);
    VkMemoryAllocateInfo bma = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = bmr.size,
        .memoryTypeIndex = memtype(&mp, bmr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) };
    CK(AllocateMemory(dev, &bma, NULL, &sbm));
    CK(BindBufferMemory(dev, sbuf, sbm, 0));
    uint8_t *mapped; CK(MapMemory(dev, sbm, 0, VK_WHOLE_SIZE, 0, (void **)&mapped));
    make_scene(mapped, iw, ih); make_scene(mapped + sa, ow, oh);
    UnmapMemory(dev, sbm);

    VkCommandPoolCreateInfo cpi = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, .queueFamilyIndex = fam };
    VkCommandPool pool; CK(CreateCommandPool(dev, &cpi, NULL, &pool));
    VkCommandBufferAllocateInfo cai = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1 };
    VkCommandBuffer cb; CK(AllocateCommandBuffers(dev, &cai, &cb));
    VkFenceCreateInfo fi = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    VkFence fence; CK(CreateFence(dev, &fi, NULL, &fence));

    /* one render pass: RGBA8, ends readable by the next pass; a dependency serialises the repeats so each pass is timed alone */
    VkAttachmentDescription ad = { .format = VK_FORMAT_R8G8B8A8_UNORM, .samples = VK_SAMPLE_COUNT_1_BIT, .loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE, .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED, .finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
    VkAttachmentReference ar = { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
    VkSubpassDescription sp = { .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS, .colorAttachmentCount = 1, .pColorAttachments = &ar };
    VkSubpassDependency dep[2] = {
        { VK_SUBPASS_EXTERNAL, 0, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0 },
        { 0, VK_SUBPASS_EXTERNAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
          VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0 } };
    VkRenderPassCreateInfo rpi = { .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, .attachmentCount = 1, .pAttachments = &ad, .subpassCount = 1, .pSubpasses = &sp,
        .dependencyCount = 2, .pDependencies = dep };
    VkRenderPass rp; CK(CreateRenderPass(dev, &rpi, NULL, &rp));
    for (int i = 1; i < 3; i++) {
        VkFramebufferCreateInfo fci = { .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = rp, .attachmentCount = 1, .pAttachments = &view[i],
            .width = ex[i].width, .height = ex[i].height, .layers = 1 };
        CK(CreateFramebuffer(dev, &fci, NULL, &fb[i]));
    }

    VkSamplerCreateInfo sci = { .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .magFilter = VK_FILTER_LINEAR, .minFilter = VK_FILTER_LINEAR,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE };
    VkSampler samp; CK(CreateSampler(dev, &sci, NULL, &samp));
    VkDescriptorSetLayoutBinding lb = { .binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT };
    VkDescriptorSetLayoutCreateInfo dli = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .bindingCount = 1, .pBindings = &lb };
    VkDescriptorSetLayout dsl; CK(CreateDescriptorSetLayout(dev, &dli, NULL, &dsl));
    VkPushConstantRange pcr = { VK_SHADER_STAGE_FRAGMENT_BIT, 0, 80 };
    VkPipelineLayoutCreateInfo pli = { .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1, .pSetLayouts = &dsl, .pushConstantRangeCount = 1, .pPushConstantRanges = &pcr };
    VkPipelineLayout pl; CK(CreatePipelineLayout(dev, &pli, NULL, &pl));
    VkDescriptorPoolSize ps = { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2 };
    VkDescriptorPoolCreateInfo dpi = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, .maxSets = 2, .poolSizeCount = 1, .pPoolSizes = &ps };
    VkDescriptorPool dp; CK(CreateDescriptorPool(dev, &dpi, NULL, &dp));
    VkDescriptorSetLayout ls[2] = { dsl, dsl }; VkDescriptorSet ds[2];
    VkDescriptorSetAllocateInfo dai = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, .descriptorPool = dp, .descriptorSetCount = 2, .pSetLayouts = ls };
    CK(AllocateDescriptorSets(dev, &dai, ds));
    for (int i = 0; i < 2; i++) {
        VkDescriptorImageInfo di = { samp, view[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };      /* set 0 reads a (game size), set 1 reads b (screen size) */
        VkWriteDescriptorSet w = { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = ds[i], .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = &di };
        UpdateDescriptorSets(dev, 1, &w, 0, NULL);
    }

    /* upload */
    CK(BeginCommandBuffer(cb, &(VkCommandBufferBeginInfo){ .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT }));
    for (int i = 0; i < 2; i++) {
        VkImageMemoryBarrier b = { .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = img[i],
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &b);
        VkBufferImageCopy rg = { .bufferOffset = i ? sa : 0, .imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, .imageExtent = { ex[i].width, ex[i].height, 1 } };
        CmdCopyBufferToImage(cb, sbuf, img[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &rg);
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT; b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, NULL, 0, NULL, 1, &b);
    }
    CK(EndCommandBuffer(cb));
    VkSubmitInfo si = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cb };
    CK(QueueSubmit(q, 1, &si, fence)); CK(WaitForFences(dev, 1, &fence, VK_TRUE, UINT64_MAX)); CK(ResetFences(dev, 1, &fence));

    /* the passes, with the constants the shim builds for this size pair */
    float sc = fminf((float)ow / iw, (float)oh / ih);
    uint32_t easu[16]; float ef[16] = { (float)iw / ow, (float)ih / oh, 0.5f * iw / ow - 0.5f, 0.5f * ih / oh - 0.5f, 1.0f / iw, 1.0f / ih, 1.0f / iw, -1.0f / ih,
        -1.0f / iw, 2.0f / ih, 1.0f / iw, 2.0f / ih, 0.0f, 4.0f / ih, 0.0f, 0.0f };
    memcpy(easu, ef, sizeof easu);
    float sh = exp2f(-0.2f); uint32_t shb; memcpy(&shb, &sh, 4);
    float v[16] = { 1.0f / iw, 1.0f / ih, (float)iw, (float)ih };
    Pass P[12]; int np_ = 0; memset(P, 0, sizeof P);
#define ADD(nm, vsarr, fsarr, psz, s) do { P[np_].name = nm; P[np_].vs = vsarr; P[np_].vsz = sizeof vsarr; P[np_].fs = fsarr; P[np_].fsz = sizeof fsarr; P[np_].pushsz = psz; P[np_].src = s; np_++; } while (0)
    ADD("Bilinear (no upscaler)", sgsr_vs_spv, scale_fs_spv, 0, 0);                   /* 0 */
    ADD("SGSR", sgsr_vs_spv, sgsr_fs_spv, 16, 0); memcpy(P[np_ - 1].push, v, 16);     /* 1 */
    ADD("SGSR Quality", sgsr_vs_spv, sgsrq_fs_spv, 16, 0); memcpy(P[np_ - 1].push, v, 16);   /* 2 */
    ADD("FSR 1 EASU", fsr_vs_spv, fsr_easu_h_fs_spv, 64, 0); memcpy(P[np_ - 1].push, easu, 64);   /* 3 */
    ADD("FSR 1 RCAS", fsr_vs_spv, fsr_rcas_fs_spv, 16, 1); memcpy(P[np_ - 1].push, &shb, 4);      /* 4 (reads a screen-sized picture) */
    float mw[16] = { 1.0f / iw, 1.0f / ih, (float)iw, (float)ih };
    { float sl = sc < 1.07f ? 1.07f : sc > 4.0f ? 4.0f : sc, x = logf(sl);
      float force = 1.0f + 0.35f * fminf(fmaxf(logf(sl / 1.3333f) / logf(2.0f / 1.3333f), 0.0f), 1.0f);
      mw[4] = sc; mw[5] = sc; mw[6] = 0.63536f + 0.02185f * x + 0.12781f * x * x; mw[7] = 0.005f;
      mw[8] = (-0.12884f + 2.58447f * x - 1.26009f * x * x) * force; mw[9] = 0.1f; mw[10] = (0.33417f - 0.29620f * x + 0.08628f * x * x) * force; mw[11] = 1.00715f - 1.02091f * x + 0.56996f * x * x; }
    ADD("MWSR", sgsr_vs_spv, mwsr_fs_spv, 48, 0); memcpy(P[np_ - 1].push, mw, 48);     /* 5 */
    float eg[16] = { 1.0f / iw, 1.0f / ih, (float)iw, (float)ih, 2.0f, 0.02f, 0.004f, 0.15f, 0.12f, 0.0f, 1.5f, 0.02f, 0.5f * 1.5f / ow, 0.5f * 1.5f / oh, 0.02f, 0.0f };
    ADD("EGSR", sgsr_vs_spv, egsr_fs_spv, 64, 0); memcpy(P[np_ - 1].push, eg, 64);     /* 6 */
    float ec[16]; memcpy(ec, eg, 64); ec[10] = 0.4f; ec[11] = 0.0f; ec[12] = 1.0f / ow; ec[13] = 1.0f / oh; ec[14] = 0.04f;
    ADD("EGSR-CAS", sgsr_vs_spv, egsr_cas_fs_spv, 64, 0); memcpy(P[np_ - 1].push, ec, 64);
    float en[16]; memcpy(en, eg, 64); en[10] = 0.0f;
    ADD("EGSR, sharpening off", sgsr_vs_spv, egsr_fs_spv, 64, 0); memcpy(P[np_ - 1].push, en, 64);
    float hd[4] = { 200.0f, 600.0f, 0.5f, 0.0f };
    ADD("HDR10 pass (extra, any upscaler)", sgsr_vs_spv, hdr_fs_spv, 16, 1); memcpy(P[np_ - 1].push, hd, 16);   /* 8 */

    VkShaderModule vsm[12], fsm[12]; VkPipeline pipe[12];
    for (int i = 0; i < np_; i++) {
        VkShaderModuleCreateInfo m = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = P[i].vsz, .pCode = P[i].vs };
        CK(CreateShaderModule(dev, &m, NULL, &vsm[i])); m.codeSize = P[i].fsz; m.pCode = P[i].fs; CK(CreateShaderModule(dev, &m, NULL, &fsm[i]));
        VkPipelineShaderStageCreateInfo st[2] = {
            { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vsm[i], .pName = "main" },
            { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = fsm[i], .pName = "main" } };
        VkPipelineVertexInputStateCreateInfo vis = { .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
        VkPipelineInputAssemblyStateCreateInfo ia = { .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST };
        VkViewport vp = { 0, 0, (float)ow, (float)oh, 0, 1 }; VkRect2D scs = { { 0, 0 }, { (uint32_t)ow, (uint32_t)oh } };
        VkPipelineViewportStateCreateInfo vps = { .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, .viewportCount = 1, .pViewports = &vp, .scissorCount = 1, .pScissors = &scs };
        VkPipelineRasterizationStateCreateInfo rs = { .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO, .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE, .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE, .lineWidth = 1.0f };
        VkPipelineMultisampleStateCreateInfo ms = { .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT };
        VkPipelineColorBlendAttachmentState cba = { .colorWriteMask = 0xF };
        VkPipelineColorBlendStateCreateInfo cbs = { .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, .attachmentCount = 1, .pAttachments = &cba };
        VkGraphicsPipelineCreateInfo gp = { .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .stageCount = 2, .pStages = st, .pVertexInputState = &vis, .pInputAssemblyState = &ia,
            .pViewportState = &vps, .pRasterizationState = &rs, .pMultisampleState = &ms, .pColorBlendState = &cbs, .layout = pl, .renderPass = rp, .basePipelineIndex = -1 };
        CK(CreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &gp, NULL, &pipe[i]));
    }

    VkQueryPoolCreateInfo qpi = { .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO, .queryType = VK_QUERY_TYPE_TIMESTAMP, .queryCount = 2 };
    VkQueryPool qp; CK(CreateQueryPool(dev, &qpi, NULL, &qp));
    double period = props.limits.timestampPeriod;       /* nanoseconds per tick */
    uint64_t mask = qf[fam].timestampValidBits >= 64 ? ~0ull : ((1ull << qf[fam].timestampValidBits) - 1);

    /* time one pass over many repeats (c, written by every repeat, is the target; pass 4 and 8 read the screen-sized picture) */
    const int N = 12;
    double res[12];
    char head[160];
    snprintf(head, sizeof head, "%s\ngame size %dx%d, screen size %dx%d (x%.2f)\n", props.deviceName, iw, ih, ow, oh, sc);
    double clk0 = now_ms();
    for (int i = 0; i < np_; i++) {
        double t[16]; int nt = 0;
        for (int r = 0; r < reps + 3; r++) {
            CK(ResetCommandBuffer(cb, 0));
            CK(BeginCommandBuffer(cb, &(VkCommandBufferBeginInfo){ .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT }));
            CmdResetQueryPool(cb, qp, 0, 2);
            CmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp, 0);
            for (int k = 0; k < N; k++) {
                VkRenderPassBeginInfo rb = { .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = rp, .framebuffer = fb[2], .renderArea = { { 0, 0 }, ex[2] } };
                CmdBeginRenderPass(cb, &rb, VK_SUBPASS_CONTENTS_INLINE);
                CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe[i]);
                CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pl, 0, 1, &ds[P[i].src], 0, NULL);
                if (P[i].pushsz) CmdPushConstants(cb, pl, VK_SHADER_STAGE_FRAGMENT_BIT, 0, P[i].pushsz, P[i].push);
                CmdDraw(cb, 3, 1, 0, 0);
                CmdEndRenderPass(cb);
            }
            CmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp, 1);
            CK(EndCommandBuffer(cb));
            VkSubmitInfo s2 = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cb };
            CK(QueueSubmit(q, 1, &s2, fence)); CK(WaitForFences(dev, 1, &fence, VK_TRUE, UINT64_MAX)); CK(ResetFences(dev, 1, &fence));
            uint64_t ts[2];
            CK(GetQueryPoolResults(dev, qp, 0, 2, sizeof ts, ts, sizeof ts[0], VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT));
            if (r >= 3 && nt < 16) t[nt++] = (double)((ts[1] - ts[0]) & mask) * period / 1e6 / N;     /* the first three repeats let the GPU clock settle */
        }
        qsort(t, nt, sizeof t[0], cmpd);
        res[i] = t[nt / 2];
        BLOG("bench: %s %.3f ms", P[i].name, res[i]);
    }
    {
        size_t len = strlen(head);
        snprintf(out, on, "%s", head);
        for (int i = 0; i < np_; i++) {
            double ms = res[i];
            if (i == 4) continue;                                   /* RCAS is shown together with EASU */
            if (i == 3) {
                double both = res[3] + res[4];
                len += snprintf(out + len, on - len, "\nFSR 1 two passes (EASU %.2f + RCAS %.2f): %.2f ms   %.1f %% of a 60 fps frame", res[3], res[4], both, both / 16.667 * 100.0);
                continue;
            }
            len += snprintf(out + len, on - len, "\n%s: %.2f ms   %.1f %% of a 60 fps frame", P[i].name, ms, ms / 16.667 * 100.0);
        }
        len += snprintf(out + len, on - len, "\n\nOne pass over a screen-sized target, shader only. In a game the GPU is also busy rendering, so treat these as the cost relative to each other and to a bilinear scale. "
                        "A synthetic scene was used; edge-heavy games cost somewhat more on shaders that do extra work on edges. (%.1f s)", (now_ms() - clk0) / 1000.0);
    }
    return;
done:
    BLOG("bench: %s", out);
}

JNIEXPORT jstring JNICALL
Java_com_freecrane_adrenotest_Native_bench(JNIEnv *env, jobject thiz, jstring jtmp, jstring jhook, jstring jdir, jstring jname, jboolean custom,
                                           jint iw, jint ih, jint ow, jint oh) {
    char out[2048];
    const char *tmp  = (*env)->GetStringUTFChars(env, jtmp, NULL);
    const char *hook = (*env)->GetStringUTFChars(env, jhook, NULL);
    const char *dir  = (*env)->GetStringUTFChars(env, jdir, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    void *vk = vkshim_bench_open(tmp, hook, dir, name, custom);
    if (!vk) snprintf(out, sizeof out, "open failed: %s", dlerror());
    else if (iw < 64 || ih < 64 || ow < iw || oh < ih || ow > 8192 || oh > 8192) snprintf(out, sizeof out, "bad sizes");
    else bench_run(out, sizeof out, vk, iw, ih, ow, oh, 5);
    (*env)->ReleaseStringUTFChars(env, jtmp, tmp);
    (*env)->ReleaseStringUTFChars(env, jhook, hook);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return (*env)->NewStringUTF(env, out);
}
