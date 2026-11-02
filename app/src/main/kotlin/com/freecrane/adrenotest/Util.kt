package com.freecrane.adrenotest

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object Util {

    // Copies the driver out of the app's assets once per app install. Overwriting a .so
    // that is already loaded into a process crashes it.
    fun installDriver(ctx: Context): Pair<File, String> {
        val dir = File(ctx.filesDir, "driver").apply { mkdirs() }
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val marker = File(dir, ".installed")
        if (!marker.exists() || marker.readText() != stamp) {
            for (n in ctx.assets.list("driver") ?: emptyArray()) {
                ctx.assets.open("driver/$n").use { i ->
                    File(dir, n).outputStream().use { o -> i.copyTo(o) }
                }
            }
            marker.writeText(stamp)
        }
        val meta = JSONObject(File(dir, "meta.json").readText())
        return dir to meta.getString("libraryName")
    }

    // returns [tmpDir, hookDir, driverDir, driverName]
    fun paths(ctx: Context, custom: Boolean): List<String> {
        val tmp = File(ctx.codeCacheDir, "adreno").apply { mkdirs() }
        val hook = ctx.applicationInfo.nativeLibraryDir
        return if (custom) {
            installDriver(ctx)                       // keeps the bundled copy in place
            val d = Drivers.active(ctx)
            listOf("$tmp/", "$hook/", "${d.dir}/", d.library)
        } else {
            listOf("$tmp/", "$hook/", "", "")
        }
    }

    // Names rewritten inside a game's libraries. Every pair has the same length, so the files keep their layout.
    //   libvulkan.so -> libvkshim.so     always: the game's Vulkan goes through the app's layer (custom driver, upscalers, effects)
    //   GL libraries -> ANGLE copies     only for games with "OpenGL through ANGLE" switched on
    private val VK_PAIRS = listOf("libvulkan.so" to "libvkshim.so")
    private val ANGLE_PAIRS = listOf(
        "libGLESv1_CM.so" to "libANGLEv1CM.so",
        "libGLESv2.so" to "libANGLE2.so",
        "libGLESv3.so" to "libANGLE2.so",
        "libEGL.so" to "libANG.so")

    fun pairs(angle: Boolean): List<Pair<ByteArray, ByteArray>> =
        (VK_PAIRS + (if (angle) ANGLE_PAIRS else emptyList())).map { it.first.toByteArray(Charsets.ISO_8859_1) to it.second.toByteArray(Charsets.ISO_8859_1) }
            .onEach { require(it.first.size == it.second.size) { "name lengths differ" } }

    // Streams input to output, replacing every name of `pairs`. Returns how many were replaced.
    fun copyPatched(input: InputStream, output: OutputStream, pairs: List<Pair<ByteArray, ByteArray>> = pairs(false)): Int {
        val longest = pairs.maxOf { it.first.size }
        val buf = ByteArray(1 shl 20)
        var filled = 0
        var count = 0
        var eof = false
        while (!eof) {
            while (filled < buf.size) {
                val r = input.read(buf, filled, buf.size - filled)
                if (r < 0) { eof = true; break }
                filled += r
            }
            var i = 0
            while (i < filled) {
                var hit = false
                if (buf[i] == 'l'.code.toByte()) {
                    for ((from, to) in pairs) {
                        val n = from.size
                        if (i + n > filled) continue
                        var match = true
                        for (j in 1 until n) { if (buf[i + j] != from[j]) { match = false; break } }
                        if (match) { System.arraycopy(to, 0, buf, i, n); count++; i += n; hit = true; break }
                    }
                }
                if (!hit) i++
            }
            val keep = if (eof) 0 else minOf(longest - 1, filled)
            output.write(buf, 0, filled - keep)
            System.arraycopy(buf, filled - keep, buf, 0, keep)
            filled = keep
        }
        return count
    }

    fun patchVulkanName(src: File, dst: File): Int =
        src.inputStream().use { i -> dst.outputStream().use { o -> copyPatched(i, o) } }

    // Puts a copy of the Vulkan shim next to a game's libraries, so the game's own
    // class loader can find it. Copied once per app install.
    fun ensureShim(ctx: Context, libDir: File) {
        libDir.mkdirs()
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val target = File(libDir, "libvkshim.so")
        val marker = File(libDir, ".shim_stamp")
        if (!target.exists() || !marker.exists() || marker.readText() != stamp) {
            File(ctx.applicationInfo.nativeLibraryDir, "libvkshim.so")
                .copyTo(target, overwrite = true)
            marker.writeText(stamp)
        }
    }

    private val ANGLE_LIBS = listOf("libANG.so", "libANGLE2.so", "libANGLEv1CM.so")

    // Makes a game's lib folder match the "OpenGL via ANGLE" choice. With ANGLE on, the game's libraries are
    // unpacked again from its stored APKs with the GL/EGL names renamed to our ANGLE copies, and those copies are
    // placed next to them. Does nothing when the folder already matches (marker file angle.state in the game folder).
    // Returns a short description for the log.
    fun syncAngle(ctx: Context, root: File, angle: Boolean): String {
        val libDir = File(root, "lib")
        val state = File(root, "angle.state")
        val want = if (angle) "on" else "off"
        val have = if (state.exists()) state.readText().trim() else "off"
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (have == want + ":" + stamp || (!angle && have == "off")) {
            if (angle) for (n in ANGLE_LIBS) if (!File(libDir, n).exists()) { /* fall through to a refresh */ return refreshAngle(ctx, root, libDir, state, stamp) }
            return "ANGLE $want (unchanged)"
        }
        if (!angle) {
            val n = extractLibs(root, libDir, false)
            state.writeText("off")
            return "ANGLE off: $n libraries restored"
        }
        return refreshAngle(ctx, root, libDir, state, stamp)
    }

    private fun refreshAngle(ctx: Context, root: File, libDir: File, state: File, stamp: String): String {
        val n = extractLibs(root, libDir, true)
        val native = File(ctx.applicationInfo.nativeLibraryDir)
        for (name in ANGLE_LIBS) {
            val s = File(native, name)
            if (!s.exists()) throw IllegalStateException("ANGLE library missing from the app: $name")
            s.copyTo(File(libDir, name), overwrite = true)
        }
        ensureShim(ctx, libDir)
        state.writeText("on:$stamp")
        return "ANGLE on: $n libraries rewritten, ${ANGLE_LIBS.size} ANGLE libraries placed"
    }

    // Unpacks arm64 libraries from base.apk and splits/*.apk into libDir with the names rewritten.
    private fun extractLibs(root: File, libDir: File, angle: Boolean): Int {
        libDir.mkdirs()
        val pr = pairs(angle)
        val apks = listOf(File(root, "base.apk")) + (File(root, "splits").listFiles { f -> f.name.endsWith(".apk") }?.sortedBy { it.name } ?: emptyList())
        var n = 0
        for (apk in apks) {
            if (!apk.exists()) continue
            java.util.zip.ZipFile(apk).use { zip ->
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val p = e.name.split("/")
                    if (p.size == 3 && p[0] == "lib" && p[1] == "arm64-v8a" && e.name.endsWith(".so")) {
                        val t = File(libDir, p[2])
                        t.delete()
                        zip.getInputStream(e).use { i -> t.outputStream().use { o -> copyPatched(i, o, pr) } }
                        n++
                    }
                }
            }
        }
        return n
    }

    // Stops the game process so its files can be replaced safely.
    fun killGuestProcess(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (p in am.runningAppProcesses ?: emptyList<ActivityManager.RunningAppProcessInfo>()) {
            if (p.processName.endsWith(":guest")) android.os.Process.killProcess(p.pid)
        }
    }

    // ---- layout of an imported game ----
    //   base.apk, splits/*.apk, lib/*.so, obb/*, dexapks.txt, adapter.txt, report.txt

    fun guestSplits(root: File): List<File> {
        val files = File(root, "splits").listFiles { f -> f.isFile && f.name.endsWith(".apk") }
        return (files ?: emptyArray<File>()).sortedBy { it.name }
    }

    // The APK files that contain game code, joined for a class loader.
    fun guestDexPath(root: File): String {
        val list = File(root, "dexapks.txt")
        val files = if (list.exists()) {
            list.readLines().filter { it.isNotBlank() }.map { File(root, it) }.filter { it.exists() }
        } else {
            emptyList()
        }
        val use = if (files.isEmpty()) listOf(File(root, "base.apk")) else files
        return use.joinToString(":") { it.path }
    }
}
