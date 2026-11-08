package com.freecrane.adrenotest

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.SeekBar
import android.widget.Switch

// The look of the app: a dark theme (default) and a light one, both built from the logo's colours (magenta, pink and
// green) with a few extra hues so every section has its own colour. The light mode is one global switch (the menu);
// it is stored in the defaults file and never per game. Shapes come from the logo too: hexagons, a green triangle and
// diagonal cuts, drawn here as small Drawables so there are no image files to keep in step with the colours.
object Theme {
    @Volatile var light = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("las", Context.MODE_PRIVATE)
    fun load(ctx: Context) { light = try { prefs(ctx).getBoolean("ui_light", false) } catch (t: Throwable) { false } }
    fun setLight(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("ui_light", on).commit(); light = on }
    fun styleRes(): Int = if (light) R.style.Theme_Epi_Light else R.style.Theme_Epi_Dark

    // ----- brand colours -----
    const val MAGENTA = 0xFFD04CF2.toInt()
    const val PINK = 0xFFFF9EDB.toInt()
    const val GREEN = 0xFF17FF74.toInt()
    const val VIOLET = 0xFF7C5CFF.toInt()
    const val CYAN = 0xFF22D3EE.toInt()
    const val ORANGE = 0xFFFF8A4C.toInt()
    const val BLUE = 0xFF4D7CFF.toInt()
    const val TEAL = 0xFF14C8A0.toInt()

    // ----- surfaces and text -----
    val bg: Int get() = if (light) 0xFFFAF6FF.toInt() else 0xFF0E0A1A.toInt()
    val card: Int get() = if (light) 0xFFFFFFFF.toInt() else 0xFF1A1330.toInt()
    val inset: Int get() = if (light) 0xFFF1E9FB.toInt() else 0xFF120D24.toInt()
    val text: Int get() = if (light) 0xFF1E1233.toInt() else 0xFFF4EEFF.toInt()
    val sub: Int get() = if (light) 0xFF6E6286.toInt() else 0xFFA99CC6.toInt()
    val off: Int get() = if (light) 0xFFD9CFEA.toInt() else 0xFF3A3150.toInt()
    val line: Int get() = if (light) 0xFF1E1233.toInt() else 0xFFF4EEFF.toInt()

    // the top bar and the menu header
    val barA: Int get() = if (light) 0xFF8F34F0.toInt() else 0xFF6A2BD9.toInt()
    val barB: Int get() = if (light) 0xFFE24DC4.toInt() else 0xFFC03CCB.toInt()

    val pairs: List<Pair<Int, Int>> = listOf(MAGENTA to PINK, VIOLET to MAGENTA, PINK to ORANGE, GREEN to CYAN,
        CYAN to VIOLET, BLUE to VIOLET, GREEN to TEAL, ORANGE to PINK)

    fun pairFor(title: String): Pair<Int, Int> = when (title.lowercase()) {
        "graphics" -> VIOLET to MAGENTA
        "epishaders" -> PINK to ORANGE
        "frame generation" -> CYAN to VIOLET
        "virtual gamepad", "controls" -> GREEN to CYAN
        "device" -> BLUE to VIOLET
        "driver" -> GREEN to TEAL
        "games", "game" -> MAGENTA to PINK
        "logs" -> ORANGE to PINK
        else -> pairs[(title.hashCode() and 0x7fffffff) % pairs.size]
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    // readable text in a section's colour
    fun accentText(p: Pair<Int, Int>): Int = if (light) mix(p.first, Color.BLACK, 0.32f) else mix(p.first, Color.WHITE, 0.2f)

    fun grad(a: Int, b: Int, radius: Float, horizontal: Boolean = true): GradientDrawable =
        GradientDrawable(if (horizontal) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = radius }

    // ----- controls -----
    fun styleSwitch(sw: Switch, p: Pair<Int, Int>, d: Float) {
        fun track(c1: Int, c2: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
            cornerRadius = 100f * d; setSize((46 * d).toInt(), (26 * d).toInt())
        }
        fun thumb(c: Int) = InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setSize((18 * d).toInt(), (18 * d).toInt()) }, (4 * d).toInt())
        sw.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(p.first, p.second))
            addState(intArrayOf(), track(off, off))
        }
        sw.thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), thumb(Color.WHITE))
            addState(intArrayOf(), thumb(if (light) Color.WHITE else 0xFF8E83A8.toInt()))
        }
    }

    fun styleSeek(sb: SeekBar, p: Pair<Int, Int>) {
        sb.progressTintList = ColorStateList.valueOf(p.first)
        sb.thumbTintList = ColorStateList.valueOf(p.second)
        sb.progressBackgroundTintList = ColorStateList.valueOf(off)
    }

    fun pillButton(b: android.widget.Button, p: Pair<Int, Int>, d: Float) {
        b.background = grad(p.first, p.second, 100f * d)
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        b.stateListAnimator = null
        b.setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), (8 * d).toInt())
        b.minWidth = 0; b.minimumWidth = 0; b.minHeight = 0; b.minimumHeight = 0
        b.typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
}

private fun hexPath(path: Path, cx: Float, cy: Float, rad: Float) {
    path.reset()
    for (i in 0 until 6) {
        val a = Math.toRadians(-90.0 + 60.0 * i)
        val x = cx + rad * Math.cos(a).toFloat(); val y = cy + rad * Math.sin(a).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

// A settings card: a rounded surface, a gradient strip on the left, a small coloured triangle in the top right corner
// (the logo's green triangle) and a thin gradient outline.
class CardDrawable(private val fill: Int, private val a: Int, private val b: Int, private val r: Float, private val d: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val tri = Path()
    override fun draw(c: Canvas) {
        val rf = RectF(bounds)
        if (rf.width() < 1f || rf.height() < 1f) return
        p.style = Paint.Style.FILL; p.shader = null; p.color = fill
        c.drawRoundRect(rf, r, r, p)
        c.save()
        clip.reset(); clip.addRoundRect(rf, r, r, Path.Direction.CW); c.clipPath(clip)
        p.shader = LinearGradient(0f, rf.top, 0f, rf.bottom, a, b, Shader.TileMode.CLAMP)
        c.drawRect(rf.left, rf.top, rf.left + 4f * d, rf.bottom, p)
        val t = 26f * d
        tri.reset(); tri.moveTo(rf.right - t, rf.top); tri.lineTo(rf.right, rf.top); tri.lineTo(rf.right, rf.top + t); tri.close()
        p.shader = LinearGradient(rf.right - t, rf.top, rf.right, rf.top + t, Theme.alpha(a, 0x00), Theme.alpha(b, 0x90), Shader.TileMode.CLAMP)
        c.drawPath(tri, p)
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d
        p.shader = LinearGradient(rf.left, rf.top, rf.right, rf.bottom, Theme.alpha(a, 0x90), Theme.alpha(b, 0x40), Shader.TileMode.CLAMP)
        rf.inset(0.6f * d, 0.6f * d)
        c.drawRoundRect(rf, r, r, p)
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(cf: ColorFilter?) { }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// A hexagon (pointy top, like the logo): gradient filled, or only a gradient outline.
class HexDrawable(private val a: Int, private val b: Int, private val outline: Int, private val sw: Float, private val filled: Boolean = true) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(c: Canvas) {
        val bd = bounds
        val w = bd.width().toFloat(); val h = bd.height().toFloat()
        if (w < 2f || h < 2f) return
        val rad = minOf(w / 1.7320508f, h / 2f) - sw
        if (rad <= 0f) return
        hexPath(path, bd.exactCenterX(), bd.exactCenterY(), rad)
        val shader = LinearGradient(bd.left.toFloat(), bd.top.toFloat(), bd.right.toFloat(), bd.bottom.toFloat(), a, b, Shader.TileMode.CLAMP)
        if (filled) { p.style = Paint.Style.FILL; p.shader = shader; c.drawPath(path, p) }
        if (sw > 0f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeJoin = Paint.Join.ROUND
            if (filled) { p.shader = null; p.color = outline } else p.shader = shader
            c.drawPath(path, p)
        }
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(cf: ColorFilter?) { }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// The app logo, drawn from its shapes (coordinates of the 432 px icon artwork): a gradient hexagon, the green triangle
// and the black line. The outline colour is a parameter so it stays visible on dark and on light backgrounds.
class LogoDrawable(private val ink: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(c: Canvas) {
        val bd = bounds
        val sc = minOf(bd.width() / 254f, bd.height() / 172f)
        if (sc <= 0f) return
        val ox = bd.exactCenterX() - 254f * sc / 2f; val oy = bd.exactCenterY() - 172f * sc / 2f
        fun x(v: Float) = ox + (v - 89f) * sc
        fun y(v: Float) = oy + (v - 130f) * sc
        // green triangle
        path.reset(); path.moveTo(x(160f), y(131f)); path.lineTo(x(208f), y(185f)); path.lineTo(x(208f), y(262f)); path.close()
        p.style = Paint.Style.FILL; p.shader = null; p.color = Theme.GREEN
        c.drawPath(path, p)
        // hexagon
        path.reset()
        val hx = floatArrayOf(274f, 340f, 340f, 274f, 208f, 208f); val hy = floatArrayOf(147f, 185f, 262f, 300f, 262f, 185f)
        for (i in 0 until 6) { if (i == 0) path.moveTo(x(hx[i]), y(hy[i])) else path.lineTo(x(hx[i]), y(hy[i])) }
        path.close()
        p.shader = LinearGradient(x(330f), y(150f), x(225f), y(295f), 0xFFD048F0.toInt(), 0xFFFFA0DB.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        // lines
        p.shader = null; p.color = ink; p.style = Paint.Style.STROKE; p.strokeWidth = 5f * sc; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
        c.drawPath(path, p)
        c.drawLine(x(107f), y(131f), x(160f), y(131f), p)
        c.drawLine(x(91f), y(131f), x(97f), y(131f), p)
        c.drawLine(x(160f), y(131f), x(208f), y(185f), p)
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(cf: ColorFilter?) { }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}

// The top bar and the menu header: a gradient with a few translucent hexagons and the green corner triangle.
class BarDrawable(private val a: Int, private val b: Int, private val d: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(c: Canvas) {
        val bd = bounds
        val w = bd.width().toFloat(); val h = bd.height().toFloat()
        if (w < 2f || h < 2f) return
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, w, h, a, b, Shader.TileMode.CLAMP)
        c.drawRect(bd, p)
        c.save(); c.clipRect(bd)
        p.shader = null
        // big hexagons on the right, like the logo's shape repeated
        hexPath(path, w * 0.93f, h * 0.15f, h * 0.95f)
        p.style = Paint.Style.FILL; p.color = 0x14FFFFFF
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0x30FFFFFF
        c.drawPath(path, p)
        hexPath(path, w * 0.72f, h * 1.05f, h * 0.6f)
        c.drawPath(path, p)
        // the green triangle, bottom right
        val t = minOf(h * 0.55f, 40f * d)
        path.reset(); path.moveTo(w, h - t); path.lineTo(w, h); path.lineTo(w - t * 1.3f, h); path.close()
        p.style = Paint.Style.FILL; p.color = Theme.alpha(Theme.GREEN, 0xB0)
        c.drawPath(path, p)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(cf: ColorFilter?) { }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

// The page background: the plain colour with a few huge, very faint hexagon outlines in the corners.
class BackdropDrawable(private val base: Int, private val d: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(c: Canvas) {
        val bd = bounds
        val w = bd.width().toFloat(); val h = bd.height().toFloat()
        if (w < 2f || h < 2f) return
        p.style = Paint.Style.FILL; p.shader = null; p.color = base
        c.drawRect(bd, p)
        val strong = if (Theme.light) 0x22 else 0x2C
        val soft = if (Theme.light) 0x0E else 0x14
        hexPath(path, w * 1.0f, h * 0.18f, w * 0.55f)
        p.style = Paint.Style.FILL; p.color = Theme.alpha(Theme.MAGENTA, soft); c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
        p.shader = LinearGradient(0f, 0f, w, h * 0.5f, Theme.alpha(Theme.MAGENTA, strong), Theme.alpha(Theme.PINK, strong / 2), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        hexPath(path, w * 0.02f, h * 0.88f, w * 0.62f)
        p.style = Paint.Style.FILL; p.color = Theme.alpha(Theme.VIOLET, soft); c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.shader = LinearGradient(0f, h, w * 0.7f, h * 0.5f, Theme.alpha(Theme.CYAN, strong), Theme.alpha(Theme.VIOLET, strong / 2), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        // the green triangle, small, bottom right
        val t = 90f * d
        path.reset(); path.moveTo(w, h - t); path.lineTo(w, h); path.lineTo(w - t * 1.3f, h); path.close()
        p.style = Paint.Style.FILL; p.color = Theme.alpha(Theme.GREEN, if (Theme.light) 0x30 else 0x28)
        c.drawPath(path, p)
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(cf: ColorFilter?) { }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
