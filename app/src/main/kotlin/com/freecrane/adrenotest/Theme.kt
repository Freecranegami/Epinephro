package com.freecrane.adrenotest

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider

// The look of the app: Material 3, a clean dark theme (default) and a clean light one (colour roles in res/values/themes.xml). Light mode is one global switch (the
// menu); it is stored in the defaults file and never per game. The only colour is on the switch cards: each section
// has its own gradient pair, taken from the logo's colours.
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

    // ----- surfaces and text (the dark ones are the app's original colours) -----
    val bg: Int get() = if (light) 0xFFF2F3F7.toInt() else 0xFF101318.toInt()
    val bar: Int get() = if (light) 0xFFFFFFFF.toInt() else 0xFF171B22.toInt()
    val card: Int get() = if (light) 0xFFFFFFFF.toInt() else 0xFF1C212A.toInt()
    val inset: Int get() = if (light) 0xFFE9EBF1.toInt() else 0xFF171B22.toInt()
    val text: Int get() = if (light) 0xFF1B1E26.toInt() else 0xFFEDEFF3.toInt()
    val sub: Int get() = if (light) 0xFF6B7280.toInt() else 0xFF9AA3B2.toInt()

    // the selected entry of the menu (Material 3 "active indicator")
    val container: Int get() = if (light) 0xFFF1DCFA.toInt() else 0xFF4A2A5E.toInt()
    val onContainer: Int get() = if (light) 0xFF36004D.toInt() else 0xFFF6D8FF.toInt()

    private val pairs: List<Pair<Int, Int>> = listOf(MAGENTA to PINK, VIOLET to MAGENTA, PINK to ORANGE, GREEN to CYAN,
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

    // readable heading text in a section's colour
    fun accentText(p: Pair<Int, Int>): Int = if (light) mix(p.first, Color.BLACK, 0.32f) else mix(p.first, Color.WHITE, 0.2f)

    // The card behind a switch: the card colour with the section's gradient washed over it, stronger while the switch is on.
    fun toggleBg(p: Pair<Int, Int>, on: Boolean, radius: Float): GradientDrawable {
        val s = if (on) (if (light) 0.40f else 0.50f) else (if (light) 0.16f else 0.20f)
        return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(mix(card, p.first, s), mix(card, p.second, s * 0.8f))).apply { cornerRadius = radius }
    }
}

// A Material 3 slider that behaves like the old SeekBar: whole numbers from 0 to max, `progress`, a change callback and a
// callback for when the finger is lifted. The value is shown in a text above it, so the slider's own label stays off.
class M3Seek(ctx: Context, max: Int, start: Int, onChange: (Int) -> Unit) : Slider(ctx) {
    var onStop: ((Int) -> Unit)? = null
    init {
        valueFrom = 0f
        valueTo = maxOf(1, max).toFloat()
        stepSize = 1f
        value = start.coerceIn(0, maxOf(1, max)).toFloat()
        isTickVisible = false
        labelBehavior = LabelFormatter.LABEL_GONE
        addOnChangeListener { _, v, _ -> onChange(v.toInt()) }
        addOnSliderTouchListener(object : OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { }
            override fun onStopTrackingTouch(slider: Slider) { onStop?.invoke(slider.value.toInt()) }
        })
    }
    var progress: Int
        get() = value.toInt()
        set(v) { value = v.toFloat() }
}
