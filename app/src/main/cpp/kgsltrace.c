/* KGSL trace: an optional black-box recorder for what a GPU driver asks the kernel to do.
 *
 * Both Turnip and the stock Qualcomm driver talk to the GPU through ioctls on /dev/kgsl-3d0. probe.c already points the
 * driver's ioctl import at h_ioctl; when LAS_KGSL_TRACE=1 that function also calls kt_before()/kt_after() here.
 *   trace.txt   : allocation flags (e.g. UBWC and cache bits), context creation flags, and every 5 s a summary of how many
 *                 GPU submissions happened and how big they were
 *   submit_N.kgt: for a window of submissions, copies of the command buffers (and small objects) a submit refers to, so the
 *                 streams of two drivers can be decoded offline and compared (binned vs direct rendering, LRZ, UBWC, ...)
 * It only observes. Nothing is changed in what the driver sends. Off unless LAS_KGSL_TRACE=1.
 * LAS_KGSL_TRACE_DIR (output folder), LAS_KGSL_TRACE_SKIP (submits to ignore first, default 600),
 * LAS_KGSL_TRACE_COUNT (submits to dump, default 30), LAS_KGSL_TRACE_MB (dump size cap, default 96). */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <time.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <linux/ioctl.h>

#define KT_TYPE 0x09
struct kt_cmdobj { uint64_t offset, gpuaddr, size; uint32_t flags, id; };
struct kt_gpucmd { uint64_t flags, cmdlist; uint32_t cmdsize, numcmds; uint64_t objlist; uint32_t objsize, numobjs; uint64_t synclist; uint32_t syncsize, numsyncs, context_id, timestamp; };
struct kt_alloc { uint64_t size, flags, va_len, mmapsize; uint32_t id, metadata_len; uint64_t metadata; };
struct kt_info { uint64_t gpuaddr, flags, size, va_len, va_addr; uint32_t id; };
struct kt_free { uint64_t flags, priv; uint32_t id, type, len; };
struct kt_allocid { uint32_t id, flags; unsigned long size, mmapsize, gpuaddr, pad[2]; };
struct kt_ctx { uint32_t flags, id; };
#define KT_GPU_COMMAND _IOWR(KT_TYPE, 0x4A, struct kt_gpucmd)
#define KT_OBJ_ALLOC   _IOWR(KT_TYPE, 0x45, struct kt_alloc)
#define KT_OBJ_FREE    _IOW(KT_TYPE, 0x46, struct kt_free)
#define KT_OBJ_INFO    _IOWR(KT_TYPE, 0x47, struct kt_info)
#define KT_ALLOC_ID    _IOWR(KT_TYPE, 0x34, struct kt_allocid)
#define KT_CTX_CREATE  _IOWR(KT_TYPE, 0x13, struct kt_ctx)

struct kt_obj { uint32_t id; uint64_t gpuaddr, size, mmapsize, flags; };
struct kt_hdr { uint32_t kind, id; uint64_t gpuaddr, size, objflags, objbase; };

static int kt_on = -1;
static pthread_mutex_t kt_mu = PTHREAD_MUTEX_INITIALIZER;
static struct kt_obj *kt_objs; static int kt_n, kt_cap;
static FILE *kt_log; static char kt_dir[512];
static uint32_t kt_skip = 600, kt_count = 30; static uint64_t kt_cap_bytes = 96ull << 20, kt_dumped;
static uint64_t kt_submits, kt_dumps, kt_cmds, kt_objsum, kt_syncs, kt_cmdbytes, kt_maxcmds, kt_maxobjs;
static uint64_t kt_nr[256];
static struct { uint64_t flags, n, bytes; } kt_fl[64]; static int kt_nfl;
static time_t kt_last;

static uint64_t kt_env(const char *n, uint64_t d) { const char *v = getenv(n); return v && *v ? strtoull(v, NULL, 10) : d; }

int kt_enabled(void) {
    if (kt_on >= 0) return kt_on;
    const char *e = getenv("LAS_KGSL_TRACE");
    int on = e && e[0] == '1';
    if (on) {
        const char *d = getenv("LAS_KGSL_TRACE_DIR");
        if (!d || !*d) d = "/data/local/tmp";
        snprintf(kt_dir, sizeof kt_dir, "%s", d);
        char p[640]; snprintf(p, sizeof p, "%s/trace.txt", kt_dir);
        kt_log = fopen(p, "a");
        if (!kt_log) on = 0;
        kt_skip = (uint32_t)kt_env("LAS_KGSL_TRACE_SKIP", 600);
        kt_count = (uint32_t)kt_env("LAS_KGSL_TRACE_COUNT", 30);
        kt_cap_bytes = kt_env("LAS_KGSL_TRACE_MB", 96) << 20;
        if (kt_log) { fprintf(kt_log, "# kgsl trace start pid=%d skip=%u count=%u\n", getpid(), kt_skip, kt_count); fflush(kt_log); }
    }
    kt_on = on;
    return on;
}

static struct kt_obj *kt_find_id(uint32_t id) { for (int i = 0; i < kt_n; i++) if (kt_objs[i].id == id) return &kt_objs[i]; return NULL; }
static struct kt_obj *kt_find_addr(uint64_t a) { for (int i = 0; i < kt_n; i++) if (a >= kt_objs[i].gpuaddr && a < kt_objs[i].gpuaddr + kt_objs[i].size) return &kt_objs[i]; return NULL; }
static void kt_add(struct kt_obj o) {
    struct kt_obj *x = kt_find_id(o.id);
    if (x) { *x = o; return; }
    if (kt_n == kt_cap) { int nc = kt_cap ? kt_cap * 2 : 1024; void *p = realloc(kt_objs, (size_t)nc * sizeof *kt_objs); if (!p) return; kt_objs = p; kt_cap = nc; }
    kt_objs[kt_n++] = o;
}

static void kt_note_flags(uint64_t f, uint64_t sz) {
    for (int i = 0; i < kt_nfl; i++) if (kt_fl[i].flags == f) { kt_fl[i].n++; kt_fl[i].bytes += sz; return; }
    if (kt_nfl < 64) { kt_fl[kt_nfl].flags = f; kt_fl[kt_nfl].n = 1; kt_fl[kt_nfl].bytes = sz; kt_nfl++; }
}

static void kt_summary(void) {
    time_t now = time(NULL);
    if (now - kt_last < 5) return;
    kt_last = now;
    fprintf(kt_log, "sum submits=%llu cmds=%llu objs=%llu syncs=%llu cmdbytes=%llu maxcmds=%llu maxobjs=%llu dumped=%llu live_objs=%d\n",
            (unsigned long long)kt_submits, (unsigned long long)kt_cmds, (unsigned long long)kt_objsum, (unsigned long long)kt_syncs,
            (unsigned long long)kt_cmdbytes, (unsigned long long)kt_maxcmds, (unsigned long long)kt_maxobjs, (unsigned long long)kt_dumps, kt_n);
    for (int i = 0; i < kt_nfl; i++)
        fprintf(kt_log, "sum alloc flags=0x%llx count=%llu bytes=%llu\n", (unsigned long long)kt_fl[i].flags, (unsigned long long)kt_fl[i].n, (unsigned long long)kt_fl[i].bytes);
    for (int i = 0; i < 256; i++) if (kt_nr[i]) fprintf(kt_log, "sum ioctl nr=0x%02x count=%llu\n", i, (unsigned long long)kt_nr[i]);
    fflush(kt_log);
}

/* copy [off, off+len) of an object by mapping it ourselves: the kgsl mmap offset of an object is id << 12 */
static int kt_write_obj(FILE *f, int fd, uint32_t kind, struct kt_obj *o, uint64_t off, uint64_t len, uint64_t addr) {
    if (!o || off + len > o->size || !len) return 0;
    size_t map = (size_t)(o->mmapsize ? o->mmapsize : o->size);
    void *p = mmap(NULL, map, PROT_READ, MAP_SHARED, fd, (off_t)((uint64_t)o->id << 12));
    if (p == MAP_FAILED) { fprintf(kt_log, "dump: mmap of object %u failed errno=%d\n", o->id, errno); return 0; }
    struct kt_hdr h = { kind, o->id, addr, len, o->flags, o->gpuaddr };
    fwrite(&h, sizeof h, 1, f);
    fwrite((char *)p + off, 1, (size_t)len, f);
    munmap(p, map);
    kt_dumped += len + sizeof h;
    return 1;
}

static void kt_dump_submit(int fd, struct kt_gpucmd *c) {
    char p[640]; snprintf(p, sizeof p, "%s/submit_%05llu.kgt", kt_dir, (unsigned long long)kt_dumps);
    FILE *f = fopen(p, "wb");
    if (!f) return;
    fwrite("KGT1\0\0\0\0", 1, 8, f);
    struct kt_cmdobj *cl = (struct kt_cmdobj *)(uintptr_t)c->cmdlist;
    uint64_t seen_ids[256]; int ns = 0; uint64_t budget = 8ull << 20, used = 0;
    for (uint32_t i = 0; cl && i < c->numcmds && c->cmdsize >= sizeof *cl; i++) {
        struct kt_cmdobj *e = (struct kt_cmdobj *)((char *)cl + (size_t)i * c->cmdsize);
        struct kt_obj *o = e->id ? kt_find_id(e->id) : kt_find_addr(e->gpuaddr);
        uint64_t off = e->id ? e->offset : (o ? e->gpuaddr - o->gpuaddr : 0);
        if (o && kt_write_obj(f, fd, 1, o, off, e->size, o->gpuaddr + off)) { used += e->size; if (ns < 256) seen_ids[ns++] = o->id; }
    }
    struct kt_cmdobj *ol = (struct kt_cmdobj *)(uintptr_t)c->objlist;
    for (uint32_t i = 0; ol && i < c->numobjs && c->objsize >= sizeof *ol && used < budget; i++) {
        struct kt_cmdobj *e = (struct kt_cmdobj *)((char *)ol + (size_t)i * c->objsize);
        struct kt_obj *o = e->id ? kt_find_id(e->id) : kt_find_addr(e->gpuaddr);
        if (!o || o->size > (256u << 10)) continue;
        int dup = 0; for (int k = 0; k < ns; k++) if (seen_ids[k] == o->id) dup = 1;
        if (dup) continue;
        if (kt_write_obj(f, fd, 2, o, 0, o->size, o->gpuaddr)) { used += o->size; if (ns < 256) seen_ids[ns++] = o->id; }
    }
    fclose(f);
    kt_dumps++;
}

void kt_before(int fd, unsigned long cmd, void *arg) {
    if (!kt_enabled() || ((cmd >> 8) & 0xff) != KT_TYPE) return;
    pthread_mutex_lock(&kt_mu);
    kt_nr[cmd & 0xff]++;
    if (cmd == KT_GPU_COMMAND && arg) {
        struct kt_gpucmd *c = arg;
        kt_submits++; kt_cmds += c->numcmds; kt_objsum += c->numobjs; kt_syncs += c->numsyncs;
        if (c->numcmds > kt_maxcmds) kt_maxcmds = c->numcmds;
        if (c->numobjs > kt_maxobjs) kt_maxobjs = c->numobjs;
        struct kt_cmdobj *cl = (struct kt_cmdobj *)(uintptr_t)c->cmdlist;
        for (uint32_t i = 0; cl && i < c->numcmds && c->cmdsize >= sizeof *cl; i++) kt_cmdbytes += ((struct kt_cmdobj *)((char *)cl + (size_t)i * c->cmdsize))->size;
        if (kt_submits > kt_skip && kt_dumps < kt_count && kt_dumped < kt_cap_bytes) kt_dump_submit(fd, c);
        kt_summary();
    }
    pthread_mutex_unlock(&kt_mu);
}

void kt_after(int fd, unsigned long cmd, void *arg, int r) {
    if (!kt_enabled() || ((cmd >> 8) & 0xff) != KT_TYPE || r < 0 || !arg) return;
    pthread_mutex_lock(&kt_mu);
    if (cmd == KT_OBJ_ALLOC) {
        struct kt_alloc *a = arg;
        struct kt_obj o = { a->id, 0, a->size, a->mmapsize, a->flags };
        struct kt_info in; memset(&in, 0, sizeof in); in.id = a->id;
        if (ioctl(fd, KT_OBJ_INFO, &in) == 0) o.gpuaddr = in.gpuaddr;
        kt_add(o); kt_note_flags(a->flags, a->size);
    } else if (cmd == KT_ALLOC_ID) {
        struct kt_allocid *a = arg;
        struct kt_obj o = { a->id, a->gpuaddr, a->size, a->mmapsize, a->flags };
        kt_add(o); kt_note_flags(a->flags, a->size);
    } else if (cmd == KT_OBJ_FREE) {
        struct kt_free *fr = arg;
        for (int i = 0; i < kt_n; i++) if (kt_objs[i].id == fr->id) { kt_objs[i] = kt_objs[--kt_n]; break; }
    } else if (cmd == KT_CTX_CREATE) {
        struct kt_ctx *c = arg;
        fprintf(kt_log, "ctx create flags=0x%x id=%u\n", c->flags, c->id); fflush(kt_log);
    }
    pthread_mutex_unlock(&kt_mu);
}
