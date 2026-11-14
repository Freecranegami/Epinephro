// Frame generation with the Win-FG engine (The412Banner/win-fg, MIT), behind the same C interface as the Lossless Scaling core
// (las_lsfg_*), so the shim (sgsr.c) can use either. No DLL is needed: the shaders are built into this library.
//
// The shim hands over two RGBA16F images (the game's previous and current frame, in GENERAL layout) and one RGBA16F image to
// fill with the in-between frame. The engine makes an RGBA8 frame, which is converted into the output image with a blit.
#include <android/log.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <stdexcept>
#include <map>
#include <string>

#include "framegen.hpp"

#define EXPORT extern "C" __attribute__((visibility("default")))
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "GuestHost", __VA_ARGS__)

namespace {

constexpr int kSlots = 3;   // matches FrameGen::kGmSlots: one in-flight submission per slot

struct Ctx {
    VkImage in[2] {};
    VkImage out = VK_NULL_HANDLE;
    VkImageView vin[2] {};
    VkImage mid = VK_NULL_HANDLE;
    VkDeviceMemory midMem = VK_NULL_HANDLE;
    VkImageView vmid = VK_NULL_HANDLE;
    VkExtent2D ext {};
    VkCommandBuffer cb[kSlots] {};
    VkFence fence[kSlots] {};
    bool used[kSlots] {};
    unsigned long n = 0;
};

winfg::InstanceDispatch g_id;
winfg::DeviceDispatch g_dd;
winfg::FrameGen g_fg;
VkPhysicalDevice g_phys = VK_NULL_HANDLE;
VkDevice g_dev = VK_NULL_HANDLE;
VkQueue g_queue = VK_NULL_HANDLE;
VkCommandPool g_pool = VK_NULL_HANDLE;
VkPhysicalDeviceMemoryProperties g_mem {};
bool g_ready = false;
bool g_perf = true;
std::map<int, Ctx *> g_ctx;
int g_nextId = 1;

void setErr(char *err, int n, const std::string &m) {
    if (err && n > 0) std::snprintf(err, static_cast<size_t>(n), "%s", m.c_str());
}

template <typename T> bool need(T v, const char *name, std::string &why) {
    if (!v) { why = std::string("the GPU driver has no ") + name; return false; }
    return true;
}

bool loadFunctions(PFN_vkGetInstanceProcAddr gipa, VkInstance inst, std::string &why) {
    g_id.GetInstanceProcAddr = gipa;
    g_id.GetPhysicalDeviceMemoryProperties = (PFN_vkGetPhysicalDeviceMemoryProperties)gipa(inst, "vkGetPhysicalDeviceMemoryProperties");
    g_id.GetPhysicalDeviceQueueFamilyProperties = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)gipa(inst, "vkGetPhysicalDeviceQueueFamilyProperties");
    g_id.GetPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gipa(inst, "vkGetPhysicalDeviceProperties");
    if (!need(g_id.GetPhysicalDeviceMemoryProperties, "vkGetPhysicalDeviceMemoryProperties", why)) return false;
    auto gdpa = (PFN_vkGetDeviceProcAddr)gipa(inst, "vkGetDeviceProcAddr");
    if (!need(gdpa, "vkGetDeviceProcAddr", why)) return false;
    g_dd.GetDeviceProcAddr = gdpa;
#define L(n) winfg::load(g_dd.n, gdpa, g_dev, "vk" #n)
    L(QueueSubmit); L(QueueWaitIdle); L(DeviceWaitIdle);
    L(CreateImage); L(DestroyImage); L(CreateImageView); L(DestroyImageView);
    L(AllocateMemory); L(FreeMemory); L(BindImageMemory); L(GetImageMemoryRequirements);
    L(CreateBuffer); L(DestroyBuffer); L(GetBufferMemoryRequirements); L(BindBufferMemory); L(MapMemory); L(UnmapMemory);
    L(CreateSampler); L(DestroySampler);
    L(CreateShaderModule); L(DestroyShaderModule);
    L(CreateDescriptorSetLayout); L(DestroyDescriptorSetLayout); L(CreatePipelineLayout); L(DestroyPipelineLayout);
    L(CreateComputePipelines); L(DestroyPipeline); L(CreateDescriptorPool); L(DestroyDescriptorPool);
    L(ResetDescriptorPool); L(AllocateDescriptorSets); L(UpdateDescriptorSets);
    L(CreateCommandPool); L(DestroyCommandPool); L(AllocateCommandBuffers); L(FreeCommandBuffers);
    L(BeginCommandBuffer); L(EndCommandBuffer); L(CmdBindPipeline); L(CmdBindDescriptorSets); L(CmdDispatch);
    L(CmdPipelineBarrier); L(CmdCopyImage); L(CmdBlitImage); L(CmdClearColorImage);
    L(CreateFence); L(DestroyFence); L(WaitForFences); L(ResetFences);
#undef L
    return need(g_dd.QueueSubmit, "vkQueueSubmit", why) && need(g_dd.CreateImage, "vkCreateImage", why) &&
           need(g_dd.CreateComputePipelines, "vkCreateComputePipelines", why) && need(g_dd.CmdBlitImage, "vkCmdBlitImage", why) &&
           need(g_dd.CreateFence, "vkCreateFence", why) && need(g_dd.WaitForFences, "vkWaitForFences", why) &&
           need(g_dd.CreateCommandPool, "vkCreateCommandPool", why);
}

uint32_t memType(uint32_t bits) {
    for (uint32_t i = 0; i < g_mem.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g_mem.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) return i;
    for (uint32_t i = 0; i < g_mem.memoryTypeCount; i++) if (bits & (1u << i)) return i;
    return 0;
}

void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to) {
    VkImageMemoryBarrier b {};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT;
    b.dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT;
    b.oldLayout = from; b.newLayout = to;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img;
    b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    g_dd.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
}

void memBarrier(VkCommandBuffer cb) {
    VkMemoryBarrier m {};
    m.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    m.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT;
    m.dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT;
    g_dd.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &m, 0, nullptr, 0, nullptr);
}

bool makeView(VkImage img, VkFormat fmt, VkImageView &v) {
    VkImageViewCreateInfo vi { VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
    vi.image = img; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = fmt;
    vi.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    return g_dd.CreateImageView(g_dev, &vi, nullptr, &v) == VK_SUCCESS;
}

void destroyCtx(Ctx *c) {
    if (!c) return;
    if (g_dd.DeviceWaitIdle) g_dd.DeviceWaitIdle(g_dev);
    for (int i = 0; i < kSlots; i++) if (c->fence[i]) g_dd.DestroyFence(g_dev, c->fence[i], nullptr);
    if (c->cb[0] && g_pool) g_dd.FreeCommandBuffers(g_dev, g_pool, kSlots, c->cb);
    for (int i = 0; i < 2; i++) if (c->vin[i]) g_dd.DestroyImageView(g_dev, c->vin[i], nullptr);
    if (c->vmid) g_dd.DestroyImageView(g_dev, c->vmid, nullptr);
    if (c->mid) g_dd.DestroyImage(g_dev, c->mid, nullptr);
    if (c->midMem) g_dd.FreeMemory(g_dev, c->midMem, nullptr);
    delete c;
}

}  // namespace

// returns 0 on success. dll is not used; flowScale and generated are not used either (the engine makes one frame per real frame)
EXPORT int las_lsfg_init(void *gipa, void *instance, void *phys, void *dev, uint32_t qfam, void *queue,
                         int perf, float /*flowScale*/, int /*generated*/, int /*hdr*/, const char * /*dll*/, char *err, int errlen) {
    try {
        if (g_ready) return 0;
        g_phys = reinterpret_cast<VkPhysicalDevice>(phys);
        g_dev = reinterpret_cast<VkDevice>(dev);
        g_queue = reinterpret_cast<VkQueue>(queue);
        g_perf = perf != 0;
        std::string why;
        if (!loadFunctions(reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa), reinterpret_cast<VkInstance>(instance), why)) {
            setErr(err, errlen, why);
            return -1;
        }
        g_id.GetPhysicalDeviceMemoryProperties(g_phys, &g_mem);
        VkCommandPoolCreateInfo pi { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
        pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pi.queueFamilyIndex = qfam;
        if (g_dd.CreateCommandPool(g_dev, &pi, nullptr, &g_pool) != VK_SUCCESS) { setErr(err, errlen, "command pool"); return -1; }
        winfg::Config c;
        c.enabled = true;
        c.model = g_perf ? 3 : 4;           // 3 = optical flow (lighter), 4 = bidirectional with occlusion gate
        c.perfPreset = g_perf ? 2 : 1;      // how fine the flow search goes
        c.multiplier = 2;
        c.pacing = false;
        c.debug = false;
        c.sanitize();
        if (!g_fg.init(&g_dd, &g_id, g_phys, g_dev, qfam, g_queue)) { setErr(err, errlen, "the engine could not start"); return -1; }
        g_fg.configure(c);
        g_ready = true;
        LOG("winfg: engine ready (%s mode: model %d, flow preset %d)", g_perf ? "performance" : "quality", c.model, c.perfPreset);
        return 0;
    } catch (const std::exception &e) {
        setErr(err, errlen, e.what());
    } catch (...) {
        setErr(err, errlen, "unknown error");
    }
    return -1;
}

// the images must be in GENERAL layout; returns the context id, or -1
EXPORT int las_lsfg_context(uint64_t in0, uint64_t in1, const uint64_t *outs, int nouts, uint32_t w, uint32_t h, int format,
                            char *err, int errlen) {
    Ctx *c = nullptr;
    try {
        if (!g_ready || nouts < 1) { setErr(err, errlen, "not started"); return -1; }
        c = new Ctx();
        c->in[0] = reinterpret_cast<VkImage>(in0);
        c->in[1] = reinterpret_cast<VkImage>(in1);
        c->out = reinterpret_cast<VkImage>(outs[0]);
        c->ext = { w, h };
        VkFormat fmt = static_cast<VkFormat>(format);
        for (int i = 0; i < 2; i++) if (!makeView(c->in[i], fmt, c->vin[i])) throw std::runtime_error("input view");
        // the frame the engine writes (its synthesis shader stores RGBA8)
        VkImageCreateInfo ci { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
        ci.imageType = VK_IMAGE_TYPE_2D; ci.format = VK_FORMAT_R8G8B8A8_UNORM; ci.extent = { w, h, 1 };
        ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
        ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (g_dd.CreateImage(g_dev, &ci, nullptr, &c->mid) != VK_SUCCESS) throw std::runtime_error("output image");
        VkMemoryRequirements mr;
        g_dd.GetImageMemoryRequirements(g_dev, c->mid, &mr);
        VkMemoryAllocateInfo ai { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
        ai.allocationSize = mr.size; ai.memoryTypeIndex = memType(mr.memoryTypeBits);
        if (g_dd.AllocateMemory(g_dev, &ai, nullptr, &c->midMem) != VK_SUCCESS) throw std::runtime_error("output memory");
        if (g_dd.BindImageMemory(g_dev, c->mid, c->midMem, 0) != VK_SUCCESS) throw std::runtime_error("bind output memory");
        if (!makeView(c->mid, VK_FORMAT_R8G8B8A8_UNORM, c->vmid)) throw std::runtime_error("output view");
        VkCommandBufferAllocateInfo cai { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
        cai.commandPool = g_pool; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = kSlots;
        if (g_dd.AllocateCommandBuffers(g_dev, &cai, c->cb) != VK_SUCCESS) throw std::runtime_error("command buffers");
        VkFenceCreateInfo fi { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        for (int i = 0; i < kSlots; i++) if (g_dd.CreateFence(g_dev, &fi, nullptr, &c->fence[i]) != VK_SUCCESS) throw std::runtime_error("fence");
        if (!g_fg.onResize({ w, h }, fmt, true)) throw std::runtime_error("the engine could not size itself for this picture");
        int id = g_nextId++;
        g_ctx[id] = c;
        LOG("winfg: context %d at %ux%u", id, w, h);
        return id;
    } catch (const std::exception &e) {
        setErr(err, errlen, e.what());
    } catch (...) {
        setErr(err, errlen, "unknown error");
    }
    destroyCtx(c);
    return -1;
}

// makes the in-between frame of the last two game frames and queues it on the queue given at init; returns 0 on success
EXPORT int las_lsfg_present(int id, char *err, int errlen) {
    try {
        auto it = g_ctx.find(id);
        if (it == g_ctx.end()) { setErr(err, errlen, "no such context"); return -1; }
        Ctx *c = it->second;
        unsigned long k = c->n++;
        int slot = static_cast<int>(k % kSlots);
        if (c->used[slot]) {   // the submission made kSlots frames ago must be finished before its slot is reused
            if (g_dd.WaitForFences(g_dev, 1, &c->fence[slot], VK_TRUE, 2000000000ull) != VK_SUCCESS) { setErr(err, errlen, "the GPU is stuck"); return -1; }
            g_dd.ResetFences(g_dev, 1, &c->fence[slot]);
        }
        VkCommandBuffer cb = c->cb[slot];
        VkCommandBufferBeginInfo bi { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (g_dd.BeginCommandBuffer(cb, &bi) != VK_SUCCESS) { setErr(err, errlen, "begin"); return -1; }
        memBarrier(cb);                                  // everything the shim queued before this (the frame copy) is done first
        VkImage newest = c->in[k & 1], older = c->in[(k + 1) & 1];
        VkImageView vnew = c->vin[k & 1], vold = c->vin[(k + 1) & 1];
        barrier(cb, newest, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        barrier(cb, older, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        barrier(cb, c->mid, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        g_fg.record(cb, vold, vnew, c->vmid, 0.5f, static_cast<uint32_t>(slot));
        barrier(cb, newest, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        barrier(cb, older, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        VkImageBlit bl {};
        bl.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
        bl.dstSubresource = bl.srcSubresource;
        bl.srcOffsets[1] = { static_cast<int32_t>(c->ext.width), static_cast<int32_t>(c->ext.height), 1 };
        bl.dstOffsets[1] = bl.srcOffsets[1];
        g_dd.CmdBlitImage(cb, c->mid, VK_IMAGE_LAYOUT_GENERAL, c->out, VK_IMAGE_LAYOUT_GENERAL, 1, &bl, VK_FILTER_NEAREST);
        memBarrier(cb);                                  // and the shim's next submit sees the finished frame
        if (g_dd.EndCommandBuffer(cb) != VK_SUCCESS) { setErr(err, errlen, "end"); return -1; }
        VkSubmitInfo si { VK_STRUCTURE_TYPE_SUBMIT_INFO };
        si.commandBufferCount = 1; si.pCommandBuffers = &cb;
        if (g_dd.QueueSubmit(g_queue, 1, &si, c->fence[slot]) != VK_SUCCESS) { setErr(err, errlen, "submit"); return -1; }
        c->used[slot] = true;
        return 0;
    } catch (const std::exception &e) {
        setErr(err, errlen, e.what());
    } catch (...) {
        setErr(err, errlen, "unknown error");
    }
    return -1;
}

EXPORT void las_lsfg_delete(int id) {
    try {
        auto it = g_ctx.find(id);
        if (it == g_ctx.end()) return;
        destroyCtx(it->second);
        g_ctx.erase(it);
    } catch (...) {}
}
