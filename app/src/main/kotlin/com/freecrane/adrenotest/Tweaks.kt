package com.freecrane.adrenotest

import android.content.Context
import java.io.File
import android.system.Os
import android.util.Log

// Performance-related switches: light mode (no diagnostics) and EpiShaders effects on the picture the game presents.
// The choices are stored in SharedPreferences by the main app and handed to the game process through LAS_* variables.
object Tweaks {
    private const val PREFS = "las"

    fun light(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("light_mode", true)   // on by default: diagnostics cost CPU on every call
    fun setLight(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("light_mode", on).commit() }
    fun depthDiag(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("depth_diag", false)
    fun setDepthDiag(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("depth_diag", on).commit() }

    // Turnip (open-source Mesa driver) settings, handed over as environment variables before the driver loads.
    // Render mode: 0 = the driver decides, 1 = GMEM (tiled, on-chip memory), 2 = sysmem (direct, no tiling).
    // Extra variables: one NAME=VALUE per line (for example TU_DEBUG=noubwc, FD_MESA_DEBUG=...). Only Turnip reads them.
    val tuModes = listOf("Auto (driver decides)", "GMEM (tiled rendering)", "Sysmem (direct rendering)")
    fun tuMode(ctx: Context): Int = Prefs.get(ctx).getInt("tu_mode", 0).coerceIn(0, 2)
    fun setTuMode(ctx: Context, i: Int) { Prefs.get(ctx).edit().putInt("tu_mode", i).commit() }
    fun tuExtra(ctx: Context): String = Prefs.get(ctx).getString("tu_extra", "") ?: ""
    fun setTuExtra(ctx: Context, t: String) { Prefs.get(ctx).edit().putString("tu_extra", t).commit() }

    private fun applyTurnipEnv(ctx: Context) {
        val vars = LinkedHashMap<String, String>()
        for (raw in tuExtra(ctx).lines()) {
            val l = raw.trim()
            val i = l.indexOf('=')
            if (i <= 0) continue
            val k = l.substring(0, i).trim()
            if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(k) || k.startsWith("LAS_") || k == "LD_PRELOAD" || k == "LD_LIBRARY_PATH") continue
            vars[k] = l.substring(i + 1).trim()
        }
        val flag = when (tuMode(ctx)) { 1 -> "gmem"; 2 -> "sysmem"; else -> null }
        if (flag != null) {
            val cur = vars["TU_DEBUG"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "gmem" && it != "sysmem" } ?: emptyList()
            vars["TU_DEBUG"] = (cur + flag).joinToString(",")
        }
        for ((k, v) in vars) Os.setenv(k, v, true)
        Log.i("GuestHost", "turnip env: " + (if (vars.isEmpty()) "none" else vars.entries.joinToString(" ") { "${it.key}=${it.value}" }) +
            " (only the Turnip driver reads these)")
    }

    // OpenGL ES games through ANGLE (OpenGL ES translated to Vulkan, running on our Vulkan layer and custom driver).
    fun angle(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("angle", false)
    fun setAngle(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("angle", on).commit() }

    // Saved pipeline cache (Vulkan shader compilation results kept between sessions) and sustained performance
    // (the phone's sustained-performance mode, when it has one, plus a little more priority for the render thread).
    fun pcache(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("pcache", true)
    fun setPcache(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("pcache", on).commit() }
    fun sustained(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("sustained", true)
    fun setSustained(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("sustained", on).commit() }

    // FSR upscaling maths in half (16-bit) precision: faster on Adreno; switch off if the picture shows artefacts.
    fun fsrHalf(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("fsr_half", true)
    fun setFsrHalf(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("fsr_half", on).commit() }

    // Ignore the notch / camera cut-out: the game's window is drawn into the cut-out area too (the whole screen).
    fun ignoreNotch(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("ignore_notch", false)
    fun setIgnoreNotch(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("ignore_notch", on).commit() }

    // Called for the game's window in the game process (when it is created, and again when it resumes).
    fun applyWindow(a: android.app.Activity) {
        try {
            if (ignoreNotch(a) && android.os.Build.VERSION.SDK_INT >= 28) {
                val w = a.window
                val mode = if (android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                           else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                val lp = w.attributes
                Log.i("GuestHost", "display: notch setting wanted $mode, window has ${lp.layoutInDisplayCutoutMode}")
                if (lp.layoutInDisplayCutoutMode != mode) {
                    lp.layoutInDisplayCutoutMode = mode
                    w.attributes = lp
                    Log.i("GuestHost", "display: the game's window is drawn under the notch")
                }
            }
        } catch (t: Throwable) { Log.w("GuestHost", "display: ignoring the notch failed: $t") }
        applySustained(a)
    }

    private fun applySustained(a: android.app.Activity) {
        try {
            if (!sustained(a)) return
            val pm = a.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isSustainedPerformanceModeSupported) {
                a.window.setSustainedPerformanceMode(true)
                Log.i("GuestHost", "performance: sustained performance mode on")
            } else Log.i("GuestHost", "performance: this phone has no sustained performance mode (render thread priority is still raised)")
        } catch (t: Throwable) { Log.w("GuestHost", "performance: sustained mode failed: $t") }
    }

    // Upscaler: the game renders smaller than the screen (the Resolution set here, or the game's own resolution option)
    // and the picture is upscaled to the screen: 0 = off, 1 = Snapdragon Game Super Resolution 1, 2 = AMD FSR 1 (EASU + RCAS).
    val upscalers = listOf("Off", "SGSR 1 (Snapdragon Game Super Resolution)", "FSR 1 (AMD FidelityFX, EASU + RCAS)")
    fun upscaler(ctx: Context): Int {
        val p = Prefs.get(ctx)
        return p.getInt("upscaler", if (p.getBoolean("sgsr", false)) 1 else 0).coerceIn(0, 2)
    }
    fun setUpscaler(ctx: Context, i: Int) { Prefs.get(ctx).edit().putInt("upscaler", i).commit() }

    // FSR 1's RCAS sharpening, shown as 0..100 % (100 % = sharpest). The filter itself takes "stops" of reduction: 0 stops is
    // the sharpest, 2 stops is very soft; AMD suggests about 0.2 as a default.
    fun rcasPercent(ctx: Context): Int = Prefs.get(ctx).getInt("rcas_pct", 90).coerceIn(0, 100)
    fun setRcasPercent(ctx: Context, v: Int) { Prefs.get(ctx).edit().putInt("rcas_pct", v.coerceIn(0, 100)).commit() }
    fun rcasStops(pct: Int): Float = (100 - pct.coerceIn(0, 100)) / 100f * 2f

    // Frame generation (Lossless Scaling's algorithm, needs the user's Lossless.dll). Default off.
    fun lsfg(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("lsfg", false)
    fun setLsfg(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("lsfg", on).commit() }

    // Lock the game's dynamic resolution (its scene size is kept at the largest step). Default off.
    // Frame generation quality: false = performance (lighter shaders), true = quality. Flow scale in steps of 0.05 (0..20).
    fun lsfgQuality(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("lsfg_quality", false)
    fun setLsfgQuality(ctx: Context, q: Boolean) { Prefs.get(ctx).edit().putBoolean("lsfg_quality", q).commit() }
    fun lsfgFlowSteps(ctx: Context): Int = Prefs.get(ctx).getInt("lsfg_flow", 10).coerceIn(0, 20)
    fun setLsfgFlowSteps(ctx: Context, s: Int) { Prefs.get(ctx).edit().putInt("lsfg_flow", s.coerceIn(0, 20)).commit() }

    fun drsLock(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("drs_lock", false)
    fun setDrsLock(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("drs_lock", on).commit() }

    // Stretch to fit (default on): a game picture whose ratio differs from the screen's is stretched over the whole screen.
    // Off: the ratio is kept and black bars fill the rest (the picture then goes through the shim's own full-size swapchain).
    fun stretch(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("stretch", true)
    fun setStretch(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("stretch", on).commit() }

    // Custom resolution: the game is told the screen is this size (window size queries, Vulkan surface size, touch
    // positions) so it renders smaller and the system scales the picture up. 0x0 = the screen's own size.
    fun resW(ctx: Context): Int = Prefs.get(ctx).getInt("res_w", 0)
    fun resH(ctx: Context): Int = Prefs.get(ctx).getInt("res_h", 0)
    fun setRes(ctx: Context, w: Int, h: Int) { Prefs.get(ctx).edit().putInt("res_w", w).putInt("res_h", h).commit() }

    // The screen size in landscape order (the game runs in landscape).
    fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val b = wm.maximumWindowMetrics.bounds
        return Pair(maxOf(b.width(), b.height()), minOf(b.width(), b.height()))
    }

    // Called in the game process before the game's libraries load.
    fun apply(ctx: Context) {
        try {
            val l = light(ctx)
            Os.setenv("LAS_LIGHT", if (l) "1" else "0", true)
            applyTurnipEnv(ctx)
            val rw = resW(ctx); val rh = resH(ctx)
            Os.setenv("LAS_RES_W", if (rw > 0 && rh > 0) rw.toString() else "0", true)
            Os.setenv("LAS_RES_H", if (rw > 0 && rh > 0) rh.toString() else "0", true)
            if (Lsfg.installed(ctx)) Os.setenv("LAS_LSFG_DLL", Lsfg.dll(ctx).path, true)
            Os.setenv("LAS_LSFG", if (lsfg(ctx) && Lsfg.installed(ctx)) "1" else "0", true)
            Os.setenv("LAS_LSFG_LIB", File(ctx.applicationInfo.nativeLibraryDir, "liblsfgcore.so").path, true)
            Os.setenv("LAS_LSFG_PERF", if (lsfgQuality(ctx)) "0" else "1", true)
            Os.setenv("LAS_LSFG_FLOW", "%.2f".format(java.util.Locale.US, lsfgFlowSteps(ctx) * 0.05), true)
            Os.setenv("LAS_DRS", if (drsLock(ctx)) "1" else "0", true)
            Os.setenv("LAS_DEPTHDIAG", if (depthDiag(ctx)) "1" else "0", true)
            val up = upscaler(ctx)
            Os.setenv("LAS_SGSR", if (up == 1) "1" else "0", true)
            Os.setenv("LAS_FSR", if (up == 2) "1" else "0", true)
            Os.setenv("LAS_KEEP", if (stretch(ctx)) "0" else "1", true)
            Os.setenv("LAS_RCAS", rcasStops(rcasPercent(ctx)).toString(), true)
            Os.setenv("LAS_CAS", "0", true)
            if (angle(ctx)) {
                Os.setenv("ANGLE_DEFAULT_PLATFORM", "vulkan", true)
                Os.setenv("LAS_ANGLE", "1", true)
            } else Os.setenv("LAS_ANGLE", "0", true)
            PerfOverlay.prepare(ctx)
            Os.setenv("LAS_FSR_HALF", if (fsrHalf(ctx)) "1" else "0", true)
            val pk = Prefs.scope
            if (pcache(ctx) && pk != null) {
                val f = Caches.file(ctx, pk); f.parentFile?.mkdirs()
                Os.setenv("LAS_PCACHE", "1", true); Os.setenv("LAS_PCACHE_FILE", f.path, true)
            } else Os.setenv("LAS_PCACHE", "0", true)
            Os.setenv("LAS_PRIO", if (sustained(ctx)) "1" else "0", true)
            Epi.apply(ctx)
            Log.i("GuestHost", "tweaks: light mode ${if (l) "on" else "off"}; depth diagnostic ${if (depthDiag(ctx)) "on" else "off"}; resolution ${if (rw > 0 && rh > 0) "${rw}x$rh" else "native"}; upscaler ${upscalers[upscaler(ctx)].substringBefore(" (")}${if (upscaler(ctx) == 2) " (RCAS ${rcasPercent(ctx)}%)" else ""}; ${if (stretch(ctx)) "stretch to fit" else "keep ratio"}; ANGLE ${if (angle(ctx)) "on" else "off"}; pipeline cache ${if (pcache(ctx)) "on" else "off"}; sustained performance ${if (sustained(ctx)) "on" else "off"}; notch ${if (ignoreNotch(ctx)) "ignored" else "kept"}; steady resolution ${if (drsLock(ctx)) "on" else "off"}; frame generation ${if (lsfg(ctx) && Lsfg.installed(ctx)) "on" else "off"} (${if (lsfgQuality(ctx)) "quality" else "performance"}, flow ${"%.2f".format(java.util.Locale.US, lsfgFlowSteps(ctx) * 0.05)})")
        } catch (t: Throwable) {
            Log.w("GuestHost", "tweaks failed: $t")
        }
    }
}

// The saved pipeline caches (files/pcache/<game>/<driver>.bin) and the app's temporary files, for the Driver page.
object Caches {
    fun root(ctx: Context) = File(ctx.filesDir, "pcache")
    fun file(ctx: Context, pkg: String): File {
        val key = Drivers.selectedId(ctx).ifEmpty { "bundled" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(File(root(ctx), pkg), "$key.bin")
    }
    private fun size(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { size(it) } ?: 0L)
    fun gameSize(ctx: Context, pkg: String): Long = size(File(root(ctx), pkg))
    fun games(ctx: Context): List<String> = root(ctx).listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    fun totalSize(ctx: Context): Long = size(root(ctx))
    fun clear(ctx: Context, pkg: String?) { if (pkg == null) root(ctx).deleteRecursively() else File(root(ctx), pkg).deleteRecursively() }
    fun tempSize(ctx: Context): Long = size(ctx.cacheDir)
    fun clearTemp(ctx: Context) { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
    fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.0f KB".format(java.util.Locale.US, b / 1024.0)
        else -> "%.1f MB".format(java.util.Locale.US, b / 1048576.0)
    }
}
