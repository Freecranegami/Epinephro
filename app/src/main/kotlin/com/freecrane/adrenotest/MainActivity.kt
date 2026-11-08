package com.freecrane.adrenotest

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.zip.ZipFile

object Native {
    init { System.loadLibrary("probe") }

    external fun probe(
        tmpDir: String, hookDir: String,
        driverDir: String, driverName: String,
        custom: Boolean
    ): String

    external fun render(
        surface: Surface,
        tmpDir: String, hookDir: String,
        driverDir: String, driverName: String,
        custom: Boolean
    ): String

    external fun bindShim(): String
    external fun threadProbe(names: String)
    external fun installFindClassHook(loader: ClassLoader): String
    external fun snapshot(tag: String)
    external fun installFileHooks(pkg: String, extDir: String)
    // virtual gamepad: made-up input events for native games (handed out through the hooked AInputQueue_getEvent)
    external fun padKey(action: Int, code: Int, device: Int): Int
    external fun padAxes(device: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, hx: Float, hy: Float): Int
    external fun padStatus(): Int

    external fun openCustom(
        tmpDir: String, hookDir: String,
        driverDir: String, driverName: String
    ): String
}

// A library that links libvulkan.so like a game would.
object Guest {
    fun load() { System.loadLibrary("guesttest") }
    external fun vulkanInfo(): String
}

class MainActivity : Activity() {

    private val pickRequest = 1001
    private val driverRequest = 1002
    private val lsfgRequest = 1003
    private val iconRequest = 1004
    private var iconPkg: String? = null

    private var surface: Surface? = null
    private lateinit var outView: TextView

    @Volatile
    private var busy = false
    private var customOpened = false
    private var guestLoaded = false

    private fun runProbe(custom: Boolean, out: TextView) {
        if (busy) { out.text = "Busy, wait for the current task to finish"; return }
        val p = Util.paths(this, custom)
        if (custom) customOpened = true
        out.text = Native.probe(p[0], p[1], p[2], p[3], custom)
    }

    private fun runRender(custom: Boolean, out: TextView) {
        if (busy) { out.text = "Busy, wait for the current task to finish"; return }
        val s = surface
        if (s == null) {
            out.text = "No surface yet"
            return
        }
        val p = Util.paths(this, custom)
        if (custom) customOpened = true
        busy = true
        out.text = "Rendering..."
        Thread {
            val result = Native.render(s, p[0], p[1], p[2], p[3], custom)
            runOnUiThread {
                out.text = result
                busy = false
            }
        }.start()
    }

    private fun linkedTest(custom: Boolean, out: TextView) {
        if (busy) { out.text = "Busy, wait for the current task to finish"; return }
        if (guestLoaded) {
            out.text = "A linked test already ran in this launch.\n" +
                "Fully close and reopen the app to run another one."
            return
        }
        if (!custom && customOpened) {
            out.text = "The custom driver was already opened in this launch, " +
                "so this control would be misleading.\n" +
                "Fully close and reopen the app, then press Linked: system first."
            return
        }
        if (custom) {
            val p = Util.paths(this, true)
            Native.probe(p[0], p[1], p[2], p[3], true)   // opens the custom driver
            customOpened = true
        }
        Guest.load()
        guestLoaded = true
        val label = if (custom) "[custom driver opened first]" else "[system only]"
        out.text = label + "\n" + Guest.vulkanInfo()
    }

    private fun linkedShim(out: TextView) {
        if (busy) { out.text = "Busy, wait for the current task to finish"; return }
        if (guestLoaded) {
            out.text = "A linked test already ran in this launch.\n" +
                "Fully close and reopen the app to run another one."
            return
        }
        try {
            val p = Util.paths(this, true)
            Native.probe(p[0], p[1], p[2], p[3], true)   // opens the custom driver
            customOpened = true
            val bound = Native.bindShim()

            val src = File(applicationInfo.nativeLibraryDir, "libguesttest.so")
            val dir = File(filesDir, "guestlibs").apply { mkdirs() }
            val dst = File(dir, "libguesttest.so")
            val patched = Util.patchVulkanName(src, dst)

            System.load(dst.absolutePath)
            guestLoaded = true
            out.text = "[shim]\n$bound\nrenamed $patched reference(s)\n" + Guest.vulkanInfo()
        } catch (e: Throwable) {
            out.text = "Shim test failed: $e"
        }
    }

    // ---------- guest import (one APK, several APKs, or a bundle) ----------

    private fun pickApk() {
        if (busy) { outView.text = "Busy, wait for the current task to finish"; return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, pickRequest)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == iconRequest) {
            val u = if (resultCode == RESULT_OK) data?.data else null
            val pkg = iconPkg
            if (u != null && pkg != null) {
                val ok = GameMeta.setIconFrom(this, pkg, u)
                outView.text = if (ok) "Icon changed." else "Couldn't read that picture."
                refreshPage()
            }
            return
        }
        if (requestCode == lsfgRequest) {
            val u = if (resultCode == RESULT_OK) data?.data else null
            if (u != null) {
                outView.text = "Importing Lossless.dll and checking it..."
                Thread {
                    val msg = Lsfg.import(this, u)
                    runOnUiThread { outView.text = msg }
                }.start()
            }
            return
        }
        if (requestCode == driverRequest) {
            val u = if (resultCode == RESULT_OK) data?.data else null
            if (u != null) {
                outView.text = "Importing the driver..."
                Thread {
                    val msg = Drivers.import(this, u)
                    runOnUiThread { outView.text = msg }
                }.start()
            }
            return
        }
        if (requestCode != pickRequest || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
        } else {
            val one = data.data
            if (one != null) uris.add(one)
        }
        if (uris.isNotEmpty()) importFiles(uris)
    }

    private fun importFiles(uris: List<Uri>) {
        busy = true
        outView.text = "Importing... large games can take several minutes."
        Thread {
            val msg = try {
                Importer.importAll(this, uris) { p -> runOnUiThread { outView.text = p } }
            } catch (e: Throwable) {
                "Import failed: $e"
            }
            runOnUiThread {
                outView.text = msg
                busy = false
                refreshPage()
            }
        }.start()
    }

    private fun showImported(out: TextView) {
        val dirs = File(filesDir, "guests").listFiles { f -> f.isDirectory }
        if (dirs == null || dirs.isEmpty()) {
            out.text = "Nothing imported yet."
            return
        }
        out.text = dirs.joinToString("\n\n") { d ->
            val r = File(d, "report.txt")
            if (r.exists()) r.readText().trim() else d.name
        }
    }

    // ---------- launching a game ----------

    private fun chooseMode(pkg: String) {
        val dir = File(filesDir, "guests/$pkg")
        val adapter = try {
            File(dir, "adapter.txt").readText().trim()
        } catch (e: Exception) {
            "unity"
        }
        if (adapter == "unknown") {
            outView.text = "Looking at the game's files to find its launcher..."
            busy = true
            Thread {
                val found = try {
                    Importer.reanalyze(this, pkg)
                } catch (e: Throwable) {
                    "unknown"
                }
                runOnUiThread {
                    busy = false
                    if (found == "unknown") {
                        outView.text = "This game has no supported launcher yet " +
                            "(no Unity, and no NativeActivity found in its settings or code)."
                    } else {
                        outView.text = "Found its launcher: $found"
                        chooseMode(pkg)
                    }
                }
            }.start()
            return
        }
        if (adapter == "native") {
            // games imported before launcher detection existed: look again once
            if (GuestRuntime.nativeValue(dir, "activity") == null && GuestRuntime.nativeValue(dir, "lib") == null) {
                try { Importer.reanalyze(this, pkg) } catch (e: Throwable) { }
            }
            val real = GuestRuntime.nativeActivity(dir)
            if (real == null || Build.VERSION.SDK_INT < 29) {
                startLegacyNative(pkg)
                return
            }
            startReal(pkg, real)
            return
        }
        val modes = arrayOf(
            "1. Vulkan (forced)",
            "2. Auto graphics (Unity chooses)")
        AlertDialog.Builder(this)
            .setTitle("Launch mode")
            .setItems(modes) { _, which ->
                val i = Intent(this, GuestActivity::class.java)
                i.putExtra("pkg", pkg)
                if (which == 0) i.putExtra("unity", "-force-vulkan")
                startActivity(i)
            }
            .show()
    }

    private fun startLegacyNative(pkg: String) {
        val i = Intent(this, GuestNativeActivity::class.java)
        i.putExtra("pkg", pkg)
        startActivity(i)
    }

    // Starts the game's own activity class through the proxy. The game process is stopped
    // first so every launch begins clean (drivers and game libraries load once per process).
    private fun startReal(pkg: String, activity: String) {
        outView.text = "Starting $activity ..."
        Util.killGuestProcess(this)
        Handler(Looper.getMainLooper()).postDelayed({
            val i = Intent(this, GuestProxyActivity::class.java)
            i.putExtra(GuestRuntime.EXTRA_PKG, pkg)
            i.putExtra(GuestRuntime.EXTRA_ACTIVITY, activity)
            startActivity(i)
        }, 600)
    }

    // Used by the build script: adb shell am start -n <app>/.MainActivity --es launch <pkg> [--es mode legacy]
    private fun autoLaunch(pkg: String, mode: String?) {
        Log.i("GuestHost", "auto launch requested: $pkg mode=${mode ?: "real"}")
        val dir = File(filesDir, "guests/$pkg")
        if (!File(dir, "base.apk").exists()) {
            outView.text = "Auto launch: game not imported: $pkg"
            Log.e("GuestHost", "auto launch: game not imported: $pkg")
            return
        }
        Thread {
            var act = GuestRuntime.nativeActivity(dir)
            if (act == null) {
                try { Importer.reanalyze(this, pkg) } catch (t: Throwable) { }
                act = GuestRuntime.nativeActivity(dir)
            }
            val found = act
            runOnUiThread {
                if (mode == "legacy") {
                    startLegacyNative(pkg)
                } else if (found != null && Build.VERSION.SDK_INT >= 29) {
                    startReal(pkg, found)
                } else {
                    outView.text = "Auto launch: no game activity recorded for $pkg"
                    Log.e("GuestHost", "auto launch: no game activity recorded for $pkg")
                }
            }
        }.start()
    }

    // ---------- log viewer ----------

    private fun readLog(lines: Int): String = try {
        val p = ProcessBuilder("logcat", "-d", "-t", lines.toString())
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
    } catch (e: Throwable) {
        "Can't read the log: $e"
    }

    private fun showLog() {
        outView.text = "Reading log..."
        Thread {
            val text = readLog(250)
            runOnUiThread { outView.text = text }
        }.start()
    }

    private fun copyLog() {
        Thread {
            val text = readLog(700)
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", text))
                outView.text = "Copied the last 700 log lines to the clipboard."
            }
        }.start()
    }

    private fun chooseGpu(out: TextView) {
        val labels = GpuSpoof.presets.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("GPU the game sees")
            .setSingleChoiceItems(labels, GpuSpoof.index(this)) { d, which ->
                GpuSpoof.set(this, which)
                out.text = "GPU spoof: ${GpuSpoof.presets[which].label}\nApplies the next time the game starts."
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseDevice(out: TextView) {
        val labels = GpuSpoof.devices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Phone the game sees (also sets GPU + CPU)")
            .setSingleChoiceItems(labels, GpuSpoof.deviceIndex(this)) { d, which ->
                GpuSpoof.setDevice(this, which)
                out.text = "Device spoof: ${GpuSpoof.devices[which].label}\nGPU: ${GpuSpoof.presets[GpuSpoof.index(this)].label}\n" +
                    "CPU: ${GpuSpoof.cpus[GpuSpoof.cpuIndex(this)].label}\nApplies the next time the game starts."
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Vulkan driver choice ----------

    private fun chooseDriver(out: TextView) {
        val drivers = Drivers.list(this)
        if (drivers.isEmpty()) { out.text = "No driver found."; return }
        var sel = drivers.indexOfFirst { it.id == Drivers.selectedId(this) }.coerceAtLeast(0)
        val labels = drivers.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vulkan driver for the game")
            .setSingleChoiceItems(labels, sel) { _, which -> sel = which }
            .setPositiveButton("Use") { _, _ ->
                val d = drivers[sel]
                Drivers.select(this, d.id)
                out.text = "Driver: ${d.label}\nApplies the next time the game starts. " +
                    "If the game then fails to start, pick the bundled driver again."
            }
            .setNegativeButton("Import...") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, driverRequest)
            }
            .setNeutralButton("Delete") { _, _ ->
                val d = drivers[sel]
                out.text = if (d.bundled) "The bundled driver can't be deleted."
                           else if (Drivers.delete(this, d.id)) "Deleted ${d.name}." else "Couldn't delete ${d.name}."
            }
            .show()
    }

    private fun toggleFocusGuard(out: TextView) {
        val on = !FocusGuard.enabled(this)
        FocusGuard.set(this, on)
        out.text = if (on) "Focus guard ON: the game keeps running (and is not told it lost focus) when the control centre or notification shade opens. Applies the next time the game starts."
                   else "Focus guard OFF: the game is told when it loses focus. Applies the next time the game starts."
    }

    private fun toggleLight(out: TextView) {
        val on = !Tweaks.light(this)
        Tweaks.setLight(this, on)
        out.text = if (on) "Light mode ON: diagnostics are off and the shim stays out of the game's way (the Guest log will have little in it). Applies the next time the game starts."
                   else "Light mode OFF: full diagnostics and Guest log, slightly more CPU use. Applies the next time the game starts."
    }

    private fun chooseResolution(out: TextView) {
        val (sw, sh) = Tweaks.screenSize(this)
        val pcts = intArrayOf(100, 90, 80, 75, 66, 50)
        fun sized(p: Int) = Pair((sw * p / 100) / 2 * 2, (sh * p / 100) / 2 * 2)
        val labels = (pcts.map { p -> val (w, h) = sized(p); if (p == 100) "Native ($sw x $sh)" else "$p% ($w x $h)" } + "Custom...").toTypedArray()
        val cw = Tweaks.resW(this); val ch = Tweaks.resH(this)
        val cur = if (cw <= 0) 0 else pcts.indices.firstOrNull { sized(pcts[it]) == Pair(cw, ch) } ?: pcts.size
        AlertDialog.Builder(this)
            .setTitle("Game resolution (screen is $sw x $sh)")
            .setSingleChoiceItems(labels, cur) { d, which ->
                d.dismiss()
                if (which < pcts.size) {
                    val (w, h) = if (pcts[which] == 100) Pair(0, 0) else sized(pcts[which])
                    Tweaks.setRes(this, w, h)
                    out.text = (if (w == 0) "Resolution: native." else "Resolution: $w x $h, scaled up to the screen (the game is told the screen is that size).") +
                        " Applies the next time the game starts. If the game misbehaves, set it back to Native."
                } else customResolution(out, sw, sh)
            }
            .show()
    }

    private fun customResolution(out: TextView, sw: Int, sh: Int) {
        val wBox = android.widget.EditText(this).apply { hint = "width"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Tweaks.resW(this@MainActivity).takeIf { it > 0 }?.toString() ?: "") }
        val hBox = android.widget.EditText(this).apply { hint = "height"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Tweaks.resH(this@MainActivity).takeIf { it > 0 }?.toString() ?: "") }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0); addView(wBox); addView(hBox) }
        AlertDialog.Builder(this)
            .setTitle("Custom resolution (landscape, e.g. 1280 x 576)")
            .setView(box)
            .setPositiveButton("Set") { _, _ ->
                val w = wBox.text.toString().toIntOrNull() ?: 0
                val h = hBox.text.toString().toIntOrNull() ?: 0
                if (w < 320 || h < 180 || w > sw * 2 || h > sh * 2) { out.text = "Not set: width at least 320, height at least 180, and no more than twice the screen." }
                else {
                    Tweaks.setRes(this, w, h)
                    out.text = "Resolution: $w x $h, scaled to the screen. Applies the next time the game starts. If the game misbehaves, set it back to Native."
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseUpscaler(out: TextView) {
        val labels = Tweaks.upscalers.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Upscaler")
            .setSingleChoiceItems(labels, Tweaks.upscaler(this)) { d, which ->
                Tweaks.setUpscaler(this, which)
                d.dismiss()
                out.text = if (which == 0) "Upscaler OFF. Applies the next time the game starts."
                else "Upscaler: ${Tweaks.upscalers[which]}. Whenever the game renders smaller than the screen (the Resolution you set here, " +
                    "or the game's own resolution option) the picture is upscaled to the screen. " +
                    (if (Tweaks.resW(this) <= 0) "With Resolution on Native it only works if the game itself is set below the screen size. " else "") +
                    "Applies the next time the game starts. If the screen goes black, turn the upscaler off."
            }
            .show()
    }

    private fun chooseRcas(out: TextView) {
        val bar = android.widget.SeekBar(this).apply { max = 100; progress = Tweaks.rcasPercent(this@MainActivity) }
        val label = TextView(this).apply { setPadding(48, 32, 48, 0) }
        fun show(p: Int) { label.text = "RCAS sharpness: $p %  (${"%.2f".format(Tweaks.rcasStops(p))} stops; 100 % is the sharpest; 0 % skips the sharpening pass, which is faster)" }
        show(bar.progress)
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { show(p) }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 0); addView(label); addView(bar) }
        AlertDialog.Builder(this)
            .setTitle("FSR 1 sharpening (RCAS)")
            .setView(box)
            .setPositiveButton("Set") { _, _ ->
                Tweaks.setRcasPercent(this, bar.progress)
                out.text = "RCAS sharpness ${bar.progress} %. Only used when the upscaler is FSR 1. Applies the next time the game starts."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleStretch(out: TextView) {
        val on = !Tweaks.stretch(this)
        Tweaks.setStretch(this, on)
        out.text = if (on) "Stretch to fit ON: a picture with a different ratio than the screen is stretched over the whole screen. Applies the next time the game starts."
                   else "Stretch to fit OFF: the picture keeps its ratio, with black bars at the sides or top and bottom (for a Resolution with another ratio, or a game resolution of another ratio). " +
                        "Applies the next time the game starts."
    }

    private fun toggleFxaa(out: TextView) {
        val on = !Tweaks.fxaa(this)
        Tweaks.setFxaa(this, on)
        out.text = if (on) "FXAA anti-aliasing ON: smooths jagged edges (before the upscale when SGSR is on, and before CAS). Applies the next time the game starts. If the screen goes black, turn it off."
                   else "FXAA anti-aliasing OFF. Applies the next time the game starts."
    }

    private fun importLossless(out: TextView) {
        out.text = "Pick Lossless.dll from your copy of Lossless Scaling (Steam: steamapps/common/Lossless Scaling/Lossless.dll). " +
            "It is only read on this phone to get the frame generation shaders; the app does not include it."
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, lsfgRequest)
    }

    private fun checkLossless(out: TextView) {
        out.text = "Checking Lossless.dll..."
        Thread {
            val msg = Lsfg.check(this)
            runOnUiThread { out.text = msg }
        }.start()
    }

    private fun toggleLsfg(out: TextView) {
        if (!Lsfg.installed(this)) { out.text = "Import Lossless.dll first (the button above)."; return }
        val on = !Tweaks.lsfg(this)
        Tweaks.setLsfg(this, on)
        out.text = if (on) "Frame generation ON (2x): an in-between frame is made for every game frame, using the shaders from your Lossless.dll. " +
                "It costs GPU time, so it may help less when the game is already GPU-bound, and it adds about one frame of delay. " +
                "Applies the next time the game starts. If the picture breaks or the game stalls, turn it off and send the Guest log."
                   else "Frame generation OFF. Applies the next time the game starts."
    }

    private fun chooseLsfg(out: TextView) {
        val ctx = this
        val modes = arrayOf("Performance (lighter, faster)", "Quality (heavier, cleaner motion)")
        val group = android.widget.RadioGroup(this).apply { setPadding(48, 24, 48, 0) }
        modes.forEachIndexed { i, s -> group.addView(android.widget.RadioButton(ctx).apply { text = s; id = 100 + i }) }
        group.check(100 + (if (Tweaks.lsfgQuality(this)) 1 else 0))
        val bar = android.widget.SeekBar(this).apply { max = 20; progress = Tweaks.lsfgFlowSteps(ctx) }
        val label = TextView(this).apply { setPadding(48, 32, 48, 0) }
        fun show(p: Int) { label.text = "Flow scale: ${"%.2f".format(java.util.Locale.US, p * 0.05)}  (lower = faster but rougher motion; values below 0.25 run as 0.25)" }
        show(bar.progress)
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { show(p) }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 0); addView(group); addView(label); addView(bar) }
        AlertDialog.Builder(this)
            .setTitle("Frame generation settings")
            .setView(box)
            .setPositiveButton("Set") { _, _ ->
                Tweaks.setLsfgQuality(this, group.checkedRadioButtonId == 101)
                Tweaks.setLsfgFlowSteps(this, bar.progress)
                out.text = "Frame generation: ${if (Tweaks.lsfgQuality(this)) "quality" else "performance"} mode, flow scale ${"%.2f".format(java.util.Locale.US, bar.progress * 0.05)}. Applies the next time the game starts."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleDrs(out: TextView) {
        val on = !Tweaks.drsLock(this)
        Tweaks.setDrsLock(this, on)
        out.text = if (on) "Steady resolution ON: the game is made to think the GPU is fast, so its own dynamic resolution stays at the largest step and the picture stays sharp when you look around. The frame rate can drop in heavy scenes. Applies the next time the game starts."
                   else "Steady resolution OFF: the game changes its own resolution as it likes. Applies the next time the game starts."
    }

    private fun chooseCpu(out: TextView) {
        val labels = GpuSpoof.cpus.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("CPU the game sees")
            .setSingleChoiceItems(labels, GpuSpoof.cpuIndex(this)) { d, which ->
                GpuSpoof.setCpu(this, which)
                out.text = "CPU spoof: ${GpuSpoof.cpus[which].label}\nApplies the next time the game starts."
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- UI ----------
    // A home screen with the games, a side menu (three lines, top left) with the settings sections, and a settings page
    // per game (press and hold a game). Settings changed on a game's page belong to that game only (see Prefs).

    // Colours come from Theme (dark or light, see Theme.kt). cur is the colour pair of the section being built.
    private val cBg get() = Theme.bg
    private val cBar get() = Theme.inset
    private val cCard get() = Theme.card
    private val cText get() = Theme.text
    private val cSub get() = Theme.sub
    private var cur: Pair<Int, Int> = Theme.pairFor("games")
    private val cAccent get() = Theme.accentText(cur)
    private val density get() = resources.displayMetrics.density

    private lateinit var content: android.widget.FrameLayout
    private lateinit var titleView: TextView
    private lateinit var drawerPanel: LinearLayout
    private lateinit var scrim: android.view.View
    private lateinit var statusPanel: LinearLayout
    private var page = "games"          // games | graphics | framegen | device | tools | diag | game:<pkg>
    private var drawerOpen = false
    private var pageScroll: ScrollView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var watched: android.content.SharedPreferences? = null
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.removeCallbacks(refreshRunnable); ui.postDelayed(refreshRunnable, 150)
    }
    private val refreshRunnable = Runnable { refreshPage() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun roundBg(color: Int, r: Int = 14) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    // A section header: a small gradient hexagon, the title in the section's colour and a fading line. Every section
    // after it (its cards, switches, sliders) takes the same colour pair.
    private fun header(b: LinearLayout, text: String) {
        cur = Theme.pairFor(text)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(20), 0, dp(8)) }
        row.addView(android.view.View(this).apply { background = HexDrawable(cur.first, cur.second, 0, 0f) }, LinearLayout.LayoutParams(dp(16), dp(18)).apply { rightMargin = dp(8) })
        row.addView(label(text.uppercase(), 12f, cAccent, true).apply { letterSpacing = 0.1f })
        row.addView(android.view.View(this).apply { background = Theme.grad(Theme.alpha(cur.first, 0x70), Theme.alpha(cur.second, 0x00), 0f) },
            LinearLayout.LayoutParams(0, dp(2), 1f).apply { leftMargin = dp(10); rightMargin = dp(4) })
        b.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun card(b: LinearLayout, onClick: (() -> Unit)?): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = CardDrawable(cCard, cur.first, cur.second, dp(16).toFloat(), density)
            setPadding(dp(20), dp(14), dp(16), dp(14))
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
        }
        b.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        return c
    }

    private fun texts(title: String, sub: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 15f, cText))
        if (!sub.isNullOrEmpty()) addView(label(sub, 12f, cSub).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun action(b: LinearLayout, title: String, sub: String?, onClick: () -> Unit) {
        val c = card(b, onClick)
        c.addView(texts(title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(label("›", 24f, cAccent, true))
    }

    private fun toggle(b: LinearLayout, title: String, sub: String?, get: () -> Boolean, flip: () -> Unit) {
        val c = card(b, null)
        val sw = android.widget.Switch(this)
        Theme.styleSwitch(sw, cur, density)
        var guard = false
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, _ ->
            if (guard) return@setOnCheckedChangeListener
            flip()
            guard = true; sw.isChecked = get(); guard = false
        }
        c.setOnClickListener { sw.toggle() }
        c.isClickable = true
        c.addView(texts(title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(sw)
    }

    // ----- settings groups (the same code serves the defaults and a game's own page) -----

    private fun addGraphics(b: LinearLayout) {
        header(b, "Graphics")
        val rw = Tweaks.resW(this); val rh = Tweaks.resH(this)
        action(b, "Resolution", if (rw > 0 && rh > 0) "$rw x $rh" else "Native") { chooseResolution(outView) }
        action(b, "Upscaler (SGSR / FSR)", Tweaks.upscalers[Tweaks.upscaler(this)].substringBefore(" (")) { chooseUpscaler(outView) }
        action(b, "RCAS sharpness (FSR 1)", "${Tweaks.rcasPercent(this)} %") { chooseRcas(outView) }
        toggle(b, "FXAA", "Smooths jagged edges", { Tweaks.fxaa(this) }) { toggleFxaa(outView) }
        toggle(b, "Ignore notch", "Draws the game under the camera cut-out, so it uses the whole screen. Parts of the game's picture at the edge can be hidden by the notch.",
            { Tweaks.ignoreNotch(this) }) { Tweaks.setIgnoreNotch(this, !Tweaks.ignoreNotch(this)); outView.text = "Ignore notch ${if (Tweaks.ignoreNotch(this)) "ON" else "OFF"}. Applies the next time the game starts."; refreshPage() }
        toggle(b, "Stretch to fit", "Off keeps the picture's ratio with black bars", { Tweaks.stretch(this) }) { toggleStretch(outView) }
        toggle(b, "Steady resolution", "Stops the game's dynamic resolution from dropping", { Tweaks.drsLock(this) }) { toggleDrs(outView) }
    }

    private fun addFrameGen(b: LinearLayout, withDll: Boolean) {
        header(b, "Frame generation")
        toggle(b, "Frame generation (2x)", if (Lsfg.installed(this)) "Makes an in-between frame for every game frame" else "Import Lossless.dll first",
            { Tweaks.lsfg(this) }) { toggleLsfg(outView) }
        action(b, "Quality and flow scale",
            "${if (Tweaks.lsfgQuality(this)) "Quality" else "Performance"}, flow ${"%.2f".format(java.util.Locale.US, Tweaks.lsfgFlowSteps(this) * 0.05)}") { chooseLsfg(outView) }
        if (withDll) {
            action(b, "Import Lossless.dll", if (Lsfg.installed(this)) "Imported" else "Not imported") { importLossless(outView) }
            action(b, "Check Lossless.dll", "Tests that the shaders can be used on this phone") { checkLossless(outView) }
        }
    }

    // ----- EpiShaders: a switch for each effect, its own settings appear underneath while it is on -----

    private fun addEpi(b: LinearLayout) {
        header(b, "EpiShaders")
        toggle(b, "EpiShaders", "Master switch: off turns every effect off without forgetting your choices",
            { Epi.master(this) }) { Epi.setMaster(this, !Epi.master(this)); refreshPage() }
        b.addView(label("Effects run on the finished picture, after the upscaler. Each one costs some speed. Applies the next time the game starts. " +
            "If the screen goes black, turn the effect off." + (if (Epi.enabledCount(this) > 0 && !Epi.master(this)) " (Master switch is off, so none of the chosen effects run.)" else ""),
            12f, cSub).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        for (g in Epi.groups) {
            header(b, g.title)
            for (e in g.effects) {
                toggle(b, e.name, e.info, { Epi.on(this, e.id) }) { Epi.setOn(this, e.id, !Epi.on(this, e.id)); refreshPage() }
                if (Epi.on(this, e.id)) addEpiParams(b, e)
            }
        }
    }

    private fun addEpiParams(b: LinearLayout, e: Epi.Effect) {
        val ps = Epi.params(this, e.id)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(10))
            background = roundBg(cBar, 14).apply { setStroke(dp(1), Theme.alpha(cur.first, 0x55)) }
        }
        b.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8); leftMargin = dp(10) })
        if (ps == null) {
            box.addView(label("This effect's settings couldn't be read. It may still run with its defaults.", 12f, cSub))
            return
        }
        if (ps.isEmpty()) box.addView(label("No settings.", 12f, cSub))
        var lastCat = ""
        for (p in ps) {
            if (p.cat.isNotEmpty() && p.cat != lastCat) {
                lastCat = p.cat
                box.addView(label(p.cat, 12f, cAccent, true).apply { setPadding(0, dp(10), 0, dp(2)) })
            }
            val cur = Epi.value(this, e.id, p.name) ?: p.def
            val isInt = p.type == "int" || p.type == "uint"
            if (p.items.isNotEmpty() && p.rows == 1) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setPadding(0, dp(8), 0, dp(8)) }
                val idx = cur[0].toInt().coerceIn(0, p.items.size - 1)
                row.addView(label(p.label, 14f, cText))
                row.addView(label(p.items[idx] + "  ›", 12f, cSub))
                row.setOnClickListener {
                    AlertDialog.Builder(this).setTitle(p.label)
                        .setSingleChoiceItems(p.items.toTypedArray(), idx) { d, which ->
                            Epi.setValue(this, e.id, p.name, doubleArrayOf(which.toDouble())); d.dismiss(); refreshPage()
                        }.show()
                }
                box.addView(row)
            } else if (p.type == "bool" || p.ui == "checkbox") {
                val sw = android.widget.Switch(this).apply { text = p.label; setTextColor(cText); isChecked = cur[0] != 0.0; setPadding(0, dp(8), 0, dp(8)) }
                Theme.styleSwitch(sw, this@MainActivity.cur, density)
                sw.setOnCheckedChangeListener { _, on -> Epi.setValue(this, e.id, p.name, doubleArrayOf(if (on) 1.0 else 0.0)) }
                box.addView(sw)
            } else {
                val comps = p.rows.coerceIn(1, 4)
                val names = if (p.ui == "color") listOf("red", "green", "blue", "alpha") else listOf("x", "y", "z", "w")
                for (ci in 0 until comps) {
                    val mn = p.min ?: 0.0
                    val mx = p.max ?: (if (p.ui == "color") 1.0 else maxOf(1.0, (p.def.getOrElse(ci) { 1.0 }) * 3.0))
                    val st = p.step?.takeIf { it > 0.0 } ?: if (isInt) 1.0 else (mx - mn) / 200.0
                    val n = Math.round((mx - mn) / st).toInt().coerceIn(1, 2000)
                    val v0 = cur.getOrElse(ci) { cur.lastOrNull() ?: 0.0 }
                    fun fmt(v: Double) = if (isInt) v.toLong().toString() else "%.3g".format(java.util.Locale.US, v)
                    val title = p.label + (if (comps > 1) " (${names[ci]})" else "")
                    val head = label("$title: ${fmt(v0)}", 13f, cText).apply { setPadding(0, dp(8), 0, 0) }
                    val bar = android.widget.SeekBar(this).apply { max = n; progress = Math.round((v0 - mn) / st).toInt().coerceIn(0, n) }
                    Theme.styleSeek(bar, this@MainActivity.cur)
                    fun valueAt(pr: Int) = (mn + pr * st).coerceIn(mn, mx)
                    bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: android.widget.SeekBar?, pr: Int, user: Boolean) { head.text = "$title: ${fmt(valueAt(pr))}" }
                        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { }
                        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                            val a = (Epi.value(this@MainActivity, e.id, p.name) ?: p.def).copyOf(maxOf(comps, p.def.size))
                            a[ci] = valueAt(bar.progress)
                            Epi.setValue(this@MainActivity, e.id, p.name, a)
                        }
                    })
                    box.addView(head)
                    box.addView(bar)
                }
            }
            if (p.tip.isNotEmpty() && p.tip.length <= 110) box.addView(label(p.tip, 11f, cSub))
        }
        val reset = label("Reset this effect's settings", 13f, cAccent).apply { setPadding(0, dp(12), 0, dp(2)); isClickable = true }
        reset.setOnClickListener { Epi.resetEffect(this, e.id); refreshPage() }
        box.addView(reset)
    }

    // ----- virtual gamepad (an Xbox 360 controller on the screen) -----

    private fun addPad(b: LinearLayout) {
        header(b, "Virtual gamepad")
        toggle(b, "On-screen Xbox 360 controller", "The game sees a controller plugged in; the buttons are drawn over the game",
            { Pad.on(this) }) { Pad.setOn(this, !Pad.on(this)); outView.text = "Virtual gamepad ${if (Pad.on(this)) "ON" else "OFF"}. Applies the next time the game starts."; refreshPage() }
        action(b, "Edit layout", "Move, resize and hide the controls") {
            startActivity(Intent(this, PadEditorActivity::class.java).putExtra("pkg", Prefs.scope))
        }
        action(b, "Transparency", "${Pad.opacity(this)} % visible") { choosePadOpacity() }
        toggle(b, "Vibrate on press", "A tiny tick when a button is pressed", { Pad.haptics(this) }) { Pad.setHaptics(this, !Pad.haptics(this)); refreshPage() }
        action(b, "Reset layout", "Back to the standard Xbox layout") { Pad.resetLayout(this); outView.text = "Layout reset."; refreshPage() }
    }

    private fun choosePadOpacity() {
        val bar = android.widget.SeekBar(this).apply { max = 85; progress = Pad.opacity(this@MainActivity) - 15 }
        val label = TextView(this).apply { setPadding(dp(20), dp(16), dp(20), 0) }
        fun show(p: Int) { label.text = "${p + 15} % visible" }
        show(bar.progress)
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) { show(p) }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { }
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) }) }
        AlertDialog.Builder(this).setTitle("Controller transparency").setView(box)
            .setPositiveButton("Save") { _, _ -> Pad.setOpacity(this, bar.progress + 15); refreshPage() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addDevice(b: LinearLayout) {
        header(b, "Device")
        action(b, "GPU spoof", GpuSpoof.presets[GpuSpoof.index(this)].label) { chooseGpu(outView) }
        action(b, "CPU spoof", GpuSpoof.cpus[GpuSpoof.cpuIndex(this)].label) { chooseCpu(outView) }
        action(b, "Device spoof", GpuSpoof.devices[GpuSpoof.deviceIndex(this)].label) { chooseDevice(outView) }
        toggle(b, "Focus guard", "Keeps the game running when the notification shade opens", { FocusGuard.enabled(this) }) { toggleFocusGuard(outView) }
        toggle(b, "Light mode", "No diagnostics, best performance. Turn it OFF to record a Guest log for a bug report", { Tweaks.light(this) }) { toggleLight(outView) }
    }

    private fun addDriver(b: LinearLayout) {
        header(b, "Driver")
        val drv = try { Drivers.list(this).firstOrNull { it.id == Drivers.selectedId(this) }?.label } catch (t: Throwable) { null }
        action(b, "Vulkan driver", drv ?: "Bundled") { chooseDriver(outView) }
        toggle(b, "OpenGL ES via ANGLE", "For games that use OpenGL ES instead of Vulkan: translates it to Vulkan so it runs on the custom driver and the layer. Experimental. The first start afterwards rewrites the game's libraries.",
            { Tweaks.angle(this) }) { Tweaks.setAngle(this, !Tweaks.angle(this)); refreshPage() }
        header(b, "Performance")
        toggle(b, "Pipeline cache", "Keeps the shader work the driver has done between sessions, so areas stutter less the second time. Applies the next time the game starts.",
            { Tweaks.pcache(this) }) { Tweaks.setPcache(this, !Tweaks.pcache(this)); refreshPage() }
        toggle(b, "Sustained performance", "Asks the phone for steady speed instead of short bursts that then throttle (if it has the mode) and gives the render thread a little more priority. Applies the next time the game starts.",
            { Tweaks.sustained(this) }) { Tweaks.setSustained(this, !Tweaks.sustained(this)); refreshPage() }
        toggle(b, "FSR half precision", "Runs the FSR upscale in 16-bit maths, which is faster on Adreno GPUs. Turn it off if the picture shows odd colours or noise. Applies the next time the game starts.",
            { Tweaks.fsrHalf(this) }) { Tweaks.setFsrHalf(this, !Tweaks.fsrHalf(this)); outView.text = "FSR half precision ${if (Tweaks.fsrHalf(this)) "ON" else "OFF"}. Applies the next time the game starts."; refreshPage() }
        toggle(b, "Performance overlay", "A small card you can drag around the screen: frames per second, CPU temperature and battery (level and power use). Light: one update a second.",
            { PerfOverlay.enabled(this) }) { PerfOverlay.setEnabled(this, !PerfOverlay.enabled(this)); outView.text = "Performance overlay ${if (PerfOverlay.enabled(this)) "ON" else "OFF"}. Applies the next time the game starts."; refreshPage() }
        addCaches(b)
    }

    // Cache manager: what the saved pipeline caches take, and a way to delete them.
    private fun addCaches(b: LinearLayout) {
        header(b, "Cache")
        val scope = Prefs.scope
        if (scope != null) {
            val n = Caches.gameSize(this, scope)
            action(b, "Pipeline cache of this game", if (n > 0) Caches.fmt(n) + ". Tap to delete." else "Nothing saved yet") {
                if (n > 0) Caches.clear(this, scope).also { outView.text = "Pipeline cache deleted."; refreshPage() }
            }
        } else {
            val games = Caches.games(this)
            action(b, "Pipeline caches", Caches.fmt(Caches.totalSize(this)) + " in total" + if (games.isEmpty()) " (nothing saved yet)" else "") { }
            for (g in games) {
                action(b, GameMeta.name(this, g), Caches.fmt(Caches.gameSize(this, g)) + ". Tap to delete.") {
                    AlertDialog.Builder(this).setTitle("Delete this cache?").setMessage("${GameMeta.name(this, g)} will build its shaders again the next time you play. Nothing else is lost.")
                        .setPositiveButton("Delete") { _, _ -> Caches.clear(this, g); outView.text = "Pipeline cache deleted."; refreshPage() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            if (games.isNotEmpty()) action(b, "Delete all pipeline caches", null) {
                AlertDialog.Builder(this).setTitle("Delete all pipeline caches?").setMessage("Games will build their shaders again the next time you play. Nothing else is lost.")
                    .setPositiveButton("Delete") { _, _ -> Caches.clear(this, null); outView.text = "Pipeline caches deleted."; refreshPage() }
                    .setNegativeButton("Cancel", null).show()
            }
            action(b, "Temporary files", Caches.fmt(Caches.tempSize(this)) + ". Tap to delete.") {
                Caches.clearTemp(this); outView.text = "Temporary files deleted."; refreshPage()
            }
        }
    }

    // ----- pages -----

    private fun gamesPage(box: LinearLayout) {
        val games = GameMeta.games(this)
        val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val cols = maxOf(2, (wDp / 120f).toInt())
        val grid = android.widget.GridLayout(this).apply { columnCount = cols }
        fun lp() = android.widget.GridLayout.LayoutParams(
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
            width = 0; setMargins(dp(6), dp(6), dp(6), dp(10)); setGravity(android.view.Gravity.FILL_HORIZONTAL)
        }
        for (pkg in games) {
            val tile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL; isClickable = true }
            val img = android.widget.ImageView(this).apply {
                setImageBitmap(GameMeta.icon(this@MainActivity, pkg))
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(19).toFloat()) }
                }
                clipToOutline = true
            }
            // every game icon sits in its own gradient frame
            val gp = Theme.pairs[(pkg.hashCode() and 0x7fffffff) % Theme.pairs.size]
            val ring = android.widget.FrameLayout(this).apply {
                background = Theme.grad(gp.first, gp.second, dp(22).toFloat(), false); setPadding(dp(3), dp(3), dp(3), dp(3))
                addView(img, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            tile.addView(ring, LinearLayout.LayoutParams(dp(88), dp(88)))
            tile.addView(label(GameMeta.name(this, pkg), 13f, cText).apply {
                gravity = android.view.Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tile.setOnClickListener { playGame(pkg) }
            tile.setOnLongClickListener {
                try { tile.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (t: Throwable) { }
                showPage("game:$pkg"); true
            }
            grid.addView(tile, lp())
        }
        val add = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL; isClickable = true }
        add.addView(label("+", 36f, cAccent, true).apply {
            gravity = android.view.Gravity.CENTER
            background = HexDrawable(Theme.MAGENTA, Theme.PINK, 0, 2.5f * density, false)
        }, LinearLayout.LayoutParams(dp(88), dp(88)))
        add.addView(label("Add game", 13f, cSub).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        add.setOnClickListener { pickApk() }
        grid.addView(add, lp())
        if (games.isEmpty()) {
            box.addView(label("No games yet. Tap + to import one (its APK files and OBB).", 14f, cSub).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        } else {
            box.addView(label("Tap a game to play. Press and hold for its settings.", 12f, cSub).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        }
        box.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun gameSettingsPage(box: LinearLayout, pkg: String) {
        val head = card(box, null)
        head.addView(android.widget.ImageView(this).apply {
            setImageBitmap(GameMeta.icon(this@MainActivity, pkg))
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(14) })
        head.addView(texts(GameMeta.name(this, pkg), pkg), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val play = Button(this).apply { text = "Play"; setOnClickListener { playGame(pkg) } }
        Theme.pillButton(play, cur, density)
        head.addView(play)

        header(box, "Game")
        action(box, "Name", GameMeta.name(this, pkg)) { renameGame(pkg) }
        action(box, "Icon", if (GameMeta.hasCustomIcon(this, pkg)) "Custom picture" else "The game's own") { chooseIcon(pkg) }
        action(box, "Files", "Browse this game's data and OBB") { startActivity(Intent(this, FileManagerActivity::class.java).putExtra("pkg", pkg)) }
        action(box, "Add shortcut to the home screen", "Starts this game directly") { pinShortcut(pkg) }
        action(box, "Play with the old basic launcher", "Only if the normal start doesn't work") { startLegacyNative(pkg) }
        header(box, "This game's settings")
        box.addView(label("Changes here affect only this game. Anything you never change here follows the defaults from the menu.", 12f, cSub).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        addGraphics(box)
        addEpi(box)
        addPad(box)
        addFrameGen(box, false)
        addDevice(box)
        addDriver(box)
        action(box, "Reset this game's settings", "Go back to the defaults") {
            AlertDialog.Builder(this).setTitle("Reset settings?").setMessage("${GameMeta.name(this, pkg)} will follow the default settings again.")
                .setPositiveButton("Reset") { _, _ ->
                    Prefs.gameOnly(this, pkg).edit().clear().commit()
                    outView.text = "Settings reset. Applies the next time the game starts."
                    refreshPage()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun toolsPage(box: LinearLayout) {
        header(box, "Games")
        action(box, "Import game", "APK files and OBB") { pickApk() }
        action(box, "Imported list", "What was imported and how it was read") { showImported(outView) }
        action(box, "File manager", "Browse every game's files") { startActivity(Intent(this, FileManagerActivity::class.java)) }
        header(box, "Logs")
        action(box, "Guest log", "Copies the game's log: send this when something is wrong") { GuestLog.copy(this, outView) }
        action(box, "Show log", "The last lines of the phone's log, here") { showLog() }
        action(box, "Copy log", "The last 700 lines to the clipboard") { copyLog() }
    }

    private fun diagPage(box: LinearLayout) {
        header(box, "Tests")
        action(box, "Probe: system driver", null) { runProbe(false, outView) }
        action(box, "Probe: custom driver", null) { runProbe(true, outView) }
        action(box, "Render: system driver", null) { runRender(false, outView) }
        action(box, "Render: custom driver", null) { runRender(true, outView) }
        action(box, "Linked: system driver", null) { linkedTest(false, outView) }
        action(box, "Linked: custom driver", null) { linkedTest(true, outView) }
        action(box, "Linked: shim", null) { linkedShim(outView) }
        val sv = SurfaceView(this)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surface = h.surface }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { surface = null }
        })
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
    }

    private fun pageTitle(p: String): String = when {
        p == "games" -> "Epinephro"
        p == "graphics" -> "Graphics (defaults)"
        p == "framegen" -> "Frame generation (defaults)"
        p == "epi" -> "EpiShaders (defaults)"
        p == "pad" -> "Controls (defaults)"
        p == "device" -> "Device (defaults)"
        p == "driver" -> "Driver (defaults)"
        p == "tools" -> "Tools"
        p == "diag" -> "Diagnostics"
        p.startsWith("game:") -> GameMeta.name(this, p.removePrefix("game:")) + " settings"
        else -> "Epinephro"
    }

    private fun showPage(p: String) {
        page = p
        setScope(if (p.startsWith("game:")) p.removePrefix("game:") else null)
        closeDrawer()
        pageScroll = null
        buildPage()
    }

    private fun refreshPage() {
        ui.removeCallbacks(refreshRunnable)
        val y = pageScroll?.scrollY ?: 0
        buildPage()
        val sc = pageScroll ?: return
        // put the scroll position back before the first frame is drawn, so the page doesn't flash at the top
        sc.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sc.viewTreeObserver.removeOnPreDrawListener(this)
                sc.scrollTo(0, y)
                return true
            }
        })
    }

    private fun buildPage() {
        titleView.text = pageTitle(page)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(24)) }
        cur = pairForPage(page)
        if (::scrim.isInitialized) markDrawer()
        when {
            page == "games" -> gamesPage(box)
            page == "graphics" -> addGraphics(box)
            page == "framegen" -> addFrameGen(box, true)
            page == "epi" -> addEpi(box)
            page == "pad" -> addPad(box)
            page == "device" -> addDevice(box)
            page == "driver" -> addDriver(box)
            page == "tools" -> toolsPage(box)
            page == "diag" -> diagPage(box)
            page.startsWith("game:") -> gameSettingsPage(box, page.removePrefix("game:"))
        }
        val sc = ScrollView(this).apply { addView(box); isFillViewport = false }
        pageScroll = sc
        content.removeAllViews()
        content.addView(sc, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (page == "games") loadGameMeta()
    }

    // Settings are read and written through Prefs; while a game's page is open its package is the scope.
    private fun setScope(pkg: String?) {
        Prefs.scope = pkg
        try { watched?.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (t: Throwable) { }
        watched = Prefs.get(this)
        watched?.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private var metaLoading = false
    private fun loadGameMeta() {
        if (metaLoading) return
        val todo = GameMeta.games(this).filter { !File(filesDir, "guests/$it/meta_tried").exists() }
        if (todo.isEmpty()) return
        metaLoading = true
        Thread {
            for (p in todo) GameMeta.ensureDefaults(this, p)
            runOnUiThread { metaLoading = false; if (page == "games") refreshPage() }
        }.start()
    }

    // ----- game actions -----

    private fun playGame(pkg: String) {
        if (busy) { outView.text = "Busy, wait for the current task to finish"; return }
        chooseMode(pkg)
    }

    private fun renameGame(pkg: String) {
        val box = android.widget.EditText(this).apply { setText(GameMeta.name(this@MainActivity, pkg)); setSelectAllOnFocus(true); setSingleLine() }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle("Game name").setView(wrap)
            .setPositiveButton("Save") { _, _ -> GameMeta.setName(this, pkg, box.text.toString()); refreshPage() }
            .setNeutralButton("Original name") { _, _ -> GameMeta.setName(this, pkg, ""); refreshPage() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseIcon(pkg: String) {
        val items = if (GameMeta.hasCustomIcon(this, pkg)) arrayOf("Pick a picture...", "Use the game's own icon") else arrayOf("Pick a picture...")
        AlertDialog.Builder(this).setTitle("Icon").setItems(items) { _, which ->
            if (which == 0) {
                iconPkg = pkg
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, iconRequest)
            } else { GameMeta.clearIcon(this, pkg); refreshPage() }
        }.show()
    }

    private fun pinShortcut(pkg: String) {
        try {
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (sm == null || !sm.isRequestPinShortcutSupported) {
                outView.text = "This launcher can't add shortcuts from an app. Try a different home screen app, or add the shortcut from the launcher's own menu."
                return
            }
            val i = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("play", pkg)
            val name = GameMeta.name(this, pkg)
            val info = android.content.pm.ShortcutInfo.Builder(this, "game_$pkg")
                .setShortLabel(name).setLongLabel(name)
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(GameMeta.icon(this, pkg)))
                .setIntent(i).build()
            sm.requestPinShortcut(info, null)
            outView.text = "Asked the launcher to add a shortcut for $name. Confirm it on the prompt that appears."
        } catch (t: Throwable) {
            outView.text = "Couldn't add the shortcut: $t"
        }
    }

    // ----- side menu -----

    private fun openDrawer() {
        drawerOpen = true
        scrim.visibility = android.view.View.VISIBLE
        scrim.animate().alpha(1f).setDuration(160).start()
        (drawerSlide ?: drawerPanel).animate().translationX(0f).setDuration(200).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(160).withEndAction { if (!drawerOpen) scrim.visibility = android.view.View.GONE }.start()
        (drawerSlide ?: drawerPanel).animate().translationX(-dp(290).toFloat()).setDuration(200).start()
    }

    private fun pairForPage(p: String): Pair<Int, Int> =
        Theme.pairFor(when (p) { "framegen" -> "frame generation"; "pad" -> "controls"; "tools" -> "logs"; "diag" -> "device"; "epi" -> "epishaders"; else -> p.takeWhile { it != ':' } })

    private val drawerItems = ArrayList<Pair<String, TextView>>()

    // a menu entry: a gradient hexagon in the colour of the section, highlighted while its page is open
    private fun drawerItem(text: String, p: String): TextView {
        val pr = pairForPage(p)
        val tv = label(text, 16f, cText)
        tv.setPadding(dp(18), dp(13), dp(16), dp(13)); tv.isClickable = true
        tv.compoundDrawablePadding = dp(14)
        tv.setCompoundDrawables(HexDrawable(pr.first, pr.second, 0, 0f).apply { setBounds(0, 0, dp(16), dp(18)) }, null, null, null)
        tv.setOnClickListener { showPage(p) }
        drawerItems.add(p to tv)
        return tv
    }

    private fun markDrawer() {
        for ((p, tv) in drawerItems) {
            val pr = pairForPage(p)
            tv.background = if (p == page) Theme.grad(Theme.alpha(pr.first, if (Theme.light) 0x36 else 0x4A), Theme.alpha(pr.second, 0x00), dp(14).toFloat()) else null
        }
    }

    private fun drawerHeader(text: String): TextView = label(text.uppercase(), 11f, cSub, true).apply {
        setPadding(dp(22), dp(18), dp(16), dp(4)); letterSpacing = 0.1f
    }

    // The light mode switch: one global setting (never per game). It rebuilds the screen in the other colours.
    private fun lightRow(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            background = CardDrawable(cCard, Theme.GREEN, Theme.CYAN, dp(16).toFloat(), density)
            setPadding(dp(20), dp(12), dp(14), dp(12)); isClickable = true
        }
        val sw = android.widget.Switch(this)
        Theme.styleSwitch(sw, Theme.GREEN to Theme.CYAN, density)
        sw.isChecked = Theme.light
        sw.setOnCheckedChangeListener { _, on -> Theme.setLight(this, on); recreate() }
        row.setOnClickListener { sw.toggle() }
        row.addView(texts("Light mode", "Brighter colours, for the whole app"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(14), dp(12), dp(6)) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page)
    }

    override fun onBackPressed() {
        if (drawerOpen) { closeDrawer(); return }
        if (page != "games") { showPage("games"); return }
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val play = intent?.getStringExtra("play")
        if (play != null) { showPage("games"); playGame(play) }
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) refreshPage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.load(this)
        setTheme(Theme.styleRes())          // dialogs, edit fields and the like follow the dark or light mode
        super.onCreate(savedInstanceState)
        try { actionBar?.hide() } catch (t: Throwable) { }
        window.statusBarColor = Theme.barA
        window.navigationBarColor = cBg
        // white status bar icons on the coloured bar; dark navigation icons in light mode
        window.decorView.systemUiVisibility = if (Theme.light) android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0

        val out = TextView(this).apply { textSize = 12f; setTextColor(cText); setTextIsSelectable(true) }
        outView = out

        // top bar: gradient with the logo and a few translucent hexagons
        titleView = label("Epinephro", 19f, android.graphics.Color.WHITE, true)
        val burger = label("☰", 26f, android.graphics.Color.WHITE).apply { setPadding(dp(18), 0, dp(14), 0); gravity = android.view.Gravity.CENTER; isClickable = true; setOnClickListener { openDrawer() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            background = BarDrawable(Theme.barA, Theme.barB, density)
            elevation = dp(4).toFloat()
            addView(burger, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
            addView(android.view.View(this@MainActivity).apply { background = LogoDrawable(android.graphics.Color.WHITE) }, LinearLayout.LayoutParams(dp(38), dp(28)).apply { rightMargin = dp(10) })
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        content = android.widget.FrameLayout(this)

        // output panel (shown when there is something to say)
        val msgPair = Theme.ORANGE to Theme.PINK
        val clear = label("✕", 16f, cSub).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); isClickable = true; setOnClickListener { out.text = "" } }
        val statusHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(label("MESSAGE", 11f, Theme.accentText(msgPair), true).apply { setPadding(dp(18), dp(4), 0, 0); letterSpacing = 0.1f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear)
        }
        val outScroll = ScrollView(this).apply { addView(out); setPadding(dp(18), 0, dp(14), dp(10)) }
        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE
            background = CardDrawable(cCard, msgPair.first, msgPair.second, dp(16).toFloat(), density)
            addView(statusHead)
            addView(outScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        }
        out.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                statusPanel.visibility = if (s.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        })

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(statusPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(10), dp(4), dp(10), dp(10)) })
        }

        // side menu: a gradient header with the logo, the light mode switch, then the sections
        scrim = android.view.View(this).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f; visibility = android.view.View.GONE; setOnClickListener { closeDrawer() } }
        val menuHead = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = BarDrawable(Theme.barA, Theme.barB, density)
            setPadding(dp(22), dp(30), dp(20), dp(22))
            addView(android.view.View(this@MainActivity).apply { background = LogoDrawable(android.graphics.Color.WHITE) }, LinearLayout.LayoutParams(dp(84), dp(58)))
            addView(label("Epinephro", 26f, android.graphics.Color.WHITE, true).apply { setPadding(0, dp(10), 0, dp(2)) })
            addView(label("Defaults below apply to every game unless you change them in the game's own settings (press and hold a game).", 12f, 0xE6FFFFFF.toInt()))
        }
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            addView(menuHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            lightRow(this)
            val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(1), dp(8), dp(1)) } }
            addView(drawerItem("Games", "games"), lp())
            addView(drawerHeader("Default settings"))
            addView(drawerItem("Graphics", "graphics"), lp())
            addView(drawerItem("EpiShaders", "epi"), lp())
            addView(drawerItem("Controls", "pad"), lp())
            addView(drawerItem("Frame generation", "framegen"), lp())
            addView(drawerItem("Device", "device"), lp())
            addView(drawerItem("Driver", "driver"), lp())
            addView(drawerHeader("More"))
            addView(drawerItem("Tools and logs", "tools"), lp())
            addView(drawerItem("Diagnostics", "diag"), lp())
            setPadding(0, 0, 0, dp(24))
        }
        val drawerScroll = ScrollView(this).apply { addView(drawerPanel); background = BackdropDrawable(cBg, density); elevation = dp(16).toFloat() }

        val root = android.widget.FrameLayout(this).apply {
            background = BackdropDrawable(cBg, density)
            addView(main, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scrim, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(drawerScroll, android.widget.FrameLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        drawerScroll.translationX = -dp(290).toFloat()
        drawerPanel.translationX = 0f
        drawerSlide = drawerScroll

        // after a switch between dark and light the same page comes back
        val restored = savedInstanceState?.getString("page")
        if (restored != null) page = restored
        setScope(if (page.startsWith("game:")) page.removePrefix("game:") else null)
        buildPage()

        if (savedInstanceState == null) {      // not again when the screen is only being rebuilt
            val auto = intent?.getStringExtra("launch")
            if (auto != null) autoLaunch(auto, intent?.getStringExtra("mode"))
            val play = intent?.getStringExtra("play")
            if (play != null) playGame(play)
        }
    }

    private var drawerSlide: android.view.View? = null
}
