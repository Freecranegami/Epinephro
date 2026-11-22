package com.freecrane.adrenotest

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.WeakHashMap

// The in-game performance overlay is FusionHUD by The412Banner (https://github.com/The412Banner/FusionHUD, GPL-3.0 with an
// additional section 7(b) attribution term, see com/winlator/star/ATTRIBUTION-FusionHUD.md). Its source is in
// com.winlator.star.*; this file only hosts it in a small window above the game and feeds it frame times.
// The Vulkan layer stamps every presented frame in a shared file (a count plus a ring of times); the overlay replays the
// stamps into FusionHUD's FpsCounter, so FPS, the frame-time graph and the 1% / 0.01% lows are real.
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

    private val panels = WeakHashMap<Activity, Host>()

    // FusionHUD's sizes: tap the card in the game to cycle them; the choice is remembered
    val sizes = com.winlator.star.widget.fusionhud.FusionSize.values().map { it.token to it.label }
    fun size(ctx: Context): String = Prefs.get(ctx).getString("perf_size", "pill") ?: "pill"
    fun setSize(ctx: Context, token: String) { Prefs.get(ctx).edit().putString("perf_size", token).commit() }

    fun attach(a: Activity) {
        try {
            if (!enabled(HostApp.instance) || panels.containsKey(a)) return
            val p = Host(a)
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

    // The window that holds FusionHUD. It drags the whole window (FusionHUD itself would only slide inside it).
    private class Host(private val act: Activity) : FrameLayout(act) {
        private val ui = Handler(Looper.getMainLooper())
        private val hud = com.winlator.star.widget.fusionhud.FusionHudView(act)
        private val fps = com.winlator.star.widget.FpsCounter()
        private var counter: java.nio.ByteBuffer? = null
        private var lastN = -1L
        private var running = false
        private var locked = false
        private val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
        private var dx = 0f; private var dy = 0f; private var ox = 0; private var oy = 0; private var dragging = false

        fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

        init {
            val prefs = Prefs.get(HostApp.instance)
            locked = prefs.getBoolean("perf_locked", false)
            val cfg = com.winlator.star.container.Container.DEFAULT_FPS_COUNTER_CONFIG
                .replace(Regex("hudSize=[a-z]+"), "hudSize=" + size(HostApp.instance))
                .replace("hudLocked=0", "hudLocked=" + if (locked) "1" else "0") + ",showFPSGraph=1,showGpuTemp=1"
            hud.applyConfig(cfg)
            hud.setFpsCounter(fps)
            hud.setEngineLabel("Vulkan")
            hud.setOnSizeCycledListener { t -> setSize(HostApp.instance, t); post { requestLayout() } }
            hud.setOnLockChangedListener { l -> locked = l; Prefs.get(HostApp.instance).edit().putBoolean("perf_locked", l).apply() }
            addView(hud, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            try {
                val f = file(HostApp.instance)
                if (f.exists()) counter = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, 4096) }.order(ByteOrder.LITTLE_ENDIAN)
            } catch (t: Throwable) { Log.w("GuestHost", "overlay: can't read the frame counter: $t") }
            Log.i("GuestHost", "overlay: FusionHUD on, size ${size(HostApp.instance)}, frame times " + (if (counter != null) "from the layer" else "unavailable"))
        }

        fun start() { if (!running) { running = true; lastN = -1; ui.post(poll) } }
        fun stop() { running = false; ui.removeCallbacks(poll) }

        private val poll = object : Runnable {
            override fun run() {
                if (!running) return
                try { replay() } catch (_: Throwable) { }
                ui.postDelayed(this, 250)
            }
        }

        // the layer's stamps (ms since boot, ring of 500 after a 64-byte header) go into FusionHUD's counter
        private fun replay() {
            val b = counter ?: return
            val n = b.getLong(0)
            if (lastN < 0 || n < lastN) { lastN = n; return }
            var i = maxOf(lastN, n - 500)
            while (i < n) {
                val t = b.getLong(64 + ((i % 500) * 8).toInt())
                if (t > 0) fps.tickAt(t)
                i++
            }
            lastN = n
        }

        // Dragging moves the window. A tap and a long press still reach FusionHUD (size / lock); once the finger has moved
        // beyond the touch slop on an unlocked card, the drag is taken here.
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            val lp = layoutParams as? WindowManager.LayoutParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> if (!locked && !dragging && (Math.abs(e.rawX - dx) > slop || Math.abs(e.rawY - dy) > slop)) dragging = true
            }
            return dragging
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val lp = layoutParams as? WindowManager.LayoutParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val m = act.resources.displayMetrics
                    lp.x = (ox + (e.rawX - dx)).toInt().coerceIn(0, maxOf(0, m.widthPixels - width))
                    lp.y = (oy + (e.rawY - dy)).toInt().coerceIn(0, maxOf(0, m.heightPixels - height))
                    try { act.windowManager.updateViewLayout(this, lp) } catch (_: Throwable) { }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) Prefs.get(HostApp.instance).edit().putInt("perf_x", lp.x).putInt("perf_y", lp.y).apply()
                    dragging = false
                }
            }
            return true
        }
    }
}
