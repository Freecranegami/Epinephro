package com.freecrane.adrenotest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.WeakHashMap

// A small draggable card over the game: frames per second, CPU temperature and battery (level and power draw).
// Light on purpose: one update a second, a few tiny file reads, and no drawing code of its own. The frame count comes
// from the Vulkan layer, which adds one to a number in a shared file for every frame the game presents.
object PerfOverlay {
    fun enabled(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("perf_overlay", false)
    fun setEnabled(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("perf_overlay", on).commit() }

    private fun file(ctx: Context) = File(ctx.filesDir, "perf_frames.bin")

    // Before the game's libraries load: the file the layer counts frames in.
    fun prepare(ctx: Context) {
        try {
            val f = file(ctx)
            RandomAccessFile(f, "rw").use { it.setLength(4096); it.seek(0); it.writeLong(0L) }
            android.system.Os.setenv("LAS_PERF_FILE", f.path, true)
        } catch (t: Throwable) { Log.w("GuestHost", "overlay: couldn't prepare the frame counter: $t") }
    }

    private val panels = WeakHashMap<Activity, Panel>()

    fun attach(a: Activity) {
        try {
            if (!enabled(HostApp.instance) || panels.containsKey(a)) return
            val p = Panel(a)
            panels[a] = p
            val decor = a.window.decorView
            val add = Runnable {
                try {
                    val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT)
                    lp.token = decor.windowToken
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.title = "LasPerf"
                    val prefs = Prefs.get(HostApp.instance)
                    lp.x = prefs.getInt("perf_x", p.dp(28)); lp.y = prefs.getInt("perf_y", p.dp(10))
                    if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    a.windowManager.addView(p, lp)
                    p.start()
                } catch (t: Throwable) { Log.w("GuestHost", "overlay: couldn't add the card: $t") }
            }
            if (decor.windowToken != null) add.run()
            else decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(x: View) { decor.removeOnAttachStateChangeListener(this); decor.post(add) }
                override fun onViewDetachedFromWindow(x: View) { }
            })
        } catch (t: Throwable) { Log.w("GuestHost", "overlay: attach failed: $t") }
    }

    fun detach(a: Activity) {
        val p = panels.remove(a) ?: return
        p.stop()
        try { a.windowManager.removeViewImmediate(p) } catch (_: Throwable) { }
    }

    val modes = listOf("Compact (FPS, CPU temperature, battery)", "Detailed (adds frame time, graph, GPU, memory)", "Full (adds per-core clocks, GPU clock, swap, temperatures, time)")
    fun mode(ctx: Context): Int = Prefs.get(ctx).getInt("perf_mode", 0).coerceIn(0, 2)
    fun setMode(ctx: Context, v: Int) { Prefs.get(ctx).edit().putInt("perf_mode", v).commit() }

    // the fps history as a small line graph
    private class Graph(ctx: Context) : View(ctx) {
        private val h = FloatArray(60)
        private var n = 0
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5EE6A8.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density }
        private val base = android.graphics.Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f }
        fun push(v: Float) { if (n < h.size) h[n++] = v else { System.arraycopy(h, 1, h, 0, h.size - 1); h[h.size - 1] = v }; invalidate() }
        override fun onDraw(c: android.graphics.Canvas) {
            if (n < 2) return
            var mx = 1f; for (i in 0 until n) if (h[i] > mx) mx = h[i]
            mx *= 1.15f
            val w = width.toFloat(); val ht = height.toFloat()
            c.drawLine(0f, ht - 1f, w, ht - 1f, base)
            val p = android.graphics.Path()
            for (i in 0 until n) {
                val x = w * i / (h.size - 1).toFloat(); val y = ht - (h[i] / mx) * (ht - 3f) - 1f
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(p, line)
        }
    }

    private class Panel(private val act: Activity) : LinearLayout(act) {
        private val ui = Handler(Looper.getMainLooper())
        private val mode = mode(HostApp.instance)
        private val fps = cell("FPS")
        private val ms = cell("AVG MS")
        private val cpu = cell("CPU")
        private val gpu = cell("GPU")
        private val gpuT = cell("GPU TEMP")
        private val gclk = cell("GPU CLK")
        private val ram = cell("RAM")
        private val swap = cell("SWAP")
        private val bat = cell("BATTERY")
        private val clk = cell("CPU CLOCKS")
        private val tim = cell("TIME")
        private val graph = Graph(act)
        private var counter: java.nio.ByteBuffer? = null
        private var lastCount = -1L
        private var lastTime = 0L
        private val zones: List<File> = findZones { it.contains("cpu") || it == "soc" }
        private val gpuZones: List<File> = findZones { it.contains("gpu") }
        private val cores: List<Pair<File, File>> = findCores()
        private val bm = act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        private var running = false
        private var said = false

        fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

        private fun row(vararg cells: View): LinearLayout = LinearLayout(act).apply {
            orientation = HORIZONTAL
            for ((i, c) in cells.withIndex()) addView(c, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(14) })
        }

        init {
            orientation = VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xD00E1116.toInt()); setStroke(dp(1), 0x33FFFFFF) }
            val gl = LayoutParams(dp(110), dp(30)).apply { gravity = Gravity.BOTTOM }
            if (mode == 0) addView(row(fps.first, cpu.first, bat.first))
            else {
                addView(LinearLayout(act).apply {
                    orientation = HORIZONTAL
                    addView(fps.first); addView(ms.first, LayoutParams(-2, -2).apply { leftMargin = dp(14) })
                    addView(graph, gl.apply { leftMargin = dp(14) })
                })
                addView(row(*(listOf(cpu.first, gpu.first, gpuT.first) + (if (mode == 2) listOf(gclk.first) else emptyList())).toTypedArray()), LayoutParams(-2, -2).apply { topMargin = dp(5) })
                addView(row(*(listOf(ram.first) + (if (mode == 2) listOf(swap.first) else emptyList()) + listOf(bat.first) + (if (mode == 2) listOf(tim.first) else emptyList())).toTypedArray()), LayoutParams(-2, -2).apply { topMargin = dp(5) })
                if (mode == 2) addView(clk.first, LayoutParams(-2, -2).apply { topMargin = dp(5) })
            }
            fps.third.setTextColor(0xFF5EE6A8.toInt())
            try {
                val f = file(HostApp.instance)
                if (f.exists()) counter = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, 4096) }.order(ByteOrder.LITTLE_ENDIAN)
            } catch (t: Throwable) { Log.w("GuestHost", "overlay: can't read the frame counter: $t") }
            if (!said) {
                said = true
                Log.i("GuestHost", "overlay: on, mode $mode; CPU temperature from " + (zones.firstOrNull()?.parentFile?.name ?: "the battery (no readable CPU sensor)") +
                    "; GPU temperature " + (gpuZones.firstOrNull()?.parentFile?.name ?: "n/a") + "; GPU load " + (gpuLoad()?.let { "readable" } ?: "not readable") +
                    "; ${cores.size} CPU clocks readable")
            }
            setOnTouchListener(Drag())
        }

        private fun cell(label: String): Triple<LinearLayout, TextView, TextView> {
            val box = LinearLayout(act).apply { orientation = VERTICAL }
            val l = TextView(act).apply {
                text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f); setTextColor(0xFF8A94A3.toInt()); letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val v = TextView(act).apply {
                text = "--"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(0xFFE8ECF1.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            box.addView(l); box.addView(v)
            return Triple(box, l, v)
        }

        fun start() { if (!running) { running = true; lastCount = -1; ui.post(tick) } }
        fun stop() { running = false; ui.removeCallbacks(tick) }

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                try { update() } catch (_: Throwable) { }
                ui.postDelayed(this, 1000)
            }
        }

        private fun num(path: String): Double? = try {
            Regex("-?[0-9]+(\\.[0-9]+)?").find(File(path).readText())?.value?.toDouble()
        } catch (_: Throwable) { null }

        // GPU load in percent from the places Adreno phones put it; null when the phone does not let apps read it
        private fun gpuLoad(): Double? {
            num("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.let { return it }
            try {
                val p = File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim().split(Regex("\\s+"))
                if (p.size >= 2) { val t = p[1].toDouble(); if (t > 0) return p[0].toDouble() * 100.0 / t }
            } catch (_: Throwable) { }
            return num("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load")
        }

        private fun gpuClockMhz(): Double? {
            val hz = num("/sys/class/kgsl/kgsl-3d0/gpuclk") ?: num("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq") ?: return null
            return if (hz > 1e6) hz / 1e6 else hz
        }

        private fun mem(): Map<String, Long> = try {
            File("/proc/meminfo").readLines().mapNotNull { l ->
                val p = l.split(":"); if (p.size < 2) null else p[0] to (p[1].trim().split(" ")[0].toLongOrNull() ?: 0L)
            }.toMap()
        } catch (_: Throwable) { emptyMap() }

        private fun update() {
            // frames per second, from the layer's counter
            val n = counter?.getLong(0) ?: -1L
            val now = SystemClock.elapsedRealtime()
            if (n >= 0 && lastCount >= 0 && now > lastTime) {
                val f = (n - lastCount) * 1000.0 / (now - lastTime)
                fps.third.text = Math.round(f).toString()
                ms.third.text = if (f > 0.5) "%.1f".format(java.util.Locale.US, 1000.0 / f) else "--"
                graph.push(f.toFloat())
            } else if (n < 0) fps.third.text = "n/a"
            lastCount = n; lastTime = now

            // battery: level, temperature, power draw
            val bi: Intent? = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bi?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0 && s > 0) l * 100 / s else -1 } ?: -1
            val mv = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val batTemp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) Float.NaN else it / 10f }
            var cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()   // microamps on most phones, milliamps on a few
            if (cur != 0L && Math.abs(cur) < 20000) cur *= 1000
            val watts = if (cur != 0L && mv > 0) Math.abs(cur) / 1e6 * (mv / 1000.0) else Double.NaN
            bat.third.text = (if (level >= 0) "$level%" else "--") + (if (!watts.isNaN()) "  ${"%.1f".format(java.util.Locale.US, watts)} W" else "") +
                (if (mode == 2 && !batTemp.isNaN()) "  ${Math.round(batTemp)}°C" else "")

            // temperature: a CPU sensor when the phone lets apps read one, else the battery's
            var t = Float.NaN
            for (z in zones) { val v = readTemp(z); if (!v.isNaN() && (t.isNaN() || v > t)) t = v }
            if (t.isNaN()) { cpu.second.text = "BAT TEMP"; t = batTemp } else cpu.second.text = "CPU"
            cpu.third.text = if (t.isNaN()) "--" else "${Math.round(t)}°C"
            if (mode == 0) return

            // GPU: load and temperature where the phone allows it
            gpu.third.text = gpuLoad()?.let { "${Math.round(it)}%" } ?: "n/a"
            var gt = Float.NaN
            for (z in gpuZones) { val v = readTemp(z); if (!v.isNaN() && (gt.isNaN() || v > gt)) gt = v }
            gpuT.third.text = if (gt.isNaN()) "n/a" else "${Math.round(gt)}°C"
            val m = mem()
            val tot = m["MemTotal"] ?: 0L; val av = m["MemAvailable"] ?: 0L
            ram.third.text = if (tot > 0) "%.1f / %.1f GB".format(java.util.Locale.US, (tot - av) / 1048576.0, tot / 1048576.0) else "--"
            if (mode == 2) {
                gclk.third.text = gpuClockMhz()?.let { "${Math.round(it)} MHz" } ?: "n/a"
                val st = m["SwapTotal"] ?: 0L; val sf = m["SwapFree"] ?: 0L
                swap.third.text = if (st > 0) "%.1f GB".format(java.util.Locale.US, (st - sf) / 1048576.0) else "off"
                tim.third.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
                val sb = StringBuilder()
                for ((i, c) in cores.withIndex()) {
                    val mhz = num(c.first.path)?.let { it / 1000.0 }
                    if (i > 0) sb.append("  ")
                    sb.append("C").append(i).append(' ').append(if (mhz == null) "--" else "%.1f".format(java.util.Locale.US, mhz / 1000.0))
                }
                clk.third.text = if (cores.isEmpty()) "n/a (the phone doesn't let apps read it)" else sb.toString()
            }
        }

        private fun readTemp(f: File): Float = try {
            val v = f.readText().trim().toFloat()
            if (v > 1000f) v / 1000f else v
        } catch (_: Throwable) { Float.NaN }

        private fun findZones(match: (String) -> Boolean): List<File> = try {
            (File("/sys/class/thermal").listFiles { x -> x.name.startsWith("thermal_zone") } ?: emptyArray<File>()).filter { z ->
                try {
                    val type = File(z, "type").readText().trim().lowercase()
                    match(type) && !readTemp(File(z, "temp")).isNaN()
                } catch (_: Throwable) { false }
            }.map { File(it, "temp") }
        } catch (_: Throwable) { emptyList() }

        // per-core current clocks: (current, max) files of the cores whose clock the phone lets apps read
        private fun findCores(): List<Pair<File, File>> = try {
            (0 until 16).mapNotNull { i ->
                val cur = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                val max = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (cur.canRead() && num(cur.path) != null) cur to max else null
            }
        } catch (_: Throwable) { emptyList() }

        // dragging: the card follows the finger; its place is remembered
        private inner class Drag : OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = v.layoutParams as? WindowManager.LayoutParams ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        val m = act.resources.displayMetrics
                        lp.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, maxOf(0, m.widthPixels - v.width))
                        lp.y = (oy + (e.rawY - sy)).toInt().coerceIn(0, maxOf(0, m.heightPixels - v.height))
                        try { act.windowManager.updateViewLayout(v, lp) } catch (_: Throwable) { }
                    }
                    MotionEvent.ACTION_UP -> Prefs.get(HostApp.instance).edit().putInt("perf_x", lp.x).putInt("perf_y", lp.y).apply()
                }
                return true
            }
        }
    }
}
