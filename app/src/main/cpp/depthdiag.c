// Depth buffer diagnostic (LAS_DEPTHDIAG=1). Nothing is changed in the game: this only watches which depth images the game
// makes, which render passes use them, how many draws each gets and what the pass does with the depth at its end, then
// prints a summary into the log. It answers: could screen-space effects (ambient occlusion, depth fog, depth of field)
// read this game's depth, and what would the layer have to change to get it?
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "vkshim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

int vkshim_dd_on(void) {
    static int s = -1;
    if (s < 0) { const char *e = getenv("LAS_DEPTHDIAG"); s = (e && e[0] == '1') ? 1 : 0; if (s) LOGI("depthdiag: on"); }
    return s;
}

// ---- small tables ----
#define NMAP 2048
typedef struct { uint64_t k[NMAP]; int v[NMAP]; } map_t;
static int map_get(const map_t *m, uint64_t k) {
    if (!k) return -1;
    uint32_t i = (uint32_t)((k * 0x9E3779B97F4A7C15ull) >> 53) & (NMAP - 1);
    for (int p = 0; p < NMAP; p++) {
        uint32_t j = (i + p) & (NMAP - 1);
        if (!m->k[j]) return -1;
        if (m->k[j] == k) return m->v[j];
    }
    return -1;
}
static void map_put(map_t *m, uint64_t k, int v) {
    if (!k) return;
    uint32_t i = (uint32_t)((k * 0x9E3779B97F4A7C15ull) >> 53) & (NMAP - 1);
    for (int p = 0; p < NMAP; p++) {
        uint32_t j = (i + p) & (NMAP - 1);
        if (!m->k[j] || m->k[j] == k) { m->k[j] = k; m->v[j] = v; return; }
    }
}

#define NI 128
typedef struct {
    uint64_t handle;
    uint32_t fmt, w, h, layers, samples, usage, flags;
    // the window since the last summary
    uint64_t passes, draws;
    uint32_t aw, ah;                    // last render area
    uint32_t load, store, sload, sstore, fin;
    float clear; int has_clear;
    int colorN; uint32_t color0;
    int secondary;                      // passes that run secondary command buffers (their draws are not counted here)
    int dynamic;                        // seen through dynamic rendering (no render pass object)
} dd_img;
typedef struct {
    int depth;                          // attachment index of the depth attachment, -1 none
    uint32_t fmt, samples, load, store, sload, sstore, init, fin;
    int colorN; uint32_t color0;
} dd_rp;
#define MAXV 10
typedef struct { int rp; uint64_t views[MAXV]; int n; uint32_t w, h; int imageless; } dd_fb;

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static dd_img g_img[NI]; static int g_ni;
static dd_rp g_rp[512]; static int g_nrp;
static dd_fb g_fb[2048]; static int g_nfb;
static map_t m_img, m_view, m_rp, m_fb;
static uint64_t g_nodepth_passes, g_nodepth_draws, g_untracked_passes;
static volatile uint64_t g_orphan_draws;
static uint64_t g_frames, g_frames_total;

static int is_depth_fmt(uint32_t f) { return f >= 124 && f <= 130; }
static const char *fmt_name(uint32_t f) {
    switch (f) {
    case 124: return "D16_UNORM"; case 125: return "X8_D24_UNORM"; case 126: return "D32_SFLOAT"; case 127: return "S8_UINT";
    case 128: return "D16_UNORM_S8"; case 129: return "D24_UNORM_S8"; case 130: return "D32_SFLOAT_S8";
    case 37: return "RGBA8_UNORM"; case 43: return "RGBA8_SRGB"; case 44: return "BGRA8_UNORM"; case 50: return "BGRA8_SRGB";
    case 64: return "A2B10G10R10"; case 97: return "RGBA16_SFLOAT"; case 109: return "RGBA32_SFLOAT"; case 122: return "B10G11R11_UFLOAT";
    default: return "fmt?";
    }
}
static const char *load_name(uint32_t o) { return o == 0 ? "LOAD" : o == 1 ? "CLEAR" : o == 2 ? "DONT_CARE" : o == 1000400000u ? "NONE" : "?"; }
static const char *store_name(uint32_t o) { return o == 0 ? "STORE" : o == 1 ? "DONT_CARE" : o == 1000301000u ? "NONE" : "?"; }
static const char *layout_name(uint32_t l) {
    switch (l) {
    case 0: return "UNDEFINED"; case 1: return "GENERAL"; case 2: return "COLOR_ATTACHMENT"; case 3: return "DEPTH_STENCIL_ATTACHMENT";
    case 4: return "DEPTH_STENCIL_READ_ONLY"; case 5: return "SHADER_READ_ONLY"; case 6: return "TRANSFER_SRC"; case 7: return "TRANSFER_DST";
    case 1000117000: return "DEPTH_READ_ONLY_STENCIL_ATT"; case 1000117001: return "DEPTH_ATT_STENCIL_READ_ONLY";
    case 1000241000: return "DEPTH_ATTACHMENT"; case 1000241001: return "DEPTH_READ_ONLY";
    default: return "layout?";
    }
}
static void usage_str(uint32_t u, char *o, size_t n) {
    o[0] = 0;
    if (u & 0x1) strncat(o, "transfer_src ", n - strlen(o) - 1);
    if (u & 0x4) strncat(o, "SAMPLED ", n - strlen(o) - 1);
    if (u & 0x20) strncat(o, "depth_att ", n - strlen(o) - 1);
    if (u & 0x40) strncat(o, "TRANSIENT ", n - strlen(o) - 1);
    if (u & 0x80) strncat(o, "input_att ", n - strlen(o) - 1);
    if (!o[0]) strncat(o, "-", n - 1);
}

static long now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000L + t.tv_nsec / 1000000L; }

// ---- creation hooks (called after the real call) ----
void vkshim_dd_image(long info, long pImage, long r) {
    if (!vkshim_dd_on() || r != 0 || !info || !pImage) return;
    const uint32_t *v = (const uint32_t *)info;           // VkImageCreateInfo
    if (v[5] != 1 || !is_depth_fmt(v[6])) return;         // 2D depth formats only
    uint64_t h = *(const uint64_t *)pImage;
    pthread_mutex_lock(&g_mu);
    if (g_ni < NI) {
        dd_img *d = &g_img[g_ni];
        memset(d, 0, sizeof *d);
        d->handle = h; d->flags = v[4]; d->fmt = v[6]; d->w = v[7]; d->h = v[8]; d->layers = v[11]; d->samples = v[12]; d->usage = v[14];
        map_put(&m_img, h, g_ni);
        g_ni++;
    }
    pthread_mutex_unlock(&g_mu);
}

void vkshim_dd_view(long info, long pView, long r) {
    if (!vkshim_dd_on() || r != 0 || !info || !pView) return;
    uint64_t image = *(const uint64_t *)(info + 24), view = *(const uint64_t *)pView;
    pthread_mutex_lock(&g_mu);
    int i = map_get(&m_img, image);
    if (i >= 0) map_put(&m_view, view, i);
    pthread_mutex_unlock(&g_mu);
}

// ver 1: VkRenderPassCreateInfo, ver 2: VkRenderPassCreateInfo2
void vkshim_dd_rp(long info, long pRP, long r, long ver) {
    if (!vkshim_dd_on() || r != 0 || !info || !pRP) return;
    const unsigned char *p = (const unsigned char *)info;
    uint32_t nat = *(const uint32_t *)(p + 20), nsub = *(const uint32_t *)(p + 32);
    const unsigned char *at = *(const unsigned char **)(p + 24), *sub = *(const unsigned char **)(p + 40);
    size_t atsz = ver == 2 ? 56 : 36, subsz = ver == 2 ? 88 : 72;
    if (!at || !sub || !nsub || nat > 32) return;
    dd_rp rp; memset(&rp, 0, sizeof rp); rp.depth = -1;
    // the first subpass that has a depth attachment decides; the colour count and format come from the first subpass
    int color_done = 0;
    for (uint32_t s = 0; s < nsub && s < 8; s++) {
        const unsigned char *sd = sub + s * subsz;
        uint32_t ncol; const unsigned char *pcol, *pdep; size_t refsz;
        if (ver == 2) { ncol = *(const uint32_t *)(sd + 40); pcol = *(const unsigned char **)(sd + 48); pdep = *(const unsigned char **)(sd + 64); refsz = 32; }
        else { ncol = *(const uint32_t *)(sd + 24); pcol = *(const unsigned char **)(sd + 32); pdep = *(const unsigned char **)(sd + 48); refsz = 8; }
        size_t aoff = ver == 2 ? 16 : 0;                   // the attachment index inside a reference
        if (!color_done) {
            rp.colorN = (int)ncol; color_done = 1;
            if (ncol && pcol) {
                uint32_t ci = *(const uint32_t *)(pcol + aoff);
                if (ci < nat) rp.color0 = *(const uint32_t *)(at + ci * atsz + (ver == 2 ? 20 : 4));
            }
            (void)refsz;
        }
        if (pdep && rp.depth < 0) {
            uint32_t di = *(const uint32_t *)(pdep + aoff);
            if (di < nat) rp.depth = (int)di;
        }
    }
    if (rp.depth >= 0) {
        const unsigned char *a = at + (size_t)rp.depth * atsz;
        size_t o = ver == 2 ? 20 : 4;                      // format
        rp.fmt = *(const uint32_t *)(a + o); rp.samples = *(const uint32_t *)(a + o + 4);
        rp.load = *(const uint32_t *)(a + o + 8); rp.store = *(const uint32_t *)(a + o + 12);
        rp.sload = *(const uint32_t *)(a + o + 16); rp.sstore = *(const uint32_t *)(a + o + 20);
        rp.init = *(const uint32_t *)(a + o + 24); rp.fin = *(const uint32_t *)(a + o + 28);
    }
    uint64_t h = *(const uint64_t *)pRP;
    pthread_mutex_lock(&g_mu);
    if (g_nrp < 512) { g_rp[g_nrp] = rp; map_put(&m_rp, h, g_nrp); g_nrp++; }
    pthread_mutex_unlock(&g_mu);
}

void vkshim_dd_fb(long info, long pFB, long r) {
    if (!vkshim_dd_on() || r != 0 || !info || !pFB) return;
    const unsigned char *p = (const unsigned char *)info;   // VkFramebufferCreateInfo
    dd_fb f; memset(&f, 0, sizeof f);
    f.imageless = (*(const uint32_t *)(p + 16) & 1) != 0;
    uint64_t rpH = *(const uint64_t *)(p + 24);
    uint32_t n = *(const uint32_t *)(p + 32);
    const uint64_t *views = *(const uint64_t *const *)(p + 40);
    f.w = *(const uint32_t *)(p + 48); f.h = *(const uint32_t *)(p + 52);
    if (!f.imageless && views) { f.n = n > MAXV ? MAXV : (int)n; for (int i = 0; i < f.n; i++) f.views[i] = views[i]; }
    uint64_t h = *(const uint64_t *)pFB;
    pthread_mutex_lock(&g_mu);
    f.rp = map_get(&m_rp, rpH);
    if (g_nfb < 2048) { g_fb[g_nfb] = f; map_put(&m_fb, h, g_nfb); g_nfb++; }
    pthread_mutex_unlock(&g_mu);
}

// ---- recording: the pass this thread is inside ----
typedef struct { int valid; int img; uint64_t draws; uint32_t aw, ah; uint32_t load, store, sload, sstore, fin; float clear; int has_clear; int colorN; uint32_t color0; int secondary, dynamic; } dd_pass;
static __thread dd_pass tl;

// VkRenderPassBeginInfo; contents is 1 for secondary command buffers (0 when unknown)
void vkshim_dd_begin(long info, long contents) {
    if (!vkshim_dd_on() || !info) return;
    const unsigned char *p = (const unsigned char *)info;
    uint64_t rpH = *(const uint64_t *)(p + 16), fbH = *(const uint64_t *)(p + 24);
    memset(&tl, 0, sizeof tl);
    tl.valid = 1; tl.img = -1; tl.secondary = contents == 1;
    tl.aw = *(const uint32_t *)(p + 40); tl.ah = *(const uint32_t *)(p + 44);
    uint32_t ncv = *(const uint32_t *)(p + 48);
    const unsigned char *cv = *(const unsigned char *const *)(p + 56);
    pthread_mutex_lock(&g_mu);
    int ri = map_get(&m_rp, rpH), fi = map_get(&m_fb, fbH);
    if (ri >= 0) {
        const dd_rp *rp = &g_rp[ri];
        tl.colorN = rp->colorN; tl.color0 = rp->color0;
        if (rp->depth >= 0) {
            tl.load = rp->load; tl.store = rp->store; tl.sload = rp->sload; tl.sstore = rp->sstore; tl.fin = rp->fin;
            if (fi >= 0 && rp->depth < g_fb[fi].n) tl.img = map_get(&m_view, g_fb[fi].views[rp->depth]);
            if (tl.img < 0) tl.img = -2;                   // a depth pass whose image we did not see being made
            if (rp->load == 1 && cv && (uint32_t)rp->depth < ncv) { tl.clear = *(const float *)(cv + 16 * (size_t)rp->depth); tl.has_clear = 1; }
        }
    }
    pthread_mutex_unlock(&g_mu);
}

// VkRenderingInfo (dynamic rendering)
void vkshim_dd_begin_dyn(long info) {
    if (!vkshim_dd_on() || !info) return;
    const unsigned char *p = (const unsigned char *)info;
    memset(&tl, 0, sizeof tl);
    tl.valid = 1; tl.img = -1; tl.dynamic = 1;
    tl.aw = *(const uint32_t *)(p + 28); tl.ah = *(const uint32_t *)(p + 32);
    tl.colorN = (int)*(const uint32_t *)(p + 44);
    const unsigned char *d = *(const unsigned char *const *)(p + 56);
    if (!d) return;
    uint64_t view = *(const uint64_t *)(d + 16);
    tl.load = *(const uint32_t *)(d + 44); tl.store = *(const uint32_t *)(d + 48);
    if (tl.load == 1) { tl.clear = *(const float *)(d + 52); tl.has_clear = 1; }
    pthread_mutex_lock(&g_mu);
    tl.img = map_get(&m_view, view);
    pthread_mutex_unlock(&g_mu);
    if (tl.img < 0) tl.img = -2;
}

void vkshim_dd_draw(void) {
    if (!vkshim_dd_on()) return;
    if (tl.valid) tl.draws++; else __atomic_add_fetch(&g_orphan_draws, 1, __ATOMIC_RELAXED);
}

void vkshim_dd_end(void) {
    if (!vkshim_dd_on() || !tl.valid) return;
    pthread_mutex_lock(&g_mu);
    if (tl.img >= 0 && tl.img < g_ni) {
        dd_img *d = &g_img[tl.img];
        d->passes++; d->draws += tl.draws; d->aw = tl.aw; d->ah = tl.ah;
        d->load = tl.load; d->store = tl.store; d->sload = tl.sload; d->sstore = tl.sstore; d->fin = tl.fin;
        if (tl.has_clear) { d->clear = tl.clear; d->has_clear = 1; }
        d->colorN = tl.colorN; d->color0 = tl.color0;
        if (tl.secondary) d->secondary++;
        if (tl.dynamic) d->dynamic = 1;
    } else if (tl.img == -2) {
        g_untracked_passes++;
    } else {
        g_nodepth_passes++; g_nodepth_draws += tl.draws;
    }
    pthread_mutex_unlock(&g_mu);
    tl.valid = 0;
}

// ---- the summary ----
static int cmp_draws(const void *a, const void *b) {
    const dd_img *x = *(const dd_img *const *)a, *y = *(const dd_img *const *)b;
    if (x->draws != y->draws) return x->draws < y->draws ? 1 : -1;
    uint64_t ax = (uint64_t)x->w * x->h, ay = (uint64_t)y->w * y->h;
    return ax < ay ? 1 : ax > ay ? -1 : 0;
}

static void summary(long secs) {
    pthread_mutex_lock(&g_mu);
    double fr = g_frames ? (double)g_frames : 1.0;
    dd_img *act[NI]; int na = 0;
    uint32_t sizes[64][2]; int nsz = 0;
    for (int i = 0; i < g_ni; i++) {
        if (g_img[i].passes) act[na++] = &g_img[i];
        int seen = 0;
        for (int k = 0; k < nsz; k++) if (sizes[k][0] == g_img[i].w && sizes[k][1] == g_img[i].h) { seen = 1; break; }
        if (!seen && nsz < 64) { sizes[nsz][0] = g_img[i].w; sizes[nsz][1] = g_img[i].h; nsz++; }
    }
    qsort(act, na, sizeof act[0], cmp_draws);
    LOGI("depthdiag: ---- t=%lds, %llu frames in this window: %d depth images created in total (%d different sizes), %d used by render passes ----",
         secs, (unsigned long long)g_frames, g_ni, nsz, na);
    for (int i = 0; i < na && i < 6; i++) {
        const dd_img *d = act[i];
        char u[96]; usage_str(d->usage, u, sizeof u);
        LOGI("depthdiag: #%d %s %ux%u x%u samples=%u usage=[%s]%s | per frame: passes %.1f draws %.0f%s | depth load=%s store=%s%s | stencil load=%s store=%s | ends in %s | colour: %d x %s | render area %ux%u",
             i + 1, fmt_name(d->fmt), d->w, d->h, d->layers, d->samples, u,
             (d->flags & 0x1) ? " sparse" : "",
             d->passes / fr, d->draws / fr, d->secondary ? " (+secondary command buffers, draws not counted)" : "",
             d->dynamic ? "(dynamic rendering)" : load_name(d->load), d->dynamic ? "(dynamic rendering)" : store_name(d->store),
             d->has_clear ? (d->clear < 0.5f ? " clear=0.0 (reversed Z)" : " clear=1.0 (normal Z)") : "",
             load_name(d->sload), store_name(d->sstore), layout_name(d->fin), d->colorN, fmt_name(d->color0), d->aw, d->ah);
    }
    if (na > 0) {
        const dd_img *d = act[0];
        char adv[400]; adv[0] = 0;
        if (d->samples > 1) strcat(adv, " multisampled (needs a resolve first);");
        if (d->usage & 0x40) strcat(adv, " TRANSIENT (cannot be sampled, may never reach memory);");
        else if (!(d->usage & 0x4)) strcat(adv, " not sampleable yet (the layer would add the SAMPLED usage);");
        else strcat(adv, " the game already samples it (a good sign);");
        if (!d->dynamic) {
            if (d->store == 1) strcat(adv, " the pass discards the depth at its end (the layer would have to change that to STORE: costs bandwidth);");
            else if (d->store == 0) strcat(adv, " the pass keeps the depth (readable after the pass);");
        } else strcat(adv, " dynamic rendering: see its store operation in the game's own code;");
        if (d->secondary) strcat(adv, " draws come from secondary command buffers;");
        LOGI("depthdiag: main candidate: #1 (%s %ux%u):%s", fmt_name(d->fmt), d->w, d->h, adv);
    } else {
        LOGI("depthdiag: no render pass used a depth image we saw created (the game may use its own depth in another way, or the depth was made before the layer started)");
    }
    LOGI("depthdiag: passes without depth: %llu (%llu draws); depth passes on images not seen created: %llu; draws outside any pass (secondary command buffers): %llu",
         (unsigned long long)g_nodepth_passes, (unsigned long long)g_nodepth_draws, (unsigned long long)g_untracked_passes,
         (unsigned long long)g_orphan_draws);
    // start a new window
    for (int i = 0; i < g_ni; i++) { g_img[i].passes = 0; g_img[i].draws = 0; g_img[i].secondary = 0; }
    g_nodepth_passes = g_nodepth_draws = g_untracked_passes = 0; g_orphan_draws = 0; g_frames = 0;
    pthread_mutex_unlock(&g_mu);
}

void vkshim_dd_present(void) {
    if (!vkshim_dd_on()) return;
    static const long marks[] = { 6000, 15000, 40000, 120000 };
    static long t0; static int next;
    long t = now_ms();
    if (!t0) t0 = t;
    __atomic_add_fetch(&g_frames, 1, __ATOMIC_RELAXED);
    g_frames_total++;
    if (next < 4 && t - t0 >= marks[next]) { next++; summary((t - t0) / 1000); }
}
