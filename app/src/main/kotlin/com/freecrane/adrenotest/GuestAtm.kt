package com.freecrane.adrenotest

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

// Launcher wrappers (fastman92 and similar) start "the real" activity of the game with startActivity.
// The system would look for that activity in the installed game, which may be missing or may refuse us
// (not exported). Every start aimed at the game's own package is turned into a start of our proxy activity,
// which GuestFactory turns into the game's activity class.
object GuestAtm {
    private const val TAG = "GuestHost"
    @Volatile private var installed = false

    @Synchronized
    fun install(g: GuestRuntime.Guest) {
        if (installed) return
        try {
            val c = Class.forName("android.app.ActivityTaskManager")
            val f = c.getDeclaredField("IActivityTaskManagerSingleton").apply { isAccessible = true }
            val singleton = f.get(null)
            val sc = Class.forName("android.util.Singleton")
            val mi = sc.getDeclaredField("mInstance").apply { isAccessible = true }
            val orig = sc.getMethod("get").invoke(singleton)
            val iface = Class.forName("android.app.IActivityTaskManager")
            mi.set(singleton, Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), Hook(orig, g)))
            installed = true
            Log.i(TAG, "activity start hook installed")
        } catch (t: Throwable) {
            Log.w(TAG, "could not hook activity starts: $t")
        }
    }

    private class Hook(private val orig: Any, private val g: GuestRuntime.Guest) : InvocationHandler {
        override fun invoke(proxy: Any, m: Method, args: Array<out Any?>?): Any? {
            val a: Array<Any?> = Array(args?.size ?: 0) { args!![it] }
            if (m.name == "startActivity" || m.name == "startActivityAsUser") {
                for (i in a.indices) {
                    val it = a[i] as? Intent ?: continue
                    val cn = it.component ?: continue
                    if (cn.packageName != g.pkg) continue
                    val target = if (cn.className.startsWith(".")) g.pkg + cn.className else cn.className
                    val n = Intent(it)
                    n.component = ComponentName(HostApp.instance.packageName, GuestProxyActivity::class.java.name)
                    n.putExtra(GuestRuntime.EXTRA_PKG, g.id)
                    n.putExtra(GuestRuntime.EXTRA_ACTIVITY, target)
                    Log.i(TAG, "activity start: ${cn.flattenToShortString()} -> game activity $target")
                    a[i] = n
                    break
                }
            }
            try {
                return m.invoke(orig, *a)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
