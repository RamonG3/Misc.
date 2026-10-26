package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.ramong.runelauncher.core.MenuOption
import kotlin.math.max

/**
 * The right-click "Choose Option" menu, drawn the way the old client drew it:
 * a #5D5447 box, a black title bar, white options, yellow when touched.
 */
@SuppressLint("ViewConstructor")
class MenuView(context: Context) : View(context) {
    private var options: List<MenuOption> = emptyList()
    private val box = RectF()
    private var rowH = 0f
    private var headH = 0f
    private var pressed = -1
    private val textPaint = Rs.paint(14f)
    private val headPaint = Rs.paint(13f, bold = true)

    val showing: Boolean get() = visibility == VISIBLE

    fun show(opts: List<MenuOption>, x: Float, y: Float) {
        options = opts + MenuOption("Cancel")
        rowH = Rs.dp(38f)
        headH = Rs.dp(26f)
        val w = max(Rs.dp(180f), options.maxOf { textPaint.measureText(it.verb + " " + (it.target ?: "")) } + Rs.dp(28f))
        val h = headH + options.size * rowH + Rs.dp(4f)
        val pw = (parent as? View)?.width?.toFloat() ?: width.toFloat()
        val ph = (parent as? View)?.height?.toFloat() ?: height.toFloat()
        val l = (x - w / 2f).coerceIn(Rs.dp(4f), max(Rs.dp(4f), pw - w - Rs.dp(4f)))
        val t = (y - headH / 2f).coerceIn(Rs.dp(28f), max(Rs.dp(28f), ph - h - Rs.dp(8f)))
        box.set(l, t, l + w, t + h)
        pressed = -1
        visibility = VISIBLE
        invalidate()
    }

    fun hide(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = INVISIBLE
        return true
    }

    private fun rowAt(x: Float, y: Float): Int {
        if (!box.contains(x, y) || y < box.top + headH) return -1
        val i = ((y - box.top - headH) / rowH).toInt()
        return if (i in options.indices) i else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!box.contains(event.x, event.y)) {
                    hide()
                    return true
                }
                pressed = rowAt(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> pressed = rowAt(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                val i = rowAt(event.x, event.y)
                if (i >= 0) {
                    val o = options[i]
                    hide()
                    o.action()
                }
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> pressed = -1
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        if (!showing) return
        Rs.rect(c, box, Rs.MENU_BG)
        Rs.rect(c, box.left + Rs.px, box.top + Rs.px, box.right - Rs.px, box.top + headH, Rs.BLACK)
        Rs.text(c, "Choose Option", box.left + Rs.dp(6f), Rs.baseline(headPaint, box.top, box.top + headH), headPaint, Rs.MENU_BG, shadow = false)
        Rs.outline(c, RectF(box.left + Rs.px, box.top + headH + Rs.px, box.right - Rs.px, box.bottom - Rs.px), Rs.BLACK)
        options.forEachIndexed { i, o ->
            val top = box.top + headH + i * rowH
            val y = Rs.baseline(textPaint, top, top + rowH)
            val x = box.left + Rs.dp(8f)
            Rs.text(c, o.verb, x, y, textPaint, if (i == pressed) Rs.YELLOW else Rs.WHITE)
            o.target?.let {
                Rs.text(c, it, x + textPaint.measureText(o.verb + " "), y, textPaint, o.targetColor)
            }
        }
    }
}
