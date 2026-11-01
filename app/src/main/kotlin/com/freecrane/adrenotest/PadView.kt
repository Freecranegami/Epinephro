package com.freecrane.adrenotest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// The on-screen Xbox 360 controller. In play mode it draws the controls, follows several fingers at once and reports the
// controller state to PadOut; touches that miss every control are left to the game. In edit mode the controls can be
// dragged, resized and hidden.
class PadView(ctx: Context, private val edit: Boolean, private val out: PadOut?) : View(ctx) {
    private val app: Context = HostApp.instance
    private val els = Pad.load(app)
    private val bound = HashMap<Int, Pad.El>()
    private val pressed = HashSet<String>()
    private val sx = HashMap<String, Float>()
    private val sy = HashMap<String, Float>()
    private var hatX = 0f
    private var hatY = 0f
    private var selected: Pad.El? = null
    private var dragDx = 0f
    private var dragDy = 0f
    private var opacity = Pad.opacity(app)
    private val haptic = Pad.haptics(app)
    var onDone: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val tmp = RectF()
    private val dirty = RectF()
    private var dirtyAny = false
    private fun mark(e: Pad.El) {
        val r = rad(e) * 1.5f
        tmp.set(cx(e) - r, cy(e) - r, cx(e) + r, cy(e) + r)
        if (dirtyAny) dirty.union(tmp) else dirty.set(tmp)
        dirtyAny = true
    }

    init { isFocusable = false; isFocusableInTouchMode = false }

    private fun unit() = min(width, height).toFloat()
    private fun cx(e: Pad.El) = e.x * width
    private fun cy(e: Pad.El) = e.y * height
    private fun rad(e: Pad.El) = e.size * unit() / 2f

    private fun hit(e: Pad.El, px: Float, py: Float): Boolean {
        val dx = px - cx(e); val dy = py - cy(e)
        return if (e.kind == "shoulder") abs(dx) <= rad(e) * 1.1f && abs(dy) <= rad(e) * 0.62f
        else hypot(dx, dy) <= rad(e) * (if (e.kind == "small") 1.35f else 1.15f)
    }

    // ---- drawing ----

    private val NAVY = 0xFF0F1826.toInt()
    private val NAVY_ON = 0xFF2F4F82.toInt()
    private val GLOW = 0xFF4A6FA8.toInt()

    private fun body(c: Canvas, a: Float, on: Boolean, draw: (Paint) -> Unit) {
        // soft outer glow, dark body, thin light edge
        line.style = Paint.Style.STROKE
        line.color = GLOW; line.alpha = (255 * a * 0.22f).toInt(); line.strokeWidth = unit() * 0.016f
        drawShape(c, line, draw)
        fill.color = if (on) NAVY_ON else NAVY; fill.alpha = (255 * a * 0.88f).toInt()
        drawShape(c, fill, draw)
        line.color = GLOW; line.alpha = (255 * a * 0.7f).toInt(); line.strokeWidth = unit() * 0.004f + 1f
        drawShape(c, line, draw)
    }
    private fun drawShape(c: Canvas, p: Paint, draw: (Paint) -> Unit) { draw(p) }

    override fun onDraw(c: Canvas) {
        val a = opacity / 100f
        if (edit) {
            c.drawColor(0xFF05080D.toInt())
            drawGrid(c)
        }
        for (e in els) {
            if (!e.vis && !edit) continue
            val on = pressed.contains(e.id)
            val dim = if (e.vis) 1f else 0.3f
            val al = a * dim
            val x = cx(e); val y = cy(e); val r = rad(e)
            when (e.kind) {
                "stick" -> {
                    body(c, al, false) { p -> c.drawCircle(x, y, r, p) }
                    val kx = x + (sx[e.id] ?: 0f) * r * 0.55f; val ky = y + (sy[e.id] ?: 0f) * r * 0.55f
                    fill.color = 0xFF16233A.toInt(); fill.alpha = (255 * al * 0.95f).toInt()
                    c.drawCircle(kx, ky, r * 0.42f, fill)
                    line.color = 0xFFFFFFFF.toInt(); line.alpha = (255 * al).toInt(); line.strokeWidth = unit() * 0.004f + 1.5f
                    c.drawCircle(kx, ky, r * 0.54f, line)
                    line.color = GLOW; line.alpha = (255 * al * 0.5f).toInt(); line.strokeWidth = unit() * 0.003f + 1f
                    c.drawCircle(kx, ky, r * 0.42f, line)
                }
                "dpad" -> {
                    val q = r * 0.34f; val o = r * 0.78f
                    for (d in 0 until 4) {
                        val ox = if (d == 0) -o else if (d == 1) o else 0f
                        val oy = if (d == 2) -o else if (d == 3) o else 0f
                        val lit = (d == 0 && hatX < 0) || (d == 1 && hatX > 0) || (d == 2 && hatY < 0) || (d == 3 && hatY > 0)
                        tmp.set(x + ox - q, y + oy - q, x + ox + q, y + oy + q)
                        body(c, al, lit) { p -> c.drawRoundRect(tmp, q * 0.35f, q * 0.35f, p) }
                        // arrow
                        val t = q * 0.5f
                        arrow.reset()
                        when (d) {
                            0 -> { arrow.moveTo(x + ox - t, y); arrow.lineTo(x + ox + t * 0.6f, y - t); arrow.lineTo(x + ox + t * 0.6f, y + t) }
                            1 -> { arrow.moveTo(x + ox + t, y); arrow.lineTo(x + ox - t * 0.6f, y - t); arrow.lineTo(x + ox - t * 0.6f, y + t) }
                            2 -> { arrow.moveTo(x, y + oy - t); arrow.lineTo(x - t, y + oy + t * 0.6f); arrow.lineTo(x + t, y + oy + t * 0.6f) }
                            else -> { arrow.moveTo(x, y + oy + t); arrow.lineTo(x - t, y + oy - t * 0.6f); arrow.lineTo(x + t, y + oy - t * 0.6f) }
                        }
                        arrow.close()
                        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (255 * al).toInt()
                        c.drawPath(arrow, fill)
                    }
                }
                "shoulder" -> {
                    tmp.set(x - r, y - r * 0.5f, x + r, y + r * 0.5f)
                    body(c, al, on) { p -> c.drawRoundRect(tmp, r * 0.5f, r * 0.5f, p) }
                    label(c, e, x, y, r * 0.5f, al)
                }
                else -> {
                    if (e.id == "back" || e.id == "start" || e.id == "guide") {
                        tmp.set(x - r * 1.3f, y - r * 0.8f, x + r * 1.3f, y + r * 0.8f)
                        body(c, al, on) { p -> c.drawRoundRect(tmp, r * 0.8f, r * 0.8f, p) }
                        icon(c, e.id, x, y, r * 0.55f, al)
                    } else {
                        body(c, al, on) { p -> c.drawCircle(x, y, r, p) }
                        label(c, e, x, y, if (e.kind == "small") r * 0.5f else r * 0.9f, al)
                    }
                }
            }
            if (edit && e === selected) {
                line.color = 0xFF4FC3F7.toInt(); line.alpha = 255; line.strokeWidth = unit() * 0.005f + 2f
                val w = if (e.kind == "shoulder") r * 1.15f else if (e.kind == "dpad") r * 1.3f else r * 1.25f
                val h = if (e.kind == "shoulder") r * 0.62f else w
                tmp.set(x - w, y - h, x + w, y + h)
                c.drawRoundRect(tmp, w * 0.2f, w * 0.2f, line)
            }
        }
        if (edit) drawBar(c)
    }

    private val arrow = android.graphics.Path()

    private fun icon(c: Canvas, id: String, x: Float, y: Float, s: Float, a: Float) {
        line.color = 0xFFFFFFFF.toInt(); line.alpha = (255 * a).toInt(); line.strokeWidth = unit() * 0.006f + 1.5f
        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (255 * a).toInt()
        when (id) {
            "back" -> {
                tmp.set(x - s, y - s * 0.7f, x + s * 0.35f, y + s * 0.35f); c.drawRoundRect(tmp, s * 0.15f, s * 0.15f, line)
                tmp.set(x - s * 0.35f, y - s * 0.35f, x + s, y + s * 0.7f); c.drawRoundRect(tmp, s * 0.15f, s * 0.15f, line)
            }
            "start" -> for (i in -1..1) c.drawLine(x - s * 0.9f, y + i * s * 0.55f, x + s * 0.9f, y + i * s * 0.55f, line)
            else -> { txt.color = 0xFFFFFFFF.toInt(); txt.alpha = (255 * a).toInt(); txt.textSize = s * 1.1f; c.drawText("XBOX", x, y + s * 0.38f, txt) }
        }
    }

    private fun label(c: Canvas, e: Pad.El, x: Float, y: Float, size: Float, a: Float) {
        if (e.label.isEmpty()) return
        // a few labels follow the style of the reference: LT/RT/LB/RB shown as L2/R2/L1/R1
        val t = when (e.id) { "lt" -> "L2"; "rt" -> "R2"; "lb" -> "L1"; "rb" -> "R1"; else -> e.label }
        txt.color = 0xFFFFFFFF.toInt(); txt.alpha = (255 * a).toInt(); txt.textSize = size
        c.drawText(t, x, y + size * 0.35f, txt)
    }

    // ---- grid (editor) ----

    private fun step() = max(8f, unit() / 24f)

    private fun drawGrid(c: Canvas) {
        val st = step()
        line.style = Paint.Style.STROKE
        line.color = 0xFF1B2433.toInt(); line.alpha = 255; line.strokeWidth = 1f
        var gx = 0f
        while (gx <= width) { c.drawLine(gx, 0f, gx, height.toFloat(), line); gx += st }
        var gy = 0f
        while (gy <= height) { c.drawLine(0f, gy, width.toFloat(), gy, line); gy += st }
        line.color = 0xFF2A3A55.toInt()
        line.strokeWidth = 2f
        c.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), line)
        c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, line)
    }

    // ---- edit bar (movable) ----

    private val barLabels = arrayOf("Smaller", "Bigger", "Hide / show", "Snap", "Reset all", "Done")
    private var barX = -1f
    private var barY = -1f
    private var barDragDx = 0f
    private var barDragDy = 0f
    private var barDrag = false
    private var snap = Prefs.get(app).getBoolean("pad_snap", true)

    private fun barH() = unit() * 0.14f
    private fun handleW() = unit() * 0.34f
    private fun btnW() = unit() * 0.2f
    private fun barW() = handleW() + barLabels.size * btnW()

    private fun ensureBar() {
        if (barX >= 0f) return
        val px = Prefs.get(app).getFloat("pad_bar_x", -1f); val py = Prefs.get(app).getFloat("pad_bar_y", -1f)
        barX = if (px >= 0f) px * width else (width - barW()) / 2f
        barY = if (py >= 0f) py * height else unit() * 0.03f
        clampBar()
    }
    private fun clampBar() {
        barX = barX.coerceIn(0f, max(0f, width - barW()))
        barY = barY.coerceIn(0f, max(0f, height - barH()))
    }
    private fun handleRect(): RectF { ensureBar(); return RectF(barX, barY, barX + handleW(), barY + barH()) }
    private fun barRect(i: Int): RectF { ensureBar(); val l = barX + handleW() + i * btnW(); return RectF(l, barY, l + btnW(), barY + barH()) }

    private fun drawBar(c: Canvas) {
        ensureBar()
        val all = RectF(barX, barY, barX + barW(), barY + barH())
        fill.color = 0xFF0A0F18.toInt(); fill.alpha = 245; c.drawRoundRect(all, 22f, 22f, fill)
        line.style = Paint.Style.STROKE; line.color = 0xFF2A3A55.toInt(); line.alpha = 255; line.strokeWidth = 3f
        c.drawRoundRect(all, 22f, 22f, line)
        val h = handleRect()
        txt.textAlign = Paint.Align.LEFT; txt.color = 0xFFFFFFFF.toInt(); txt.alpha = 255; txt.textSize = h.height() * 0.28f
        c.drawText("Virtual Gamepad", h.left + h.height() * 0.3f, h.centerY() - h.height() * 0.02f, txt)
        txt.isFakeBoldText = false; txt.color = 0xFF8FA3C0.toInt(); txt.textSize = h.height() * 0.2f
        c.drawText("drag here to move", h.left + h.height() * 0.3f, h.centerY() + h.height() * 0.3f, txt)
        txt.isFakeBoldText = true; txt.textAlign = Paint.Align.CENTER
        // grip dots
        fill.color = 0xFF3A4A66.toInt(); fill.alpha = 255
        for (k in 0 until 3) c.drawCircle(h.right - h.height() * 0.25f, h.centerY() + (k - 1) * h.height() * 0.2f, 4f, fill)
        line.strokeWidth = 2f; line.color = 0xFF1E2A3E.toInt()
        c.drawLine(h.right, h.top + 10f, h.right, h.bottom - 10f, line)
        for (i in barLabels.indices) {
            val r = barRect(i)
            val lab = if (i == 3) (if (snap) "Snap: on" else "Snap: off") else barLabels[i]
            txt.color = if (i == 5) 0xFF66BB6A.toInt() else if (i == 3 && !snap) 0xFF8FA3C0.toInt() else 0xFFFFFFFF.toInt()
            txt.alpha = 255; txt.textSize = r.height() * 0.24f
            c.drawText(lab, r.centerX(), r.centerY() + r.height() * 0.09f, txt)
            if (i < barLabels.size - 1) c.drawLine(r.right, r.top + 14f, r.right, r.bottom - 14f, line)
        }
    }

    private fun barTap(px: Float, py: Float): Boolean {
        ensureBar()
        if (handleRect().contains(px, py)) { barDrag = true; barDragDx = barX - px; barDragDy = barY - py; return true }
        for (i in barLabels.indices) {
            if (!barRect(i).contains(px, py)) continue
            val s = selected
            when (i) {
                0 -> if (s != null) s.size = max(0.04f, s.size * 0.9f)
                1 -> if (s != null) s.size = min(0.8f, s.size * 1.1f)
                2 -> if (s != null) s.vis = !s.vis
                3 -> { snap = !snap; Prefs.get(app).edit().putBoolean("pad_snap", snap).commit() }
                4 -> { Pad.resetLayout(app); els.clear(); els.addAll(Pad.defaults()); selected = null }
                5 -> { save(); onDone?.invoke() }
            }
            invalidate()
            return true
        }
        // anywhere else on the panel: swallow the touch
        return RectF(barX, barY, barX + barW(), barY + barH()).contains(px, py)
    }

    fun save() {
        Pad.save(app, els)
        if (barX >= 0f && width > 0) Prefs.get(app).edit().putFloat("pad_bar_x", barX / width).putFloat("pad_bar_y", barY / height).commit()
    }

    // ---- touches ----

    private fun hitTop(px: Float, py: Float): Pad.El? {
        for (i in els.indices.reversed()) {
            val e = els[i]
            if ((e.vis || edit) && hit(e, px, py)) return e
        }
        return null
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (edit) return editTouch(ev)
        var took = false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val e = hitTop(ev.getX(i), ev.getY(i))
                if (e != null) {
                    bound[ev.getPointerId(i)] = e
                    pressEl(e, ev.getX(i), ev.getY(i), true)
                    took = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val e = bound[ev.getPointerId(i)] ?: continue
                    pressEl(e, ev.getX(i), ev.getY(i), false)
                }
                took = bound.isNotEmpty()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                val e = bound.remove(ev.getPointerId(i))
                if (e != null) { releaseEl(e); took = true }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (e in bound.values.toList()) releaseEl(e)
                bound.clear()
            }
        }
        push()
        // Redraw only the controls that were touched: a full-screen redraw on every touch sample is costly next to the game.
        if (dirtyAny) { dirtyAny = false; invalidate(dirty.left.toInt() - 2, dirty.top.toInt() - 2, dirty.right.toInt() + 2, dirty.bottom.toInt() + 2) }
        return true          // the controller covers the whole screen: the game gets no touches while it is on
    }

    private fun pressEl(e: Pad.El, px: Float, py: Float, first: Boolean) {
        mark(e)
        when (e.kind) {
            "stick" -> {
                val r = rad(e)
                var dx = (px - cx(e)) / r; var dy = (py - cy(e)) / r
                val m = hypot(dx, dy)
                if (m > 1f) { dx /= m; dy /= m }
                sx[e.id] = dx; sy[e.id] = dy
            }
            "dpad" -> {
                val r = rad(e)
                val dx = (px - cx(e)) / r; val dy = (py - cy(e)) / r
                hatX = if (dx > 0.28f) 1f else if (dx < -0.28f) -1f else 0f
                hatY = if (dy > 0.28f) 1f else if (dy < -0.28f) -1f else 0f
            }
            else -> if (pressed.add(e.id) && first && haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun releaseEl(e: Pad.El) {
        mark(e)
        when (e.kind) {
            "stick" -> { sx[e.id] = 0f; sy[e.id] = 0f }
            "dpad" -> { hatX = 0f; hatY = 0f }
            else -> pressed.remove(e.id)
        }
    }

    private fun push() {
        out?.update(pressed, sx["ls"] ?: 0f, sy["ls"] ?: 0f, sx["rs"] ?: 0f, sy["rs"] ?: 0f, hatX, hatY)
    }

    private fun snapped(v: Float): Float = if (snap) Math.round(v / step()) * step() else v

    private fun editTouch(ev: MotionEvent): Boolean {
        val px = ev.x; val py = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                barDrag = false
                if (barTap(px, py)) return true
                val e = hitTop(px, py)
                selected = e
                if (e != null) { dragDx = cx(e) - px; dragDy = cy(e) - py }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (barDrag) {
                    barX = snapped(px + barDragDx); barY = snapped(py + barDragDy); clampBar(); invalidate()
                    return true
                }
                val e = selected
                if (e != null && width > 0 && height > 0) {
                    e.x = (snapped(px + dragDx) / width).coerceIn(0.02f, 0.98f)
                    e.y = (snapped(py + dragDy) / height).coerceIn(0.02f, 0.98f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (barDrag) { barDrag = false; save() } }
        }
        return true
    }
}
