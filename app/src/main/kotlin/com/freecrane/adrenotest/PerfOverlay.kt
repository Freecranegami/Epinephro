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
            if (!enabled(ctx)) { android.system.Os.setenv("LAS_PERF_FILE", "", true); return }
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

    private class Panel(private val act: Activity) : LinearLayout(act) {
        private val ui = Handler(Looper.getMainLooper())
        private val fps = cell("FPS")
        private val cpu = cell("CPU")
        private val bat = cell("BATTERY")
        private var counter: java.nio.ByteBuffer? = null
        private var lastCount = -1L
        private var lastTime = 0L
        private val zones: List<File> = findCpuZones()
        private val bm = act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        private var running = false
        private var said = false

        fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

        init {
            orientation = HORIZONTAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xD00E1116.toInt()); setStroke(dp(1), 0x33FFFFFF) }
            for ((i, c) in listOf(fps, cpu, bat).withIndex()) {
                addView(c.first, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(14) })
            }
            fps.third.setTextColor(0xFF5EE6A8.toInt())
            try {
                val f = file(HostApp.instance)
                if (f.exists()) counter = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, 4096) }.order(ByteOrder.LITTLE_ENDIAN)
            } catch (t: Throwable) { Log.w("GuestHost", "overlay: can't read the frame counter: $t") }
            if (!said) { said = true; Log.i("GuestHost", "overlay: on; CPU temperature from " + (zones.firstOrNull()?.parentFile?.name ?: "the battery (no readable CPU sensor)")) }
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

        private fun update() {
            // frames per second, from the layer's counter
            val n = counter?.getLong(0) ?: -1L
            val now = SystemClock.elapsedRealtime()
            if (n >= 0 && lastCount >= 0 && now > lastTime) fps.third.text = Math.round((n - lastCount) * 1000.0 / (now - lastTime)).toString()
            else if (n < 0) fps.third.text = "n/a"
            lastCount = n; lastTime = now

            // battery: level, temperature, power draw
            val bi: Intent? = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bi?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0 && s > 0) l * 100 / s else -1 } ?: -1
            val mv = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val batTemp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) Float.NaN else it / 10f }
            var cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()   // microamps on most phones, milliamps on a few
            if (cur != 0L && Math.abs(cur) < 20000) cur *= 1000
            val watts = if (cur != 0L && mv > 0) Math.abs(cur) / 1e6 * (mv / 1000.0) else Double.NaN
            bat.third.text = (if (level >= 0) "$level%" else "--") + (if (!watts.isNaN()) "  ${"%.1f".format(java.util.Locale.US, watts)} W" else "")

            // temperature: a CPU sensor when the phone lets apps read one, else the battery's
            var t = Float.NaN
            for (z in zones) { val v = readTemp(z); if (!v.isNaN() && (t.isNaN() || v > t)) t = v }
            if (t.isNaN()) { cpu.second.text = "BAT TEMP"; t = batTemp } else cpu.second.text = "CPU"
            cpu.third.text = if (t.isNaN()) "--" else "${Math.round(t)}°C"
        }

        private fun readTemp(f: File): Float = try {
            val v = f.readText().trim().toFloat()
            if (v > 1000f) v / 1000f else v
        } catch (_: Throwable) { Float.NaN }

        private fun findCpuZones(): List<File> = try {
            (File("/sys/class/thermal").listFiles { x -> x.name.startsWith("thermal_zone") } ?: emptyArray<File>()).filter { z ->
                try {
                    val type = File(z, "type").readText().trim().lowercase()
                    (type.contains("cpu") || type == "soc") && !readTemp(File(z, "temp")).isNaN()
                } catch (_: Throwable) { false }
            }.map { File(it, "temp") }
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
