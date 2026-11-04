/* Persistent Vulkan pipeline cache, and a small render-thread priority boost.
 *
 * Games that never create a VkPipelineCache (or never save theirs) compile every pipeline again on every start, which
 * is the main source of hitching the first time an area is drawn. This file keeps one cache for the whole process:
 *  - a pipeline created with a null cache gets ours instead (created on first use, filled from the saved file);
 *  - a cache the game creates itself with no initial data is filled from the saved file too;
 *  - the cache is written to LAS_PCACHE_FILE a short while after new pipelines appear (a game process is often killed
 *    without warning), and when the game destroys its cache.
 * The file starts with our own header (magic, size, checksum) and the driver's cache header is checked against the GPU
 * before the data is handed back, so a file from another driver or GPU is ignored. Environment: LAS_PCACHE=1 enables,
 * LAS_PCACHE_FILE is the file, LAS_PRIO=1 raises the priority of the thread that presents frames.
 */
#include <vulkan/vulkan.h>
#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>
#include <sys/resource.h>
#include <sys/syscall.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GuestHost", __VA_ARGS__)
extern void *vkshim_real_proc(const char *name);

#define SAVE_AFTER_S 45          /* at most one save per this many seconds */
#define MAGIC 0x31435045u        /* "EPC1" */

static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER;
static int g_on = -1;
static char g_file[512];
static VkDevice g_dev;
static VkPipelineCache g_cache;          /* the cache we save: ours, or the first one the game created */
static int g_owned;                      /* 1 = created by us (the game does not know it) */
static unsigned char *g_blob;            /* data loaded from the file, valid for this GPU */
static size_t g_blob_len;
static uint32_t g_vendor, g_device;
static uint8_t g_uuid[VK_UUID_SIZE];
static volatile int g_dirty, g_saving;
static volatile long g_last_save;
static size_t g_last_size;

typedef VkResult (*pfn_create)(VkDevice, const VkPipelineCacheCreateInfo *, const VkAllocationCallbacks *, VkPipelineCache *);
typedef VkResult (*pfn_get)(VkDevice, VkPipelineCache, size_t *, void *);
static pfn_create p_create; static pfn_get p_get;

static long now_s(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec; }

static int enabled(void) {
    if (g_on < 0) {
        const char *e = getenv("LAS_PCACHE"), *f = getenv("LAS_PCACHE_FILE");
        g_on = (e && *e == '1' && f && *f) ? 1 : 0;
        if (g_on) { strncpy(g_file, f, sizeof g_file - 1); g_file[sizeof g_file - 1] = 0; }
    }
    return g_on;
}

static uint32_t checksum(const unsigned char *p, size_t n) {   /* FNV-1a */
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < n; i++) { h ^= p[i]; h *= 16777619u; }
    return h;
}

/* the driver's own header: u32 size, u32 version (1), u32 vendor, u32 device, uuid[16] */
static int header_ok(const unsigned char *d, size_t n) {
    if (n < 32) return 0;
    uint32_t hs, hv, v, dv;
    memcpy(&hs, d, 4); memcpy(&hv, d + 4, 4); memcpy(&v, d + 8, 4); memcpy(&dv, d + 12, 4);
    return hs >= 32 && hs <= n && hv == 1 && v == g_vendor && dv == g_device && !memcmp(d + 16, g_uuid, VK_UUID_SIZE);
}

static void load_file(void) {
    FILE *f = fopen(g_file, "rb");
    if (!f) { LOGI("pipeline cache: no saved cache yet (%s)", g_file); return; }
    uint32_t hdr[3];
    if (fread(hdr, 4, 3, f) != 3 || hdr[0] != MAGIC || hdr[1] < 32 || hdr[1] > (256u << 20)) { fclose(f); LOGI("pipeline cache: saved file unreadable, ignored"); return; }
    unsigned char *d = malloc(hdr[1]);
    if (!d || fread(d, 1, hdr[1], f) != hdr[1]) { free(d); fclose(f); LOGI("pipeline cache: saved file cut short, ignored"); return; }
    fclose(f);
    if (checksum(d, hdr[1]) != hdr[2]) { free(d); LOGI("pipeline cache: saved file damaged, ignored"); return; }
    if (!header_ok(d, hdr[1])) { free(d); LOGI("pipeline cache: saved file is for another driver or GPU, ignored"); return; }
    g_blob = d; g_blob_len = hdr[1]; g_last_size = hdr[1];
    LOGI("pipeline cache: loaded %u KB from the saved file", (unsigned)(hdr[1] / 1024));
}

static void write_cache(VkDevice dev, VkPipelineCache c) {
    if (!p_get || !c) return;
    size_t n = 0;
    if (p_get(dev, c, &n, NULL) != VK_SUCCESS || n < 64 || n > (256u << 20)) return;
    if (n == g_last_size) return;                       /* nothing new */
    unsigned char *d = malloc(n);
    if (!d) return;
    if (p_get(dev, c, &n, d) != VK_SUCCESS || !header_ok(d, n)) { free(d); return; }
    char tmp[560]; snprintf(tmp, sizeof tmp, "%s.tmp", g_file);
    FILE *f = fopen(tmp, "wb");
    if (f) {
        uint32_t hdr[3] = { MAGIC, (uint32_t)n, checksum(d, n) };
        int ok = fwrite(hdr, 4, 3, f) == 3 && fwrite(d, 1, n, f) == n;
        ok = (fclose(f) == 0) && ok;
        if (ok && rename(tmp, g_file) == 0) { g_last_size = n; LOGI("pipeline cache: saved %u KB", (unsigned)(n / 1024)); }
        else unlink(tmp);
    }
    free(d);
}

static void *save_thread(void *a) {
    (void)a;
    pthread_mutex_lock(&g_mx);
    VkDevice d = g_dev; VkPipelineCache c = g_cache;
    pthread_mutex_unlock(&g_mx);
    write_cache(d, c);
    g_last_save = now_s();
    g_saving = 0;
    return NULL;
}

static void maybe_save(void) {
    if (!g_dirty || g_saving) return;
    long t = now_s();
    if (g_last_save && t - g_last_save < SAVE_AFTER_S) return;
    if (!g_last_save) { g_last_save = t; return; }       /* first time: start the clock, save a little later */
    if (__sync_lock_test_and_set(&g_saving, 1)) return;
    g_dirty = 0;
    pthread_t th;
    if (pthread_create(&th, NULL, save_thread, NULL) == 0) pthread_detach(th); else g_saving = 0;
}

/* ---- called by the generated wrappers ---- */

void vkshim_pc_device(long phys, long dev) {
    if (!enabled() || !phys || !dev || g_dev) return;
    typedef void (*pfn_props)(VkPhysicalDevice, VkPhysicalDeviceProperties *);
    pfn_props gp = (pfn_props)vkshim_real_proc("vkGetPhysicalDeviceProperties");
    p_create = (pfn_create)vkshim_real_proc("vkCreatePipelineCache");
    p_get = (pfn_get)vkshim_real_proc("vkGetPipelineCacheData");
    if (!gp || !p_create || !p_get) { LOGI("pipeline cache: off, the driver lacks the functions"); g_on = 0; return; }
    VkPhysicalDeviceProperties pr;
    memset(&pr, 0, sizeof pr);
    gp((VkPhysicalDevice)phys, &pr);
    pthread_mutex_lock(&g_mx);
    g_vendor = pr.vendorID; g_device = pr.deviceID; memcpy(g_uuid, pr.pipelineCacheUUID, VK_UUID_SIZE);
    g_dev = (VkDevice)dev;
    load_file();
    pthread_mutex_unlock(&g_mx);
}

/* pipeline creation: which cache to use (ours when the game passes none) */
long vkshim_pc_pipelines(long dev, long cache) {
    if (cache || !enabled() || (VkDevice)dev != g_dev) return cache;
    pthread_mutex_lock(&g_mx);
    if (!g_cache && p_create) {
        VkPipelineCacheCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO };
        if (g_blob) { ci.initialDataSize = g_blob_len; ci.pInitialData = g_blob; }
        VkPipelineCache c = VK_NULL_HANDLE;
        if (p_create(g_dev, &ci, NULL, &c) == VK_SUCCESS) { g_cache = c; g_owned = 1; LOGI("pipeline cache: the game uses none, ours is used (%s)", g_blob ? "filled from the saved file" : "empty"); }
    }
    long r = (long)g_cache;
    pthread_mutex_unlock(&g_mx);
    return r;
}

void vkshim_pc_after(long dev, long res) {
    if (!g_on || res < 0 || (VkDevice)dev != g_dev) return;
    g_dirty = 1;
    maybe_save();
}

/* the game creates a cache: hand it the saved data when it brings none */
long vkshim_pc_create_info(long dev, long info) {
    static __thread VkPipelineCacheCreateInfo t_ci;
    if (!enabled() || (VkDevice)dev != g_dev || !info || !g_blob) return info;
    const VkPipelineCacheCreateInfo *ci = (const VkPipelineCacheCreateInfo *)info;
    if (ci->initialDataSize != 0) return info;
    t_ci = *ci; t_ci.initialDataSize = g_blob_len; t_ci.pInitialData = g_blob;
    return (long)&t_ci;
}

void vkshim_pc_created(long dev, long cache, long res) {
    if (!g_on || res != 0 || !cache || (VkDevice)dev != g_dev) return;
    pthread_mutex_lock(&g_mx);
    if (!g_cache) { g_cache = (VkPipelineCache)cache; g_owned = 0; LOGI("pipeline cache: the game's own cache is saved too"); }
    pthread_mutex_unlock(&g_mx);
}

void vkshim_pc_destroy(long dev, long cache) {
    if (!g_on || !cache || (VkDevice)dev != g_dev) return;
    pthread_mutex_lock(&g_mx);
    int mine = ((VkPipelineCache)cache == g_cache && !g_owned);
    pthread_mutex_unlock(&g_mx);
    if (!mine) return;
    for (int i = 0; g_saving && i < 200; i++) usleep(1000);   /* let a save in progress finish first */
    write_cache(g_dev, g_cache);                          /* last chance: the game is about to destroy it */
    pthread_mutex_lock(&g_mx); g_cache = VK_NULL_HANDLE; pthread_mutex_unlock(&g_mx);
}

/* ---- a little more priority for the thread that presents frames (once per thread) ---- */
void vkshim_perf_present(void) {
    static __thread int done;
    if (done) return;
    done = 1;
    const char *e = getenv("LAS_PRIO");
    if (!e || *e != '1') return;
    int tid = (int)syscall(SYS_gettid);
    if (setpriority(PRIO_PROCESS, tid, -4) == 0) LOGI("performance: render thread %d priority raised", tid);
    else LOGI("performance: render thread priority not changed (errno %d)", errno);
}
