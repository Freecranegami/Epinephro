package com.freecrane.adrenotest

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log

// The Google Play licence service, as the game sees it. Games (the Play Licensing Library, PairIP) ask the Play Store
// "does this user own me?" by binding to com.android.vending.licensing.ILicensingService. The real one refuses a caller that
// is not the installed game itself, which an imported game is not, so the bind used to be refused outright.
//
// Now the bind is answered by this service inside the app. It speaks the same protocol (the AIDL interface is small and
// stable: checkLicense = transaction 1, checkLicenseV2 = transaction 2, the result listeners take transaction 1), logs
// what the game asked for, and passes the question to a LicenseSource. A source gives Google's own answer for the user's
// account; this service never invents one. Without a source the answer is "could not contact the server".
object GuestLicense {
    private const val TAG = "GuestHost"
    const val ACTION = "com.android.vending.licensing.ILicensingService"
    private const val DESC = "com.android.vending.licensing.ILicensingService"
    private const val DESC_L1 = "com.android.vending.licensing.ILicenseResultListener"
    private const val DESC_L2 = "com.android.vending.licensing.ILicenseV2ResultListener"

    const val ERROR_CONTACTING_SERVER = 0x101

    /** Source of real answers (the signed in account's licence from Google). */
    interface LicenseSource {
        // result code, signed data, signature (null when there is nothing to say); null = no answer at all
        fun checkV1(pkg: String, nonce: Long): Triple<Int, String?, String?>?
        // result code and the Bundle for the V2 listener; null = no answer at all
        fun checkV2(pkg: String, extra: Bundle?): Pair<Int, Bundle>?
    }

    @Volatile var source: LicenseSource? = null

    fun wants(intent: Intent?): Boolean {
        if (intent == null) return false
        val pkg = intent.component?.packageName ?: intent.`package`
        return pkg == "com.android.vending" && (intent.action == ACTION || intent.component?.className?.endsWith("LicensingService") == true)
    }

    // Answers a bind request for the licence service; true when it did.
    fun bind(intent: Intent?, conn: ServiceConnection, g: GuestRuntime.Guest?): Boolean {
        if (g == null || !wants(intent) || !GuestPm.stubsOn()) return false
        Log.i(TAG, "licence service: the game binds to the Play licence service; answered by the app")
        val name = ComponentName("com.android.vending", "com.android.vending.licensing.LicensingService")
        android.os.Handler(Looper.getMainLooper()).post {
            try { conn.onServiceConnected(name, binder(g)) } catch (t: Throwable) { Log.w(TAG, "licence service connect: $t") }
        }
        return true
    }

    private fun binder(g: GuestRuntime.Guest): IBinder = object : Binder() {
        override fun getInterfaceDescriptor(): String = DESC
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                1 -> {
                    data.enforceInterface(DESC)
                    val nonce = data.readLong(); val pkg = data.readString() ?: ""; val l = data.readStrongBinder()
                    Log.i(TAG, "licence request (v1): package $pkg nonce $nonce")
                    answer { 
                        val r = if (pkg == g.pkg) source?.checkV1(pkg, nonce) else null
                        val t = r ?: Triple(ERROR_CONTACTING_SERVER, null, null)
                        Log.i(TAG, "licence answer (v1): code ${t.first}" + (if (r == null) " (no licence source: not asking Google)" else " (from Google)"))
                        val p = Parcel.obtain()
                        try { p.writeInterfaceToken(DESC_L1); p.writeInt(t.first); p.writeString(t.second); p.writeString(t.third); l.transact(1, p, null, IBinder.FLAG_ONEWAY) } finally { p.recycle() }
                    }
                    return true
                }
                2 -> {
                    data.enforceInterface(DESC)
                    val pkg = data.readString() ?: ""; val l = data.readStrongBinder()
                    val extra = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                    Log.i(TAG, "licence request (v2): package $pkg")
                    answer {
                        val r = if (pkg == g.pkg) source?.checkV2(pkg, extra) else null
                        if (r == null) { Log.i(TAG, "licence answer (v2): none (no licence source: not asking Google)"); return@answer }
                        Log.i(TAG, "licence answer (v2): code ${r.first} (from Google)")
                        val p = Parcel.obtain()
                        try { p.writeInterfaceToken(DESC_L2); p.writeInt(r.first); p.writeInt(1); r.second.writeToParcel(p, 0); l.transact(1, p, null, IBinder.FLAG_ONEWAY) } finally { p.recycle() }
                    }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    // The real service answers later, on its own thread.
    private fun answer(work: () -> Unit) {
        Thread { try { work() } catch (t: Throwable) { Log.w(TAG, "licence answer failed: $t") } }.also { it.name = "las-licence" }.start()
    }
}
