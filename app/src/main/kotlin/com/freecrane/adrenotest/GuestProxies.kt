package com.freecrane.adrenotest

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Modifier

// More of what a real install gives a game, built from how Android itself runs components:
//  - Activities: the system only knows this app's activities, so the game's own activities start through proxy
//    activities (GuestFactory swaps in the game's class). Their screen orientation is declared in our manifest, so there
//    is one proxy per orientation kind and the game's own manifest value picks which one is used.
//  - Services: the game's services are not known to the system either. A pool of stub services (declared in our manifest,
//    running in the game process) stands in for them; each stub creates the game's service object, hands it the game's
//    context and forwards every call to it.
class GuestProxyPortrait : GuestProxyActivity()
class GuestProxySensor : GuestProxyActivity()

object GuestServices {
    private const val TAG = "GuestHost"
    private val classes: Array<Class<*>> = arrayOf(
        GuestStubService0::class.java, GuestStubService1::class.java, GuestStubService2::class.java, GuestStubService3::class.java,
        GuestStubService4::class.java, GuestStubService5::class.java, GuestStubService6::class.java, GuestStubService7::class.java)
    private val targets = arrayOfNulls<String>(classes.size)

    @Synchronized
    private fun stubFor(target: String): Class<*>? {
        var i = targets.indexOf(target)
        if (i < 0) i = targets.indexOf(null)
        if (i < 0) { Log.w(TAG, "service $target: no free stub (8 services at once)"); return null }
        targets[i] = target
        return classes[i]
    }

    @Synchronized
    fun targetOf(stub: String): String? {
        val i = classes.indexOfFirst { it.name == stub }
        return if (i < 0) null else targets[i]
    }

    // The same intent aimed at a stub, when it is an explicit request for one of the game's own services.
    fun route(g: GuestRuntime.Guest, intent: Intent): Intent? {
        val cn = intent.component
        val pkg = cn?.packageName ?: intent.`package`
        if (pkg != g.pkg || cn == null) return null
        val cls = if (cn.className.startsWith(".")) g.pkg + cn.className else cn.className
        val stub = stubFor(cls) ?: return null
        val n = Intent(intent)
        n.`package` = null
        n.component = ComponentName(HostApp.instance.packageName, stub.name)
        Log.i(TAG, "service request: $cls -> ${stub.simpleName}")
        return n
    }

    fun nameOf(g: GuestRuntime.Guest, intent: Intent): ComponentName? {
        val cn = intent.component ?: return null
        return ComponentName(g.pkg, if (cn.className.startsWith(".")) g.pkg + cn.className else cn.className)
    }
}

open class GuestStubService : Service() {
    private var d: Service? = null

    override fun onCreate() {
        super.onCreate()
        val g = GuestRuntime.current
        val target = GuestServices.targetOf(javaClass.name)
        if (g == null || target == null) { Log.w("GuestHost", "stub service ${javaClass.simpleName} started with no game service"); stopSelf(); return }
        try {
            val s = g.loader.loadClass(target).getDeclaredConstructor().newInstance() as Service
            // the game's service is attached the way the system would have: same token, thread and class name as this stub
            for (f in Service::class.java.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                try { f.set(s, f.get(this)) } catch (t: Throwable) { }
            }
            try { Service::class.java.getDeclaredField("mApplication").apply { isAccessible = true }.set(s, g.app ?: application) } catch (t: Throwable) { }
            val attach = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(s, GuestContext(baseContext, g))
            d = s
            s.onCreate()
            Log.i("GuestHost", "game service created: $target")
        } catch (t: Throwable) {
            Log.e("GuestHost", "game service $target failed to start", t)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = d?.onStartCommand(intent, flags, startId) ?: START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = d?.onBind(intent)
    override fun onUnbind(intent: Intent?): Boolean = d?.onUnbind(intent) ?: false
    override fun onRebind(intent: Intent?) { d?.onRebind(intent) }
    override fun onTaskRemoved(rootIntent: Intent?) { d?.onTaskRemoved(rootIntent) }
    override fun onLowMemory() { d?.onLowMemory() }
    override fun onTrimMemory(level: Int) { d?.onTrimMemory(level) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); d?.onConfigurationChanged(newConfig) }
    override fun onDestroy() { try { d?.onDestroy() } catch (t: Throwable) { Log.w("GuestHost", "game service onDestroy: $t") }; d = null; super.onDestroy() }
}

class GuestStubService0 : GuestStubService()
class GuestStubService1 : GuestStubService()
class GuestStubService2 : GuestStubService()
class GuestStubService3 : GuestStubService()
class GuestStubService4 : GuestStubService()
class GuestStubService5 : GuestStubService()
class GuestStubService6 : GuestStubService()
class GuestStubService7 : GuestStubService()
