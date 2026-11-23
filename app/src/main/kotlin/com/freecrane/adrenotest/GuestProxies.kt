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

// Creates the game's service object for a stub: same token, thread and class name as the stub, the game's context.
internal fun makeGameService(stub: Service, target: String, g: GuestRuntime.Guest): Service {
    val s = g.loader.loadClass(target).getDeclaredConstructor().newInstance() as Service
    for (f in Service::class.java.declaredFields) {
        if (Modifier.isStatic(f.modifiers)) continue
        f.isAccessible = true
        try { f.set(s, f.get(stub)) } catch (t: Throwable) { }
    }
    try { Service::class.java.getDeclaredField("mApplication").apply { isAccessible = true }.set(s, g.app ?: stub.application) } catch (t: Throwable) { }
    val attach = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
    attach.isAccessible = true
    attach.invoke(s, GuestContext(stub.baseContext, g))
    return s
}

open class GuestStubService : Service() {
    private var d: Service? = null

    override fun onCreate() {
        super.onCreate()
        val g = GuestRuntime.current
        val target = GuestServices.targetOf(javaClass.name)
        if (g == null || target == null) { Log.w("GuestHost", "stub service ${javaClass.simpleName} started with no game service"); stopSelf(); return }
        try {
            val s = makeGameService(this, target, g)
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

// Jobs (JobScheduler, which WorkManager and many SDKs use). The scheduler refuses a job whose service is not in this
// app, so the game's scheduler is a wrapper that points each job at a stub job service; the stub runs the game's own
// JobService object the way the stubs above do.
object GuestJobs {
    private const val TAG = "GuestHost"
    private val classes: Array<Class<*>> = arrayOf(
        GuestStubJob0::class.java, GuestStubJob1::class.java, GuestStubJob2::class.java, GuestStubJob3::class.java,
        GuestStubJob4::class.java, GuestStubJob5::class.java, GuestStubJob6::class.java, GuestStubJob7::class.java)
    private val targets = arrayOfNulls<String>(classes.size)

    @Synchronized
    private fun stubFor(target: String): Class<*>? {
        var i = targets.indexOf(target)
        if (i < 0) i = targets.indexOf(null)
        if (i < 0) { Log.w(TAG, "job service $target: no free stub"); return null }
        targets[i] = target
        return classes[i]
    }

    @Synchronized
    fun targetOf(stub: String): String? {
        val i = classes.indexOfFirst { it.name == stub }
        return if (i < 0) null else targets[i]
    }

    // A copy of the job aimed at a stub (JobInfo has no setter for its service, so it is copied through a parcel).
    fun redirect(g: GuestRuntime.Guest, job: android.app.job.JobInfo): android.app.job.JobInfo {
        val cn = job.service
        if (cn.packageName != g.pkg) return job
        val cls = if (cn.className.startsWith(".")) g.pkg + cn.className else cn.className
        val stub = stubFor(cls) ?: return job
        return try {
            val parcel = android.os.Parcel.obtain()
            val copy: android.app.job.JobInfo
            try {
                job.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                copy = android.app.job.JobInfo.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
            val f = android.app.job.JobInfo::class.java.getDeclaredField("service").apply { isAccessible = true }
            f.set(copy, ComponentName(HostApp.instance.packageName, stub.name))
            Log.i(TAG, "job ${job.id}: $cls -> ${stub.simpleName}")
            copy
        } catch (t: Throwable) { Log.w(TAG, "job redirect failed: $t"); job }
    }

    fun wrap(real: android.app.job.JobScheduler, g: GuestRuntime.Guest): android.app.job.JobScheduler = Wrapper(real, g)

    private class Wrapper(val r: android.app.job.JobScheduler, val g: GuestRuntime.Guest) : android.app.job.JobScheduler() {
        override fun schedule(job: android.app.job.JobInfo): Int = r.schedule(redirect(g, job))
        override fun enqueue(job: android.app.job.JobInfo, work: android.app.job.JobWorkItem): Int = r.enqueue(redirect(g, job), work)
        override fun cancel(jobId: Int) = r.cancel(jobId)
        override fun cancelAll() = r.cancelAll()
        override fun getAllPendingJobs(): MutableList<android.app.job.JobInfo> = r.allPendingJobs
        override fun getPendingJob(jobId: Int): android.app.job.JobInfo? = r.getPendingJob(jobId)
    }
}

open class GuestStubJob : android.app.job.JobService() {
    private var d: android.app.job.JobService? = null

    private fun delegate(): android.app.job.JobService? {
        d?.let { return it }
        val g = GuestRuntime.current
        val target = GuestJobs.targetOf(javaClass.name)
        if (g == null || target == null) return null
        return try {
            val s = makeGameService(this, target, g) as android.app.job.JobService
            d = s
            s.onCreate()
            Log.i("GuestHost", "game job service created: $target")
            s
        } catch (t: Throwable) { Log.e("GuestHost", "game job service $target failed", t); null }
    }

    // jobFinished() on the game's object needs the engine the system bound to this stub
    private fun share() {
        try {
            val f = android.app.job.JobService::class.java.getDeclaredField("mEngine").apply { isAccessible = true }
            f.set(d, f.get(this))
        } catch (t: Throwable) { }
    }

    override fun onStartJob(params: android.app.job.JobParameters): Boolean { val s = delegate() ?: return false; share(); return s.onStartJob(params) }
    override fun onStopJob(params: android.app.job.JobParameters): Boolean = d?.onStopJob(params) ?: false
    override fun onLowMemory() { d?.onLowMemory() }
    override fun onTrimMemory(level: Int) { d?.onTrimMemory(level) }
    override fun onDestroy() { try { d?.onDestroy() } catch (t: Throwable) { }; d = null; super.onDestroy() }
}

class GuestStubJob0 : GuestStubJob()
class GuestStubJob1 : GuestStubJob()
class GuestStubJob2 : GuestStubJob()
class GuestStubJob3 : GuestStubJob()
class GuestStubJob4 : GuestStubJob()
class GuestStubJob5 : GuestStubJob()
class GuestStubJob6 : GuestStubJob()
class GuestStubJob7 : GuestStubJob()
