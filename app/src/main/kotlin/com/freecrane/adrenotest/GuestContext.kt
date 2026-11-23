package com.freecrane.adrenotest

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import android.view.ContextThemeWrapper
import java.io.File

// The context a game's own activity sees: the game's package name, resources, assets,
// class loader, APK path and OBB folder, on top of this app's real context.
class GuestContext(base: Context, private val g: GuestRuntime.Guest) : ContextWrapper(base) {

    override fun getPackageName(): String = g.pkg
    override fun getApplicationInfo(): ApplicationInfo = g.ai
    override fun getResources(): Resources = g.res
    override fun getAssets(): AssetManager = g.res.assets
    override fun getClassLoader(): ClassLoader = g.loader
    override fun getPackageCodePath(): String = g.apk.path
    override fun getPackageResourcePath(): String = g.apk.path
    override fun getObbDir(): File = g.obbDir
    override fun getObbDirs(): Array<File> = arrayOf(g.obbDir)
    override fun getApplicationContext(): Context = g.app ?: super.getApplicationContext()

    // Questions about the game's own package are answered from its APK (see GuestPm).
    override fun getPackageManager(): PackageManager {
        val pm = super.getPackageManager()
        GuestPm.install(pm, g)
        return pm
    }

    // The game's own folders, where a real install would have them: files, cache, databases, preferences and the
    // external folders (which are the ones the native file hooks map Android/data/<package> to). Off = this app's own.
    private fun own(): Boolean = Tweaks.gpaths(HostApp.instance)
    private fun sub(n: String): java.io.File = java.io.File(g.ai.dataDir, n).also { it.mkdirs() }
    private fun ext(n: String): java.io.File = java.io.File(HostApp.instance.filesDir, "guests/${g.id}/ext/$n").also { it.mkdirs() }
    override fun getDataDir(): java.io.File = if (own()) java.io.File(g.ai.dataDir).also { it.mkdirs() } else super.getDataDir()
    override fun getFilesDir(): java.io.File = if (own()) sub("files") else super.getFilesDir()
    override fun getNoBackupFilesDir(): java.io.File = if (own()) sub("no_backup") else super.getNoBackupFilesDir()
    override fun getCacheDir(): java.io.File = if (own()) sub("cache") else super.getCacheDir()
    override fun getCodeCacheDir(): java.io.File = if (own()) sub("code_cache") else super.getCodeCacheDir()
    override fun getDir(name: String?, mode: Int): java.io.File = if (own()) sub("app_$name") else super.getDir(name, mode)
    override fun getDatabasePath(name: String): java.io.File =
        if (!own()) super.getDatabasePath(name) else if (name.startsWith("/")) java.io.File(name) else java.io.File(sub("databases"), name)
    override fun databaseList(): Array<String> = if (own()) (sub("databases").list() ?: emptyArray()) else super.databaseList()
    override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase =
        if (own()) openOrCreateDatabase(name, mode, factory, null) else super.openOrCreateDatabase(name, mode, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
                                      errorHandler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase {
        if (!own()) return super.openOrCreateDatabase(name, mode, factory, errorHandler)
        var flags = android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY
        if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) flags = flags or android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        return android.database.sqlite.SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, flags, errorHandler)
    }
    override fun deleteDatabase(name: String): Boolean =
        if (own()) android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name)) else super.deleteDatabase(name)
    private fun prefName(n: String?) = "g_${g.id}_${n ?: "null"}"
    override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences =
        if (own()) super.getSharedPreferences(prefName(name), mode) else super.getSharedPreferences(name, mode)
    override fun deleteSharedPreferences(name: String): Boolean =
        if (own()) super.deleteSharedPreferences(prefName(name)) else super.deleteSharedPreferences(name)
    override fun getExternalFilesDir(type: String?): java.io.File? =
        if (own()) ext(if (type == null) "data/files" else "data/files/$type") else super.getExternalFilesDir(type)
    override fun getExternalFilesDirs(type: String?): Array<java.io.File> =
        if (own()) arrayOf(getExternalFilesDir(type)!!) else super.getExternalFilesDirs(type)
    override fun getExternalCacheDir(): java.io.File? = if (own()) ext("data/cache") else super.getExternalCacheDir()
    override fun getExternalCacheDirs(): Array<java.io.File> = if (own()) arrayOf(getExternalCacheDir()!!) else super.getExternalCacheDirs()
    override fun getExternalMediaDirs(): Array<java.io.File> = if (own()) arrayOf(ext("media")) else super.getExternalMediaDirs()

    // The game's data lives in this app's own folders, so its storage permission checks are answered "granted".
    private fun storagePerm(p: String?) = p == "android.permission.READ_EXTERNAL_STORAGE" ||
        p == "android.permission.WRITE_EXTERNAL_STORAGE" || p == "android.permission.MANAGE_EXTERNAL_STORAGE"
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (storagePerm(permission)) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)
    override fun checkSelfPermission(permission: String): Int =
        if (storagePerm(permission)) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)

    // The game's own services run through stub services (GuestProxies.kt).
    override fun bindService(service: android.content.Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
        GuestServices.route(g, service)?.let { return super.bindService(it, conn, flags) }
        if (GuestLicense.bind(service, conn, g)) return true
        if (GuestPm.blocksPlay(service)) return false
        return super.bindService(service, conn, flags)
    }

    override fun bindService(service: android.content.Intent, flags: Int, executor: java.util.concurrent.Executor,
                             conn: android.content.ServiceConnection): Boolean {
        GuestServices.route(g, service)?.let { return super.bindService(it, flags, executor, conn) }
        if (GuestLicense.bind(service, conn, g)) return true
        if (GuestPm.blocksPlay(service)) return false
        return super.bindService(service, flags, executor, conn)
    }

    override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
        GuestServices.route(g, service)?.let { super.startForegroundService(it); return GuestServices.nameOf(g, service) }
        return if (GuestPm.blocksPlay(service)) null else super.startForegroundService(service)
    }

    override fun stopService(name: android.content.Intent): Boolean {
        GuestServices.route(g, name)?.let { return super.stopService(it) }
        return super.stopService(name)
    }

    // Google services are "not installed" (see GuestPm.blocksPlay): no starting them either.
    override fun startService(service: android.content.Intent): android.content.ComponentName? {
        GuestServices.route(g, service)?.let { super.startService(it); return GuestServices.nameOf(g, service) }
        return if (GuestPm.blocksPlay(service)) null else super.startService(service)
    }
    override fun startActivity(intent: android.content.Intent) {
        if (GuestPm.blocksPlay(intent)) return
        super.startActivity(intent)
    }
    override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) {
        if (GuestPm.blocksPlay(intent)) return
        super.startActivity(intent, options)
    }

    // Some system services identify the caller by the package name of the context they were made
    // for. The game mode service refuses a package that is not installed, so hand out this app's own.
    override fun getSystemService(name: String): Any? {
        if (name == "notification") {
            try { HostApp.instance.getSystemService(name)?.let { return it } } catch (t: Throwable) { }
        }
        if (name == "game") {
            try {
                val r = HostApp.instance.getSystemService(name)
                Log.i(TAG, "game mode service from the host app: " + (r != null))
                if (r != null) return r
            } catch (t: Throwable) {
                Log.w(TAG, "game mode service lookup failed: $t")
            }
        }
        if (name == Context.JOB_SCHEDULER_SERVICE) {
            val r = super.getSystemService(name)
            if (r is android.app.job.JobScheduler) return GuestJobs.wrap(r, g)
            return r
        }
        return super.getSystemService(name)
    }

    companion object {
        private const val TAG = "GuestHost"

        private fun setField(cls: Class<*>, name: String, obj: Any, value: Any?): Boolean = try {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            f.set(obj, value)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not set ${cls.simpleName}.$name: $t")
            false
        }

        // Runs after the system has attached a game's activity and before its onCreate.
        // Swaps the activity's base context for the game's, and drops anything the
        // activity already cached from our own context during attach.
        // The game's native code asks the activity class for getActivity()/getContext(), which read
        // static fields normally filled in by the game's own startup. Fill them before it runs.
        private fun seedStatics(a: Activity) {
            var c: Class<*>? = a.javaClass
            while (c != null && c != Activity::class.java) {
                for (f in c.declaredFields) {
                    if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    if (java.lang.reflect.Modifier.isFinal(f.modifiers)) continue
                    val v: Any? = when {
                        f.name.contains("activity", true) && f.type.isAssignableFrom(a.javaClass) -> a
                        f.name.contains("context", true) && f.type.isAssignableFrom(a.javaClass) -> a
                        else -> null
                    }
                    if (v == null) continue
                    try { f.isAccessible = true; if (f.get(null) == null) { f.set(null, v); Log.i(TAG, "seeded ${c.simpleName}.${f.name}") } }
                    catch (e: Throwable) { Log.w(TAG, "seed ${f.name} failed: $e") }
                }
                c = c.superclass
            }
        }

        fun install(a: Activity) {
            val g = GuestRuntime.current ?: return
            if (a.javaClass.classLoader !== g.loader) return
            val base = setField(ContextWrapper::class.java, "mBase", a, GuestContext(a.baseContext, g))
            val res = setField(ContextThemeWrapper::class.java, "mResources", a, null)
            val theme = setField(ContextThemeWrapper::class.java, "mTheme", a, null)
            val infl = setField(ContextThemeWrapper::class.java, "mInflater", a, null)
            // Activity.getApplication() should hand back the game's Application, not ours.
            val gameApp = g.app
            val app = if (gameApp != null) setField(Activity::class.java, "mApplication", a, gameApp) else true
            Log.i(TAG, "game context installed for ${a.javaClass.name}: " +
                "base=$base resources=$res theme=$theme inflater=$infl application=$app")
            seedStatics(a)
        }
    }
}
