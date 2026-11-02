package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ramong.cyberlauncher.core.AppEntry
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Rarity
import com.ramong.cyberlauncher.ui.panels.CharacterPanel
import com.ramong.cyberlauncher.ui.panels.CyberwarePanel
import com.ramong.cyberlauncher.ui.panels.HacksPanel
import com.ramong.cyberlauncher.ui.panels.InventoryPanel
import com.ramong.cyberlauncher.ui.panels.JournalPanel
import com.ramong.cyberlauncher.ui.panels.PhonePanel
import com.ramong.cyberlauncher.ui.panels.RadioPanel
import com.ramong.cyberlauncher.ui.panels.SettingsPanel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Base class for a tab. Coordinates are in content space (already scrolled). */
abstract class Panel(protected val game: Game, protected val host: PanelView) {
    abstract val title: String
    abstract val glyph: Glyph
    open val scrollable: Boolean get() = false
    open val animates: Boolean get() = false
    open fun contentHeight(r: RectF): Float = r.height()
    open fun info(): String = ""
    abstract fun draw(c: Canvas, r: RectF)
    open fun tap(x: Float, y: Float, r: RectF) {}
    open fun longPress(x: Float, y: Float, r: RectF) {}

    /** Menu anchor in root-layout coordinates for a point in content space. */
    protected fun menuX(x: Float) = host.left + x
    protected fun menuY(y: Float) = host.top + y - host.scrollOf(this)

    protected val label = Cy.paint(13.5f, Cy.Face.BOLD, spacing = 0.04f)
    protected val sub = Cy.paint(11.5f, Cy.Face.BODY)
    protected val mono = Cy.paint(10f, Cy.Face.MONO, spacing = 0.06f)
    protected val monoRight = Cy.paint(10f, Cy.Face.MONO, Paint.Align.RIGHT, 0.06f)
    protected val section = Cy.paint(9f, Cy.Face.MONO, spacing = 0.16f)

    /** A small section heading with a rule; returns the y below it. */
    protected fun heading(c: Canvas, r: RectF, y: Float, text: String): Float {
        val by = y + Cy.dp(14f)
        Cy.text(c, text, r.left + Cy.dp(2f), by, section, Cy.dim)
        val x = r.left + Cy.dp(8f) + section.measureText(text)
        Cy.line(c, x, by - Cy.dp(3.5f), r.right, by - Cy.dp(3.5f), Cy.faint)
        return by + Cy.dp(6f)
    }
}

/** Shared drawing for an app shown as an item: icon, rarity glow, label. */
object ItemDraw {
    private val filtered = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint()
    private val labelPaint = Cy.paint(11f, Cy.Face.BODY, Paint.Align.CENTER)
    private val letter = Cy.paint(16f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val dst = RectF()
    private val path = Path()
    private val tmp = RectF()

    fun icon(c: Canvas, game: Game, app: AppEntry, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val bmp: Bitmap? = game.icons.get(app)
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        if (bmp == null) {
            Cy.cut(path, dst, size * 0.22f, Cy.TR or Cy.BL)
            glow.shader = null
            glow.style = Paint.Style.STROKE
            glow.strokeWidth = Cy.px
            glow.color = Cy.withAlpha(Cy.primary, alpha / 2)
            c.drawPath(path, glow)
            glow.style = Paint.Style.FILL
            letter.textSize = size * 0.45f
            Cy.text(c, app.label.take(1).uppercase(), cx, cy + size * 0.16f, letter, Cy.withAlpha(Cy.primary, alpha))
            return
        }
        filtered.alpha = alpha
        c.drawBitmap(bmp, null, dst, filtered)
        filtered.alpha = 255
    }

    /** A slot tile. [app] null draws an empty slot. */
    fun tile(c: Canvas, game: Game, app: AppEntry?, r: RectF, pressed: Boolean, labels: Boolean, emptyHint: Boolean = true) {
        val cut = min(Cy.dp(8f), r.width() * 0.16f)
        if (app == null) {
            Cy.panel(c, r, cut, Cy.withAlpha(Cy.bg, 0x70), if (pressed) Cy.accent else Cy.withAlpha(Cy.primary, 0x30), Cy.TR, ticks = false)
            if (emptyHint) Glyphs.draw(c, Glyph.PLUS, r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.24f, Cy.withAlpha(Cy.primary, if (pressed) 0xC0 else 0x38), 1.4f)
            return
        }
        val rarity = game.rarity(app)
        val rc = rarity.color
        val high = rarity.ordinal >= Rarity.EPIC.ordinal
        Cy.panel(c, r, cut, Cy.panelLight, if (pressed) Cy.accent else if (high) Cy.withAlpha(rc, 0xB0) else Cy.withAlpha(Cy.primary, 0x40), Cy.TR, ticks = false)
        glow.style = Paint.Style.FILL
        glow.shader = LinearGradient(0f, r.bottom, 0f, r.top + r.height() * 0.35f, Cy.withAlpha(rc, if (high) 0x58 else 0x34), 0, Shader.TileMode.CLAMP)
        tmp.set(r.left + Cy.px, r.top + Cy.px, r.right - Cy.px, r.bottom - Cy.px)
        c.drawRect(tmp, glow)
        glow.shader = null
        Cy.rect(c, r.left + Cy.px, r.bottom - Cy.dp(2.2f), r.right - cut * 0.2f, r.bottom - Cy.px, rc)
        if (rarity == Rarity.ICONIC) {
            Cy.brackets(c, RectF(r.left + Cy.dp(3f), r.top + Cy.dp(3f), r.right - Cy.dp(3f), r.bottom - Cy.dp(4.5f)), rc, Cy.dp(5f), Cy.px)
        } else if (rarity.ordinal >= Rarity.LEGENDARY.ordinal) {
            path.reset()
            path.moveTo(r.left + Cy.px, r.top + Cy.px)
            path.lineTo(r.left + Cy.dp(9f), r.top + Cy.px)
            path.lineTo(r.left + Cy.px, r.top + Cy.dp(9f))
            path.close()
            glow.color = rc
            c.drawPath(path, glow)
        }
        val showLabel = labels && r.height() > Cy.dp(52f)
        val size = min(r.width(), r.height()) * if (showLabel) 0.5f else 0.62f
        val cy = if (showLabel) r.top + (r.height() - Cy.dp(14f)) * 0.5f + Cy.dp(1f) else r.centerY()
        icon(c, game, app, r.centerX(), cy, size)
        if (pressed) Cy.rect(c, r, Cy.withAlpha(Cy.accent, 0x26))
        if (showLabel) {
            labelPaint.textSize = Cy.dp(if (r.width() < Cy.dp(70f)) 10f else 11f)
            Cy.text(c, Cy.ellipsize(app.label, labelPaint, r.width() - Cy.dp(8f)), r.centerX(), r.bottom - Cy.dp(6.5f), labelPaint, Cy.TEXT)
        }
    }
}

/** The bottom panel: the active tab's contents with a header, and the tab bar underneath. */
@SuppressLint("ViewConstructor")
class PanelView(context: Context, val game: Game) : View(context) {
    var tab = Game.TAB_INVENTORY
        private set
    val panels: List<Panel> = listOf(
        InventoryPanel(game, this), CharacterPanel(game, this), CyberwarePanel(game, this), HacksPanel(game, this),
        JournalPanel(game, this), PhonePanel(game, this), RadioPanel(game, this), SettingsPanel(game, this),
    )
    private val tabRects = Array(panels.size) { RectF() }
    private val bounds = RectF()
    private val header = RectF()
    private val tabBar = RectF()
    val content = RectF()
    private val panelRect = RectF()
    private val scrollY = FloatArray(panels.size)
    private val scroller = OverScroller(context)
    private var pressedTab = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tabChanged = 0L
    private var tabDir = 0
    private var swiping = false
    private var swipeDx = 0f

    private val titlePaint = Cy.paint(17f, Cy.Face.BOLD, spacing = 0.12f)
    private val infoPaint = Cy.paint(10f, Cy.Face.MONO, Paint.Align.RIGHT, 0.08f)
    private val hintPaint = Cy.paint(8f, Cy.Face.MONO, Paint.Align.CENTER, 0.08f)
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    class Drag(val slot: Int, var x: Float, var y: Float, val startX: Float, val startY: Float) {
        var moved = false
    }

    var drag: Drag? = null
        private set

    private val inventory: InventoryPanel get() = panels[Game.TAB_INVENTORY] as InventoryPanel
    private val panel: Panel get() = panels[tab]

    fun select(i: Int) {
        val t = i.coerceIn(0, panels.size - 1)
        if (t != tab) {
            tabDir = if (t > tab) 1 else -1
            tab = t
            tabChanged = SystemClock.uptimeMillis()
            scroller.forceFinished(true)
        }
        invalidate()
    }

    fun scrollOf(p: Panel): Float = if (panels[tab] === p) scrollY[tab] else 0f

    /** Screen rectangle of a content-space rect (for launch animations). */
    fun screenRect(r: RectF): Rect {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val sy = scrollY[tab]
        return Rect((loc[0] + r.left).toInt(), (loc[1] + r.top - sy).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom - sy).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val m = Cy.dp(8f)
        bounds.set(m, Cy.dp(2f), w - m, h.toFloat() - Cy.dp(2f))
        val tabH = Cy.dp(50f)
        // Inset so the selected-tab highlight stays inside the chamfered corner.
        tabBar.set(bounds.left + Cy.dp(10f), bounds.bottom - tabH, bounds.right - Cy.dp(10f), bounds.bottom)
        header.set(bounds.left, bounds.top, bounds.right, bounds.top + Cy.dp(34f))
        content.set(bounds.left + Cy.dp(8f), header.bottom + Cy.dp(4f), bounds.right - Cy.dp(8f), tabBar.top - Cy.dp(6f))
        val tw = tabBar.width() / panels.size
        for (i in panels.indices) tabRects[i].set(tabBar.left + i * tw, tabBar.top, tabBar.left + (i + 1) * tw, tabBar.bottom)
    }

    private fun panelRect(p: Panel): RectF {
        panelRect.set(content)
        if (p.scrollable && maxScroll(p) > 0f) panelRect.right -= Cy.dp(8f)
        return panelRect
    }

    private fun maxScroll(p: Panel): Float {
        panelRect.set(content)
        panelRect.right -= Cy.dp(8f)
        return max(0f, p.contentHeight(panelRect) - content.height())
    }

    // ------------------------------------------------------------------ touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            pressedTab = tabRects.indexOfFirst { it.contains(e.x, e.y) }
            swiping = false
            swipeDx = 0f
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val t = tabRects.indexOfFirst { it.contains(e.x, e.y) }
            if (t >= 0) {
                game.haptic()
                select(t)
                return true
            }
            if (content.contains(e.x, e.y)) {
                val p = panel
                p.tap(e.x, e.y + scrollY[tab], panelRect(p))
                invalidate()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!content.contains(e.x, e.y) || swiping) return
            val p = panel
            val r = panelRect(p)
            val cy = e.y + scrollY[tab]
            if (p is InventoryPanel) {
                val slot = p.slotAt(e.x, cy, r)
                if (slot >= 0 && game.invApp(slot) != null) {
                    game.haptic()
                    drag = Drag(slot, e.x, e.y, e.x, e.y)
                    invalidate()
                    return
                }
            }
            p.longPress(e.x, cy, r)
            invalidate()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val start = e1 ?: return false
            val totalX = e2.x - start.x
            val totalY = e2.y - start.y
            if (!swiping && abs(totalX) > touchSlop * 2 && abs(totalX) > abs(totalY) * 1.4f) swiping = true
            if (swiping) {
                swipeDx = totalX
                pressedTab = -1
                invalidate()
                return true
            }
            val p = panel
            if (!p.scrollable) return false
            scrollY[tab] = (scrollY[tab] + dy).coerceIn(0f, maxScroll(p))
            pressedTab = -1
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (swiping) return true
            val p = panel
            if (!p.scrollable || maxScroll(p) <= 0f) {
                // Nothing to scroll: a swipe up opens the stash, like on the city.
                if (vy < -Cy.dp(900f) && abs(vy) > abs(vx) * 1.5f) {
                    game.ui.openStash(null, null)
                    return true
                }
                return false
            }
            scroller.fling(0, scrollY[tab].toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll(p).toInt())
            postInvalidateOnAnimation()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = drag
        if (d != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    d.x = event.x
                    d.y = event.y
                    if (abs(d.x - d.startX) > touchSlop || abs(d.y - d.startY) > touchSlop) d.moved = true
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    drag = null
                    if (d.moved) {
                        val target = inventory.slotAt(event.x, event.y + scrollY[tab], panelRect(inventory))
                        if (target >= 0) game.swapInventory(d.slot, target)
                    } else {
                        game.inventoryMenu(d.slot, left + event.x, top + event.y)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    drag = null
                    invalidate()
                }
            }
            return true
        }
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (swiping) {
                val threshold = Cy.dp(56f)
                if (swipeDx <= -threshold) select((tab + 1) % panels.size)
                else if (swipeDx >= threshold) select((tab - 1 + panels.size) % panels.size)
                game.haptic()
            }
            swiping = false
            swipeDx = 0f
            pressedTab = -1
            invalidate()
        }
        return handled
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY[tab] = scroller.currY.toFloat().coerceIn(0f, maxScroll(panel))
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        Cy.panel(c, bounds, Cy.dp(14f), Cy.panel, Cy.dim, Cy.TR or Cy.BL)
        val p = panel
        // Header: "◢ TITLE" and the tab's own summary on the right.
        val ty = Cy.baseline(titlePaint, header.top, header.bottom) + Cy.dp(1f)
        path.reset()
        val tx = header.left + Cy.dp(12f)
        path.moveTo(tx + Cy.dp(9f), ty - Cy.dp(11f))
        path.lineTo(tx + Cy.dp(9f), ty)
        path.lineTo(tx, ty)
        path.close()
        fill.color = Cy.primary
        c.drawPath(path, fill)
        Cy.glow(c, p.title, tx + Cy.dp(15f), ty, titlePaint, Cy.primary, Cy.dp(5f))
        val info = p.info()
        if (info.isNotEmpty()) Cy.text(c, info, header.right - Cy.dp(18f), ty - Cy.dp(1f), infoPaint, Cy.dim)
        Cy.line(c, header.left + Cy.dp(8f), header.bottom, header.right - Cy.dp(8f), header.bottom, Cy.faint)
        Cy.line(c, header.left + Cy.dp(8f), header.bottom, header.left + Cy.dp(60f), header.bottom, Cy.primary, Cy.px * 2)

        val i = tab
        scrollY[i] = scrollY[i].coerceIn(0f, maxScroll(p))
        val r = panelRect(p)
        val since = SystemClock.uptimeMillis() - tabChanged
        val slide = if (since < SLIDE_MS) (1f - since / SLIDE_MS.toFloat()) * Cy.dp(28f) * tabDir else 0f
        val drift = if (swiping) (swipeDx * 0.25f).coerceIn(-Cy.dp(40f), Cy.dp(40f)) else 0f
        c.save()
        c.clipRect(content.left - Cy.dp(2f), content.top, content.right + Cy.dp(2f), content.bottom)
        c.translate(slide + drift, -scrollY[i])
        p.draw(c, r)
        c.restore()
        if (p.scrollable && maxScroll(p) > 0f) drawScrollbar(c, p)
        drawTabs(c)
        drag?.let { drawDrag(c, it) }
        if (since < SLIDE_MS || swiping) {
            postInvalidateOnAnimation()
        } else if (p.animates && !frameQueued) {
            // Equaliser bars and upload flashes don't need 60 fps.
            frameQueued = true
            postDelayed(frame, 50L)
        }
    }

    private var frameQueued = false
    private val frame = Runnable {
        frameQueued = false
        invalidate()
    }

    private fun drawTabs(c: Canvas) {
        Cy.line(c, tabBar.left + Cy.dp(8f), tabBar.top, tabBar.right - Cy.dp(8f), tabBar.top, Cy.faint)
        for (k in panels.indices) {
            val r = tabRects[k]
            val sel = k == tab
            if (sel) {
                Cy.rect(c, r.left + Cy.dp(3f), r.top + Cy.dp(1f), r.right - Cy.dp(3f), r.bottom - Cy.dp(6f), Cy.withAlpha(Cy.accent, 0x14))
                Cy.rect(c, r.left + Cy.dp(8f), r.top, r.right - Cy.dp(8f), r.top + Cy.dp(2f), Cy.accent)
            }
            if (k == pressedTab) Cy.rect(c, r.left + Cy.dp(3f), r.top + Cy.dp(1f), r.right - Cy.dp(3f), r.bottom - Cy.dp(6f), Cy.withAlpha(Cy.primary, 0x22))
            val size = min(Cy.dp(22f), r.width() * 0.5f)
            Glyphs.draw(c, panels[k].glyph, r.centerX(), r.centerY() - Cy.dp(3f), size, if (sel) Cy.accent else Cy.dim, 1.7f, glow = sel)
            if (sel) Cy.text(c, "${k + 1}", r.centerX(), r.bottom - Cy.dp(6f), hintPaint, Cy.accent)
        }
    }

    private fun drawScrollbar(c: Canvas, p: Panel) {
        val total = p.contentHeight(panelRect(p))
        val frac = content.height() / total
        val th = max(Cy.dp(24f), content.height() * frac)
        val ms = maxScroll(p)
        val ty = content.top + (content.height() - th) * (if (ms > 0f) scrollY[tab] / ms else 0f)
        val x = content.right - Cy.dp(2f)
        Cy.line(c, x, content.top, x, content.bottom, Cy.faint)
        Cy.rect(c, x - Cy.dp(1.5f), ty, x + Cy.dp(1.5f), ty + th, Cy.primary)
    }

    private fun drawDrag(c: Canvas, d: Drag) {
        val app = game.invApp(d.slot) ?: return
        val cell = inventory.cellSize(panelRect(inventory))
        ItemDraw.icon(c, game, app, d.x, d.y - cell * 0.4f, cell * 0.62f, alpha = 210)
    }

    companion object {
        const val SLIDE_MS = 170L
    }
}
