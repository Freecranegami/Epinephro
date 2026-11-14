package com.freecrane.adrenotest

import android.os.Handler
import android.os.Looper
import android.util.Log

// The Play Store is not reachable from here, so Play Core calls the game makes (such as the "rate this game" prompt) fail with
// an error of a kind the game does not expect, and the game's own handler then throws on the main thread and the process dies.
// This lets the main thread carry on after those specific errors, and only those: anything else is rethrown as before.
object MainGuard {
    private fun expected(t: Throwable): Boolean {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth++ < 6) {
            if (e is ClassCastException) {
                val m = e.message ?: ""
                if (m.contains("play/core") || m.contains("play.core") || e.stackTrace.any { it.methodName.contains("showReviewDialog") }) return true
            }
            e = e.cause
        }
        return false
    }

    fun install() {
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    break
                } catch (t: Throwable) {
                    if (!expected(t)) throw t
                    Log.w("GuestHost", "guard: a Play Core callback of the game failed and was skipped: $t")
                }
            }
        }
    }
}
