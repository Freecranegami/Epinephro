package com.freecrane.adrenotest

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

// A short performance summary of every run, written to the log (lines "perf: ..."), so drivers and settings can be compared by
// frame rate, power draw and heat from the Guest log alone. Once a second it reads the frame counter the layer keeps and the
// battery; every 30 seconds it logs the totals since the game started (the first 15 seconds, mostly loading, are left out).
object PerfLog {
    private var started = false
    private const val WARMUP_S = 15
    private const val EVERY_S = 30

    fun start(ctx: Context, driver: String) {
        synchronized(this) { if (started) return; started = true }
        val app = ctx.applicationContext
        val t = Thread({ try { run(app, driver) } catch (e: Throwable) { Log.w("GuestHost", "perf: stopped: $e") } }, "perf-log")
        t.isDaemon = true
        t.start()
    }

    private fun run(app: Context, driver: String) {
        val counter = try {
            val f = File(app.filesDir, "perf_frames.bin")
            RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, 4096) }.order(ByteOrder.LITTLE_ENDIAN)
        } catch (e: Throwable) { null }
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val zones = cpuZones()

        val fps = ArrayList<Double>()
        var wSum = 0.0; var wN = 0; var tMax = Float.NaN; var charging = false
        var level0 = -1; var temp0 = Float.NaN; var level = -1; var last = -1L; var lastT = 0L; var sec = 0
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            Thread.sleep(1000)
            sec++
            val now = SystemClock.elapsedRealtime()
            val n = try { counter?.getLong(0) ?: -1L } catch (e: Throwable) { -1L }
            val bi: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            level = bi?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0 && s > 0) l * 100 / s else -1 } ?: -1
            val mv = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
            if (st == BatteryManager.BATTERY_STATUS_CHARGING || (bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) charging = true
            val bt = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) Float.NaN else it / 10f }
            if (sec == WARMUP_S) { level0 = level; temp0 = bt }
            if (sec > WARMUP_S) {
                if (n >= 0 && last >= 0 && now > lastT) fps.add((n - last) * 1000.0 / (now - lastT))
                var cur = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong() } catch (e: Throwable) { 0L }
                if (cur != 0L && Math.abs(cur) < 20000) cur *= 1000     // milliamps on a few phones
                if (cur != 0L && mv > 0) { wSum += Math.abs(cur) / 1e6 * (mv / 1000.0); wN++ }
                var t = bt
                for (z in zones) { val v = readTemp(z); if (!v.isNaN() && (t.isNaN() || v > t)) t = v }
                if (!t.isNaN() && (tMax.isNaN() || t > tMax)) tMax = t
            }
            last = n; lastT = now
            if (sec > WARMUP_S && (sec - WARMUP_S) % EVERY_S == 0 && fps.isNotEmpty()) report(driver, sec - WARMUP_S, fps, wSum, wN, level0, level, temp0, bt, tMax, charging, counter != null)
        }
    }

    private fun report(driver: String, secs: Int, fps: List<Double>, wSum: Double, wN: Int, l0: Int, l1: Int, t0: Float, t1: Float,
                       tMax: Float, charging: Boolean, haveCounter: Boolean) {
        val s = fps.sorted()
        val avg = fps.average()
        val low = s[(s.size * 0.05).toInt().coerceAtMost(s.size - 1)]       // the slowest 5% of seconds
        val w = if (wN > 0) wSum / wN else Double.NaN
        val us = java.util.Locale.US
        val sb = StringBuilder("perf: driver \"$driver\", ${secs}s measured: ")
        sb.append("${"%.1f".format(us, avg)} fps avg, ${"%.1f".format(us, low)} fps in the slowest 5% of seconds, worst second ${"%.0f".format(us, s.first())}")
        if (!w.isNaN()) sb.append("; power ${"%.2f".format(us, w)} W avg, ${"%.2f".format(us, avg / w)} fps per W, ${"%.0f".format(us, w / maxOf(avg, 0.1) * 1000)} mJ per frame")
        else sb.append("; power not readable")
        if (l0 >= 0 && l1 >= 0) sb.append("; battery $l0% -> $l1%")
        if (!t0.isNaN() && !t1.isNaN()) sb.append("; battery temp ${"%.1f".format(us, t0)} -> ${"%.1f".format(us, t1)} C")
        if (!tMax.isNaN()) sb.append(", hottest sensor ${"%.0f".format(us, tMax)} C")
        if (charging) sb.append("; CHARGER CONNECTED, power numbers are not reliable")
        if (!haveCounter) sb.append("; no frame counter")
        Log.i("GuestHost", sb.toString())
    }

    private fun readTemp(f: File): Float = try {
        val v = f.readText().trim().toFloat()
        if (v > 1000f) v / 1000f else v
    } catch (_: Throwable) { Float.NaN }

    private fun cpuZones(): List<File> = try {
        (File("/sys/class/thermal").listFiles { x -> x.name.startsWith("thermal_zone") } ?: emptyArray<File>()).filter { z ->
            try {
                val type = File(z, "type").readText().trim().lowercase()
                (type.contains("cpu") || type == "soc") && !readTemp(File(z, "temp")).isNaN()
            } catch (_: Throwable) { false }
        }.map { File(it, "temp") }
    } catch (_: Throwable) { emptyList() }
}
