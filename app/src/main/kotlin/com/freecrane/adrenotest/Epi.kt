package com.freecrane.adrenotest

import android.content.Context
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File

// EpiShaders: ReShade effects (bundled .fx files) that run on the finished picture after the upscaler.
// The app stores which effects are on and their settings in Prefs (so each game can have its own); the game process writes a
// small preset file from them and hands it to the native library (libepifx.so) through LAS_EPI* variables.
object Epi {
    class Effect(val id: String, val name: String, val info: String)
    class Group(val title: String, val effects: List<Effect>)

    // Order here is the order the effects run in: sharpen / anti-alias first, colour next, bloom, vignette, CRT last.
    val groups = listOf(
        Group("Sharpening", listOf(
            Effect("LumaSharpen", "LumaSharpen", "Sharpens the picture without making edges glow"),
            Effect("CAS", "AMD CAS", "AMD's contrast adaptive sharpening"))),
        Group("Anti-aliasing", listOf(
            Effect("FXAA", "FXAA", "Smooths jagged edges, a ReShade version"),
            Effect("SMAA", "SMAA", "Sharper anti-aliasing that costs more"))),
        Group("Colour", listOf(
            Effect("FakeHDR", "HDR", "Fake HDR: brighter highlights, deeper shadows"),
            Effect("Vibrance", "Vibrance", "Boosts dull colours more than colourful ones"),
            Effect("Tonemap", "Tonemap", "Gamma, exposure, saturation and bleach"),
            Effect("Levels", "Colour tools: Levels", "Black and white points"),
            Effect("Curves", "Colour tools: Curves", "Contrast curve"),
            Effect("LiftGammaGain", "Colour tools: Lift Gamma Gain", "Shadows, mid-tones and highlights per colour"),
            Effect("ColorMatrix", "Colour tools: Colour matrix", "Mixes the red, green and blue channels"),
            Effect("Technicolor", "Colour tools: Technicolor", "Old film colour look"),
            Effect("Technicolor2", "Colour tools: Technicolor 2", "Another film colour look"),
            Effect("DPX", "Colour tools: DPX (cinema film)", "Film scan look"),
            Effect("Sepia", "Colour tools: Sepia", "Brown old photo tone"),
            Effect("Monochrome", "Colour tools: Monochrome", "Black and white"),
            Effect("LUT", "LUT", "Colour look from a lookup picture"))),
        Group("Light", listOf(
            Effect("Bloom", "Bloom", "Glow around bright areas"),
            Effect("MagicBloom", "Bloom (Magic, with lens dirt)", "A softer, wider glow"),
            Effect("Vignette", "Vignette", "Darkens the corners"))),
        Group("Style", listOf(
            Effect("Cartoon", "Cartoon", "Draws dark outlines"))),
        Group("RetroArch CRT", listOf(
            Effect("zfast_crt", "CRT: zfast (light)", "Cheapest CRT look, scanlines"),
            Effect("CRTEasymode", "CRT: Easymode", "Scanlines and mask"),
            Effect("CRTLottes", "CRT: Lottes", "Classic CRT with bloom"),
            Effect("CRTLottes2", "CRT: Lottes 2", "Lottes, second version"),
            Effect("CRTGeom", "CRT: Geom (curved screen)", "Curved glass and shadow mask, heavy"),
            Effect("CRTcgwg", "CRT: cgwg", "Mask and scanlines"),
            Effect("CRTAperture", "CRT: Aperture grille", "Trinitron style"),
            Effect("CRTCaligari", "CRT: Caligari", "Soft glowing scanlines"),
            Effect("CRTHyllian", "CRT: Hyllian", "Sharp scanlines"),
            Effect("CRTPi", "CRT: Pi (light)", "Scanlines made for weak hardware"))))

    val all: List<Effect> get() = groups.flatMap { it.effects }

    fun master(ctx: Context): Boolean = Prefs.get(ctx).getBoolean("epi_on", true)
    fun setMaster(ctx: Context, on: Boolean) { Prefs.get(ctx).edit().putBoolean("epi_on", on).commit() }
    fun on(ctx: Context, id: String): Boolean = Prefs.get(ctx).getBoolean("epi_e_$id", false)
    fun setOn(ctx: Context, id: String, v: Boolean) { Prefs.get(ctx).edit().putBoolean("epi_e_$id", v).commit() }
    fun enabledCount(ctx: Context): Int = all.count { on(ctx, it.id) }

    private fun key(id: String, u: String) = "epi_v_${id}__$u"
    fun value(ctx: Context, id: String, u: String): DoubleArray? =
        Prefs.get(ctx).getString(key(id, u), null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.toDoubleArray()?.takeIf { it.isNotEmpty() }
    fun setValue(ctx: Context, id: String, u: String, v: DoubleArray) {
        Prefs.get(ctx).edit().putString(key(id, u), v.joinToString(",") { "%.6g".format(java.util.Locale.US, it) }).commit()
    }
    fun resetEffect(ctx: Context, id: String) {
        val e = Prefs.get(ctx).edit()
        for (k in Prefs.get(ctx).all.keys) if (k.startsWith("epi_v_${id}__")) e.remove(k)
        e.commit()
    }

    // ---- files ----

    fun root(ctx: Context) = File(ctx.filesDir, "epishaders")

    // Copies the bundled shaders and pictures out of the APK (again after an app update).
    fun ensure(ctx: Context): File {
        val r = root(ctx)
        val stamp = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString() } catch (t: Throwable) { "0" }
        val sf = File(r, ".stamp")
        if (r.isDirectory && sf.exists() && sf.readText() == stamp) return r
        r.deleteRecursively()
        fun copy(path: String, dst: File) {
            val kids = ctx.assets.list(path)
            if (kids != null && kids.isNotEmpty()) {
                dst.mkdirs()
                for (k in kids) copy("$path/$k", File(dst, k))
            } else {
                dst.parentFile?.mkdirs()
                ctx.assets.open(path).use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            }
        }
        copy("epishaders", r)
        sf.writeText(stamp)
        return r
    }

    // ---- the user's own LUT picture ----
    // A LUT strip is N tiles of N x N pixels side by side, so its width is N x N and its height N (16: 256x16, 32: 1024x32,
    // 64: 4096x64). The picture is kept in the app's files; the choice (file name, tile size) lives in the settings so each
    // game can have its own. Before the effects compile, the bundled lut.png / LUT.fx are swapped for it.
    fun lutFile(ctx: Context): File? {
        val n = Prefs.get(ctx).getString("epi_lut_file", null) ?: return null
        val f = File(File(ctx.filesDir, "epi_luts"), n)
        return if (f.isFile) f else null
    }
    fun lutTile(ctx: Context): Int = Prefs.get(ctx).getInt("epi_lut_tile", 32)

    // A cube of colours (n x n x n, red varies fastest, then green, then blue; values 0..1, 3 floats each) as a LUT strip
    // of t tiles of t x t pixels. When t differs from n the cube is resampled (trilinear).
    private fun cubeToStrip(cube: FloatArray, n: Int, t: Int): android.graphics.Bitmap {
        fun at(r: Int, g: Int, b: Int, c: Int) = cube[((b * n + g) * n + r) * 3 + c]
        val px = IntArray(t * t * t)
        for (bt in 0 until t) for (g in 0 until t) for (r in 0 until t) {
            val out = IntArray(3)
            if (t == n) {
                for (c in 0..2) out[c] = (at(r, g, bt, c).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            } else {
                val fr = r * (n - 1f) / (t - 1f); val fg = g * (n - 1f) / (t - 1f); val fb = bt * (n - 1f) / (t - 1f)
                val r0 = fr.toInt().coerceAtMost(n - 2); val g0 = fg.toInt().coerceAtMost(n - 2); val b0 = fb.toInt().coerceAtMost(n - 2)
                val dr = fr - r0; val dg = fg - g0; val db = fb - b0
                for (c in 0..2) {
                    var v = 0f
                    for (i in 0..7) {
                        val x = i and 1; val y = (i shr 1) and 1; val z = (i shr 2) and 1
                        val wgt = (if (x == 1) dr else 1 - dr) * (if (y == 1) dg else 1 - dg) * (if (z == 1) db else 1 - db)
                        v += wgt * at(r0 + x, g0 + y, b0 + z, c)
                    }
                    out[c] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                }
            }
            px[g * (t * t) + bt * t + r] = (0xFF shl 24) or (out[0] shl 16) or (out[1] shl 8) or out[2]
        }
        val bmp = android.graphics.Bitmap.createBitmap(t * t, t, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, t * t, 0, 0, t * t, t)
        return bmp
    }

    private fun tileFor(n: Int) = if (n in 8..64) n else if (n > 64) 64 else 16

    // An Adobe/Resolve .cube file (3D). Throws IllegalArgumentException with a message for the user.
    private fun cubeFileToStrip(text: String): android.graphics.Bitmap {
        var n = 0
        var lo = floatArrayOf(0f, 0f, 0f); var hi = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var k = 0
        for (raw in text.lineSequence()) {
            val l = raw.substringBefore('#').trim()
            if (l.isEmpty()) continue
            val c0 = l[0]
            if (c0.isLetter()) {
                val parts = l.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "LUT_3D_SIZE" -> { n = parts.getOrNull(1)?.toIntOrNull() ?: 0; if (n < 2 || n > 129) throw IllegalArgumentException("Unsupported .cube size $n."); data = FloatArray(n * n * n * 3) }
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("That .cube file is a 1D LUT. Only 3D .cube files (LUT_3D_SIZE) work.")
                    "DOMAIN_MIN" -> if (parts.size >= 4) lo = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    "DOMAIN_MAX" -> if (parts.size >= 4) hi = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                }
                continue
            }
            val d = data ?: throw IllegalArgumentException("The .cube file has colour data before LUT_3D_SIZE.")
            val parts = l.split(Regex("\\s+"))
            if (parts.size < 3 || k + 3 > d.size) { if (k + 3 > d.size) continue else throw IllegalArgumentException("Bad line in the .cube file: $l") }
            for (c in 0..2) {
                val span = if (hi[c] - lo[c] != 0f) hi[c] - lo[c] else 1f
                d[k + c] = (parts[c].toFloat() - lo[c]) / span
            }
            k += 3
        }
        val d = data ?: throw IllegalArgumentException("No LUT_3D_SIZE in the .cube file.")
        if (k != d.size) throw IllegalArgumentException("The .cube file has ${k / 3} colours but ${n}x${n}x$n = ${n * n * n} were expected.")
        return cubeToStrip(d, n, tileFor(n))
    }

    // A Hald CLUT picture: a square picture of side L^3 holding a cube of L^2 per side, red fastest, row by row.
    private fun haldToStrip(bmp: android.graphics.Bitmap, level: Int): android.graphics.Bitmap {
        val n = level * level
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val cube = FloatArray(n * n * n * 3)
        for (i in 0 until n * n * n) {
            val c = px[i]
            cube[i * 3] = ((c shr 16) and 255) / 255f
            cube[i * 3 + 1] = ((c shr 8) and 255) / 255f
            cube[i * 3 + 2] = (c and 255) / 255f
        }
        return cubeToStrip(cube, n, tileFor(n))
    }

    private fun displayName(ctx: Context, uri: android.net.Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "" } ?: ""
    } catch (t: Throwable) { "" }

    // Returns a message for the user. Accepts a LUT strip (N tiles of NxN in one row), a Hald CLUT picture, or a 3D .cube file.
    fun importLut(ctx: Context, uri: android.net.Uri): String {
        try {
            val name0 = displayName(ctx, uri)
            var kind = "strip"
            var strip: android.graphics.Bitmap? = null
            val head = ctx.contentResolver.openInputStream(uri)?.use { i -> ByteArray(256).let { b -> String(b, 0, maxOf(0, i.read(b)), Charsets.ISO_8859_1) } } ?: return "Couldn't read that file."
            val isText = name0.endsWith(".cube", true) || head.contains("LUT_3D_SIZE") || head.contains("LUT_1D_SIZE") || head.contains("DOMAIN_M")
            if (isText) {
                kind = ".cube"
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (bytes.size > 64 * 1024 * 1024) return "That file is too big for a LUT."
                strip = try { cubeFileToStrip(String(bytes, Charsets.UTF_8)) } catch (e: IllegalArgumentException) { return e.message ?: "Couldn't read the .cube file." }
                  catch (e: NumberFormatException) { return "The .cube file has a value I can't read." }
            } else {
                val bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    ?: return "Couldn't read that file as a picture or a .cube LUT."
                val w = bmp.width; val h = bmp.height
                if (h in 8..64 && w == h * h) strip = bmp
                else {
                    val level = (4..16).firstOrNull { it * it * it == w }
                    if (w == h && level != null) { kind = "Hald level $level"; strip = haldToStrip(bmp, level); bmp.recycle() }
                    else {
                        bmp.recycle()
                        return "That picture is ${w}x$h. I can use a LUT strip (256x16, 1024x32 or 4096x64), a square Hald CLUT picture " +
                            "(512x512 is level 8, 216x216 level 6 and so on) or a 3D .cube file."
                    }
                }
            }
            val st = strip!!
            val t = st.height
            val dir = File(ctx.filesDir, "epi_luts").apply { mkdirs() }
            val name = "lut_" + System.currentTimeMillis() + ".png"
            File(dir, name).outputStream().use { st.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            st.recycle()
            val old = Prefs.get(ctx).getString("epi_lut_file", null)
            Prefs.get(ctx).edit().putString("epi_lut_file", name).putInt("epi_lut_tile", t).commit()
            if (old != null && old != name) try { File(dir, old).delete() } catch (_: Throwable) { }
            prepareLut(ctx)
            return "LUT imported ($kind, $t tiles of ${t}x$t). Turn on the LUT effect to use it. Applies the next time the game starts."
        } catch (t: Throwable) { return "Couldn't import that LUT: $t" }
    }

    fun clearLut(ctx: Context) {
        val old = Prefs.get(ctx).getString("epi_lut_file", null)
        Prefs.get(ctx).edit().remove("epi_lut_file").remove("epi_lut_tile").commit()
        if (old != null) try { File(File(ctx.filesDir, "epi_luts"), old).delete() } catch (_: Throwable) { }
        prepareLut(ctx)
    }

    // Puts the chosen (or the bundled) LUT picture and tile size where the effect looks for them.
    fun prepareLut(ctx: Context) {
        try {
            val r = ensure(ctx)
            val custom = lutFile(ctx)
            val tex = File(r, "Textures/lut.png")
            tex.parentFile?.mkdirs()
            if (custom != null) custom.copyTo(tex, overwrite = true)
            else ctx.assets.open("epishaders/Textures/lut.png").use { i -> tex.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            val fx = ctx.assets.open("epishaders/Shaders/LUT.fx").use { it.readBytes().toString(Charsets.UTF_8) }
            val n = if (custom != null) lutTile(ctx) else 32
            File(r, "Shaders/LUT.fx").writeText("#define fLUT_TileSizeXY $n\n#define fLUT_TileAmount $n\n" + fx)
            cache.remove("LUT")
        } catch (t: Throwable) { Log.w("GuestHost", "epishaders: LUT setup failed: $t") }
    }

    // The settings of one effect, for the UI. Compiles the effect with ReShade's compiler (a few hundred ms the first time).
    class Param(val name: String, val type: String, val rows: Int, val ui: String, val min: Double?, val max: Double?, val step: Double?,
                val label: String, val tip: String, val cat: String, val items: List<String>, val def: DoubleArray)

    private val cache = HashMap<String, List<Param>?>()
    private var loaded = false

    @JvmStatic external fun describe(root: String, file: String): String

    @Synchronized
    fun params(ctx: Context, id: String): List<Param>? {
        if (cache.containsKey(id)) return cache[id]
        val r = try {
            if (!loaded) { System.loadLibrary("epifx"); loaded = true }
            val j = JSONObject(describe(ensure(ctx).path, "$id.fx"))
            if (!j.optBoolean("ok")) { Log.w("GuestHost", "epishaders: $id: ${j.optString("log")}"); null }
            else {
                val a = j.getJSONArray("uniforms")
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    val items = o.optJSONArray("items")?.let { ar -> (0 until ar.length()).map { ar.getString(it) } } ?: emptyList()
                    val d = o.getJSONArray("def")
                    Param(o.getString("n"), o.getString("t"), o.optInt("r", 1), o.optString("ui"),
                        if (o.has("min")) o.getDouble("min") else null, if (o.has("max")) o.getDouble("max") else null,
                        if (o.has("step")) o.getDouble("step") else null, o.optString("label"), o.optString("tip"), o.optString("cat"),
                        items, DoubleArray(d.length()) { d.getDouble(it) })
                }
            }
        } catch (t: Throwable) { Log.w("GuestHost", "epishaders: describe $id failed: $t"); null }
        cache[id] = r
        return r
    }

    // ---- hand-over to the game process ----

    // Called in the game process before its libraries load.
    fun apply(ctx: Context) {
        try {
            val enabled = if (master(ctx)) all.filter { on(ctx, it.id) } else emptyList()
            if (enabled.isEmpty()) { Os.setenv("LAS_EPI", "0", true); return }
            val r = ensure(ctx)
            if (enabled.any { it.id == "LUT" }) {
                prepareLut(ctx)
                Log.i("GuestHost", "epishaders: LUT picture " + (lutFile(ctx)?.let { "imported (${lutTile(ctx)} tiles)" } ?: "bundled"))
            }
            val pre = File(ctx.filesDir, "epishaders_preset_" + (Prefs.scope ?: "default").replace(Regex("[^A-Za-z0-9_.]"), "_") + ".txt")
            val sb = StringBuilder()
            for (e in enabled) {
                if (!File(r, "Shaders/${e.id}.fx").exists()) continue
                sb.append("effect ").append(e.id).append('\n')
            }
            val all = Prefs.get(ctx).all
            for (e in enabled) {
                val p = "epi_v_${e.id}__"
                for ((k, v) in all) if (k.startsWith(p) && v is String) sb.append("set ").append(e.id).append(' ').append(k.removePrefix(p)).append(' ').append(v.replace(',', ' ')).append('\n')
            }
            pre.writeText(sb.toString())
            Os.setenv("LAS_EPI", "1", true)
            Os.setenv("LAS_EPI_LIB", File(ctx.applicationInfo.nativeLibraryDir, "libepifx.so").path, true)
            Os.setenv("LAS_EPI_ROOT", r.path, true)
            Os.setenv("LAS_EPI_PRESET", pre.path, true)
            Log.i("GuestHost", "epishaders: on, ${enabled.joinToString { it.id }}")
        } catch (t: Throwable) {
            Log.w("GuestHost", "epishaders: setup failed: $t")
            try { Os.setenv("LAS_EPI", "0", true) } catch (_: Throwable) { }
        }
    }
}
