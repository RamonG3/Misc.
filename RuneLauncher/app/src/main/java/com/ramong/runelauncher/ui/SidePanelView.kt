package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ramong.runelauncher.core.AppEntry
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.ui.panels.AccountPanel
import com.ramong.runelauncher.ui.panels.ClanPanel
import com.ramong.runelauncher.ui.panels.CombatPanel
import com.ramong.runelauncher.ui.panels.EmotesPanel
import com.ramong.runelauncher.ui.panels.EquipmentPanel
import com.ramong.runelauncher.ui.panels.FriendsPanel
import com.ramong.runelauncher.ui.panels.InventoryPanel
import com.ramong.runelauncher.ui.panels.LogoutPanel
import com.ramong.runelauncher.ui.panels.MagicPanel
import com.ramong.runelauncher.ui.panels.MusicPanel
import com.ramong.runelauncher.ui.panels.PrayerPanel
import com.ramong.runelauncher.ui.panels.QuestPanel
import com.ramong.runelauncher.ui.panels.SettingsPanel
import com.ramong.runelauncher.ui.panels.SkillsPanel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Tab(val title: String) {
    COMBAT("Combat Options"), SKILLS("Skills"), QUESTS("Quest List"), INVENTORY("Inventory"),
    EQUIPMENT("Worn Equipment"), PRAYER("Prayer"), MAGIC("Magic"),
    CLAN("Clan"), FRIENDS("Friends List"), ACCOUNT("Account Management"), LOGOUT("Logout"),
    SETTINGS("Settings"), EMOTES("Emotes"), MUSIC("Music Player");

    val sprite: Sprite
        get() = when (this) {
            COMBAT -> Sprites.ATTACK
            SKILLS -> Sprites.STATS
            QUESTS -> Sprites.SCROLL
            INVENTORY -> Sprites.BAG
            EQUIPMENT -> Sprites.HELMET
            PRAYER -> Sprites.PRAYER
            MAGIC -> Sprites.MAGIC
            CLAN -> Sprites.BANNER
            FRIENDS -> Sprites.SMILEY
            ACCOUNT -> Sprites.PERSON
            LOGOUT -> Sprites.DOOR
            SETTINGS -> Sprites.GEAR
            EMOTES -> Sprites.CHEER
            MUSIC -> Sprites.NOTE
        }
}

/** Base class for a side-panel tab. Coordinates are in content space (already scrolled). */
abstract class Panel(protected val game: Game, protected val host: SidePanelView) {
    open val scrollable: Boolean get() = false
    open fun contentHeight(r: RectF): Float = r.height()
    abstract fun draw(c: Canvas, r: RectF)
    open fun tap(x: Float, y: Float, r: RectF) {}
    open fun longPress(x: Float, y: Float, r: RectF) {}

    protected val title = Rs.paint(15f, bold = true, align = Paint.Align.CENTER)
    protected val body = Rs.paint(13f)
    protected val bodyCenter = Rs.paint(13f, align = Paint.Align.CENTER)
    protected val small = Rs.paint(11f, align = Paint.Align.CENTER)

    /** Draws an orange title centred at the top; returns the y where content can start. */
    protected fun header(c: Canvas, r: RectF, text: String): Float {
        val y = r.top + Rs.dp(20f)
        Rs.text(c, text, r.centerX(), y, title, Rs.ORANGE)
        return y + Rs.dp(10f)
    }

    /** Menu anchor in root-layout coordinates for a point in content space. */
    protected fun menuX(x: Float) = host.left + x
    protected fun menuY(y: Float) = host.top + y - host.scrollOf(this)
}

/** Shared drawing for an app shown as an item (inventory, bank, equipment). */
object ItemDraw {
    private val filtered = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glow = Sprites.silhouette(Rs.WHITE)
    private val glowHd = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(Rs.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val dst = RectF()
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)

    fun icon(c: Canvas, game: Game, app: AppEntry, cx: Float, cy: Float, size: Float, highlight: Boolean = false, alpha: Int = 255) {
        val bmp: Bitmap? = game.icons.get(app)
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        if (bmp == null) {
            placeholder.color = Rs.withAlpha(0xFF6B5E4B.toInt(), alpha / 2)
            c.drawRoundRect(dst, size * 0.2f, size * 0.2f, placeholder)
            return
        }
        val hd = game.icons.pixelSize == 0
        if (highlight) {
            val o = max(Rs.px, size / 28f)
            for ((dx, dy) in listOf(-o to 0f, o to 0f, 0f to -o, 0f to o)) {
                dst.offset(dx, dy)
                c.drawBitmap(bmp, null, dst, if (hd) glowHd else glow)
                dst.offset(-dx, -dy)
            }
        }
        val p = if (hd) filtered else Sprites.paint
        p.alpha = alpha
        c.drawBitmap(bmp, null, dst, p)
        p.alpha = 255
    }
}

/** The stone side panel: 7 tabs on top, 7 on the bottom, the active tab's contents in between. */
@SuppressLint("ViewConstructor")
class SidePanelView(context: Context, val game: Game) : View(context) {
    var tab = Tab.INVENTORY
        private set
    private val panels = HashMap<Tab, Panel>()
    private val tabRects = Array(14) { RectF() }
    private val bounds = RectF()
    private val contentBg = RectF()
    val content = RectF()
    private val panelRect = RectF()
    private val scrollY = FloatArray(Tab.values().size)
    private val scroller = OverScroller(context)
    private var pressedTab = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val track = RectF()
    private val thumb = RectF()

    class Drag(val slot: Int, var x: Float, var y: Float, val startX: Float, val startY: Float) {
        var moved = false
    }

    var drag: Drag? = null
        private set

    init {
        panels[Tab.COMBAT] = CombatPanel(game, this)
        panels[Tab.SKILLS] = SkillsPanel(game, this)
        panels[Tab.QUESTS] = QuestPanel(game, this)
        panels[Tab.INVENTORY] = InventoryPanel(game, this)
        panels[Tab.EQUIPMENT] = EquipmentPanel(game, this)
        panels[Tab.PRAYER] = PrayerPanel(game, this)
        panels[Tab.MAGIC] = MagicPanel(game, this)
        panels[Tab.CLAN] = ClanPanel(game, this)
        panels[Tab.FRIENDS] = FriendsPanel(game, this)
        panels[Tab.ACCOUNT] = AccountPanel(game, this)
        panels[Tab.LOGOUT] = LogoutPanel(game, this)
        panels[Tab.SETTINGS] = SettingsPanel(game, this)
        panels[Tab.EMOTES] = EmotesPanel(game, this)
        panels[Tab.MUSIC] = MusicPanel(game, this)
    }

    private val panel: Panel get() = panels.getValue(tab)
    private val inventory: InventoryPanel get() = panels.getValue(Tab.INVENTORY) as InventoryPanel

    fun select(t: Tab) {
        if (t != tab) {
            tab = t
            scroller.forceFinished(true)
        }
        invalidate()
    }

    fun scrollOf(p: Panel): Float = if (panels[tab] === p) scrollY[tab.ordinal] else 0f

    /** Screen rectangle of a content-space rect (for launch animations). */
    fun screenRect(r: RectF): Rect {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val sy = scrollY[tab.ordinal]
        return Rect((loc[0] + r.left).toInt(), (loc[1] + r.top - sy).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom - sy).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val f = Rs.px * 3
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        val tabW = (w - 2 * f) / 7f
        val tabH = min(Rs.dp(44f), tabW * 0.78f)
        for (i in 0..6) tabRects[i].set(f + i * tabW, f, f + (i + 1) * tabW, f + tabH)
        for (i in 7..13) tabRects[i].set(f + (i - 7) * tabW, h - f - tabH, f + (i - 6) * tabW, h - f)
        contentBg.set(f, f + tabH, w - f, h - f - tabH)
        content.set(contentBg.left + Rs.dp(4f), contentBg.top + Rs.dp(4f), contentBg.right - Rs.dp(4f), contentBg.bottom - Rs.dp(4f))
    }

    private fun panelRect(p: Panel): RectF {
        panelRect.set(content)
        if (p.scrollable && maxScroll(p) > 0f) panelRect.right -= Rs.dp(14f)
        return panelRect
    }

    private fun maxScroll(p: Panel): Float {
        panelRect.set(content)
        panelRect.right -= Rs.dp(14f)
        return max(0f, p.contentHeight(panelRect) - content.height())
    }

    // ------------------------------------------------------------------ touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            pressedTab = tabRects.indexOfFirst { it.contains(e.x, e.y) }
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val t = tabRects.indexOfFirst { it.contains(e.x, e.y) }
            if (t >= 0) {
                game.haptic()
                if (game.useSlot >= 0 && Tab.values()[t] != Tab.INVENTORY) game.useSlot = -1
                select(Tab.values()[t])
                return true
            }
            if (content.contains(e.x, e.y)) {
                val p = panel
                p.tap(e.x, e.y + scrollY[tab.ordinal], panelRect(p))
                invalidate()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!content.contains(e.x, e.y)) return
            val p = panel
            val r = panelRect(p)
            val cy = e.y + scrollY[tab.ordinal]
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

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val p = panel
            if (!p.scrollable) return false
            val i = tab.ordinal
            scrollY[i] = (scrollY[i] + distanceY).coerceIn(0f, maxScroll(p))
            pressedTab = -1
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val p = panel
            if (!p.scrollable) return false
            scroller.fling(0, scrollY[tab.ordinal].toInt(), 0, -velocityY.toInt(), 0, 0, 0, maxScroll(p).toInt())
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
                        val target = inventory.slotAt(event.x, event.y + scrollY[tab.ordinal], panelRect(inventory))
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
            pressedTab = -1
            invalidate()
        }
        return handled
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY[tab.ordinal] = scroller.currY.toFloat().coerceIn(0f, maxScroll(panel))
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        Rs.stone(c, bounds, light = true)
        Rs.bevel(c, bounds)
        Rs.stone(c, contentBg)
        Rs.bevel(c, contentBg, sunken = true)
        for (i in 0 until 14) {
            val t = Tab.values()[i]
            val r = tabRects[i]
            Rs.button(c, r, selected = t == tab, pressed = i == pressedTab)
            t.sprite.draw(c, r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.62f)
        }
        val p = panel
        val i = tab.ordinal
        scrollY[i] = scrollY[i].coerceIn(0f, maxScroll(p))
        val r = panelRect(p)
        c.save()
        c.clipRect(content)
        c.translate(0f, -scrollY[i])
        p.draw(c, r)
        c.restore()
        if (p.scrollable && maxScroll(p) > 0f) drawScrollbar(c, p)
        drag?.let { drawDrag(c, it) }
    }

    private fun drawScrollbar(c: Canvas, p: Panel) {
        track.set(content.right - Rs.dp(12f), content.top, content.right, content.bottom)
        Rs.rect(c, track, 0xFF231F1A.toInt())
        Rs.outline(c, track, Rs.BLACK)
        val total = p.contentHeight(panelRect(p))
        val frac = content.height() / total
        val th = max(Rs.dp(24f), track.height() * frac)
        val ms = maxScroll(p)
        val ty = track.top + (track.height() - th) * (if (ms > 0f) scrollY[tab.ordinal] / ms else 0f)
        thumb.set(track.left + Rs.px, ty, track.right - Rs.px, ty + th)
        Rs.stone(c, thumb, light = true)
        Rs.bevel(c, thumb)
    }

    private fun drawDrag(c: Canvas, d: Drag) {
        val app = game.invApp(d.slot) ?: return
        val cell = inventory.cellSize(panelRect(inventory))
        ItemDraw.icon(c, game, app, d.x, d.y - cell * 0.35f, cell * 0.8f, alpha = 200)
    }
}
