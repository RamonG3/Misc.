package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ramong.cyberlauncher.core.AppEntry
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.SaveData
import com.ramong.cyberlauncher.core.Tag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The home screen, laid out like any launcher: a clock widget and mini map up top, a 4x5 app
 * grid, page dots, then a dock and a search bar that stay put. Swipe right for the deck page,
 * up for all apps, down for notifications. Hold an app for options, or hold and drag to move it.
 */
@SuppressLint("ViewConstructor")
class HomeView(context: Context, private val game: Game) : View(context) {
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    fun setInsets(top: Int, bottom: Int, left: Int, right: Int) {
        insetTop = top
        insetBottom = bottom
        insetLeft = left
        insetRight = right
        layoutHome()
        invalidate()
    }

    /** Where toasts should sit: just above the dock. */
    val toastBottom: Float get() = dock.top - Cy.dp(10f)

    // ------------------------------------------------------------------ pages

    private var pagePos = 1f
    private var animFrom = 1f
    private var animTo = 1f
    private var animStart = 0L
    private val animating get() = SystemClock.uptimeMillis() - animStart < PAGE_MS && animFrom != animTo

    val page: Int get() = if (animTo < 0.5f) Game.PAGE_DECK else Game.PAGE_HOME

    fun showPage(p: Int, animate: Boolean = true) {
        val target = if (p == Game.PAGE_DECK && game.save.deck) 0f else 1f
        if (animate) {
            animFrom = currentPos()
            animTo = target
            animStart = SystemClock.uptimeMillis()
        } else {
            animFrom = target
            animTo = target
            pagePos = target
        }
        invalidate()
    }

    private fun currentPos(): Float {
        if (!animating) return animTo.takeIf { mode != Mode.PAGE } ?: pagePos
        val k = ((SystemClock.uptimeMillis() - animStart) / PAGE_MS.toFloat()).coerceIn(0f, 1f)
        val e = 1f - (1f - k) * (1f - k) * (1f - k)
        return animFrom + (animTo - animFrom) * e
    }

    // ------------------------------------------------------------------ layout

    private var landscape = false
    private var clockX = 0f
    private var clockY = 0f
    private var dateY = 0f
    private var statusY = 0f
    private var taskY = 0f
    private val clockBox = RectF()
    private val dateBox = RectF()
    private val batteryBox = RectF()
    private val statusBox = RectF()
    private val taskBox = RectF()
    private var mapCx = 0f
    private var mapCy = 0f
    private var mapR = 0f
    private var map: Bitmap? = null
    private var mapKey = ""
    private val grid = RectF()
    private var cols = SaveData.HOME_COLS
    private var rows = SaveData.HOME / SaveData.HOME_COLS
    private var cellW = 0f
    private var cellH = 0f
    private var iconSize = 0f
    private val dock = RectF()
    private val dockCells = Array(SaveData.DOCK) { RectF() }
    private var dockIcon = 0f
    private val search = RectF()
    private var dotsY = 0f
    private val deckArea = RectF()
    private val targets = Array(3) { RectF() }
    private val cell = RectF()
    private val tmp = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutHome()

    private fun layoutHome() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val top = insetTop.toFloat()
        val left = insetLeft.toFloat()
        val right = w - insetRight
        val bottom = h - insetBottom
        landscape = w > h * 1.15f
        clockPaint.textSize = Cy.dp(if (landscape) 52f else 64f)

        if (!landscape) {
            search.set(left + Cy.dp(16f), bottom - Cy.dp(58f), right - Cy.dp(16f), bottom - Cy.dp(12f))
            dock.set(left + Cy.dp(12f), search.top - Cy.dp(86f), right - Cy.dp(12f), search.top - Cy.dp(10f))
            dotsY = dock.top - Cy.dp(16f)
            clockX = left + Cy.dp(20f)
            clockY = top + Cy.dp(78f)
            mapR = min(Cy.dp(42f), w * 0.11f)
            mapCx = right - Cy.dp(20f) - mapR
            mapCy = top + Cy.dp(26f) + mapR
            cols = SaveData.HOME_COLS
            rows = SaveData.HOME / cols
            grid.set(left + Cy.dp(10f), top + Cy.dp(176f), right - Cy.dp(10f), dotsY - Cy.dp(10f))
            deckArea.set(left + Cy.dp(14f), top + Cy.dp(10f), right - Cy.dp(14f), dotsY - Cy.dp(8f))
        } else {
            val split = left + (right - left) * 0.36f
            search.set(left + Cy.dp(16f), bottom - Cy.dp(54f), split - Cy.dp(8f), bottom - Cy.dp(10f))
            dock.set(split + Cy.dp(4f), bottom - Cy.dp(76f), right - Cy.dp(12f), bottom - Cy.dp(8f))
            dotsY = dock.top - Cy.dp(10f)
            clockX = left + Cy.dp(20f)
            clockY = top + Cy.dp(64f)
            mapR = Cy.dp(34f)
            mapCx = split - Cy.dp(16f) - mapR
            mapCy = search.top - Cy.dp(22f) - mapR
            cols = 5
            rows = SaveData.HOME / cols
            grid.set(split, top + Cy.dp(6f), right - Cy.dp(8f), dotsY - Cy.dp(6f))
            deckArea.set(left + Cy.dp(14f), top + Cy.dp(8f), right - Cy.dp(14f), dotsY - Cy.dp(4f))
        }
        dateY = clockY + Cy.dp(24f)
        statusY = dateY + Cy.dp(22f)
        taskY = statusY + Cy.dp(24f)
        clockBox.set(0f, clockY - clockPaint.textSize * 0.8f, clockX + clockPaint.measureText("88:88") + Cy.dp(20f), clockY + Cy.dp(6f))
        dateBox.set(0f, clockY + Cy.dp(6f), clockBox.right, dateY + Cy.dp(8f))
        cellW = grid.width() / cols
        cellH = grid.height() / rows
        iconSize = min(Cy.dp(54f), min(cellW * 0.6f, cellH - Cy.dp(if (game.save.labels) 30f else 12f)))
        val dw = dock.width() / SaveData.DOCK
        for (i in 0 until SaveData.DOCK) dockCells[i].set(dock.left + i * dw, dock.top, dock.left + (i + 1) * dw, dock.bottom)
        dockIcon = min(Cy.dp(50f), min(dw * 0.66f, dock.height() - Cy.dp(22f)))
        val tw = (right - left - Cy.dp(48f)) / 3f
        for (i in 0..2) targets[i].set(left + Cy.dp(16f) + i * (tw + Cy.dp(8f)), top + Cy.dp(8f), left + Cy.dp(16f) + i * (tw + Cy.dp(8f)) + tw, top + Cy.dp(52f))
        map = null
    }

    private fun cellRect(i: Int): RectF {
        val col = i % cols
        val row = i / cols
        cell.set(grid.left + col * cellW, grid.top + row * cellH, grid.left + (col + 1) * cellW, grid.top + (row + 1) * cellH)
        return cell
    }

    private fun gridIndexAt(x: Float, y: Float): Int {
        if (!grid.contains(x, y)) return -1
        val col = ((x - grid.left) / cellW).toInt().coerceIn(0, cols - 1)
        val row = ((y - grid.top) / cellH).toInt().coerceIn(0, rows - 1)
        return row * cols + col
    }

    private fun dockIndexAt(x: Float, y: Float): Int {
        if (y < dock.top - Cy.dp(6f) || y > dock.bottom + Cy.dp(6f)) return -1
        return dockCells.indexOfFirst { x >= it.left && x < it.right }
    }

    // ------------------------------------------------------------------ paints

    private val clockPaint = Cy.paint(64f, Cy.Face.BOLD, spacing = 0.02f)
    private val ampmPaint = Cy.paint(18f, Cy.Face.BOLD)
    private val datePaint = Cy.paint(13f, Cy.Face.MONO, spacing = 0.12f)
    private val statusPaint = Cy.paint(10.5f, Cy.Face.MONO, spacing = 0.06f)
    private val taskPaint = Cy.paint(13.5f, Cy.Face.BOLD, spacing = 0.06f)
    private val districtPaint = Cy.paint(8.5f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val northPaint = Cy.paint(9f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val searchPaint = Cy.paint(14f, Cy.Face.MONO, spacing = 0.06f)
    private val searchHint = Cy.paint(9f, Cy.Face.MONO, Paint.Align.RIGHT, 0.1f)
    private val targetPaint = Cy.paint(12f, Cy.Face.BOLD, Paint.Align.CENTER, 0.12f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val path = Path()
    private val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMM", Locale.getDefault())
    private val deck = DeckPage(game)
    private var deckScroll = 0f
    private val deckScroller = OverScroller(context)

    // ------------------------------------------------------------------ touch

    private enum class Mode { IDLE, PENDING, PAGE, SCROLL, SWIPE, DRAG, DONE }

    private sealed class Target {
        class Cell(val slot: Game.Slot) : Target()
        class Deck(val hit: DeckPage.Hit) : Target()
        object Clock : Target()
        object Date : Target()
        object Battery : Target()
        object Status : Target()
        object Task : Target()
        object Map : Target()
        object Search : Target()
        object Cat : Target()
        object Empty : Target()
    }

    private class Drag(val app: AppEntry, val from: Game.Slot?, var x: Float, var y: Float) {
        var moved = false
    }

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downPos = 1f
    private val swipe = Swipe()
    private var pressed: Target? = null
    private var drag: Drag? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val timer = Handler(Looper.getMainLooper())
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val longPress = Runnable { onLongPress() }

    private fun onHomePage() = currentPos() > 0.5f
    private fun homeOffset() = (1f - currentPos()) * width
    private fun deckOffset() = -currentPos() * width

    private fun targetAt(x: Float, y: Float): Target {
        if (search.contains(x, y)) return Target.Search
        val d = dockIndexAt(x, y)
        if (d >= 0 && y >= dock.top - Cy.dp(6f)) return Target.Cell(Game.Slot(true, d))
        if (onHomePage()) {
            val hx = x - homeOffset()
            if (game.save.hasCat && game.save.scene == 0 && sceneCat?.contains(x, y) == true) return Target.Cat
            if (hypot(hx - mapCx, y - mapCy) < mapR * 1.15f) return Target.Map
            if (clockBox.contains(hx, y)) return Target.Clock
            if (dateBox.contains(hx, y)) return Target.Date
            if (batteryBox.contains(hx, y)) return Target.Battery
            if (statusBox.contains(hx, y)) return Target.Status
            if (!taskBox.isEmpty && taskBox.contains(hx, y)) return Target.Task
            val g = gridIndexAt(hx, y)
            if (g >= 0) return Target.Cell(Game.Slot(false, g))
            return Target.Empty
        }
        val lx = x - deckOffset() - deckArea.left
        val ly = y - deckArea.top + deckScroll
        if (deckArea.contains(x - deckOffset(), y)) deck.hitAt(lx, ly)?.let { return Target.Deck(it) }
        return Target.Empty
    }

    /** The cat lives in the scene view; the root hands its box over. */
    var sceneCat: RectF? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        val before = mode
        touchLog?.invoke("${MotionEvent.actionToString(e.actionMasked)} x=${x.toInt()} y=${y.toInt()} mode=$mode pos=${"%.2f".format(currentPos())} n=${e.historySize} lag=${SystemClock.uptimeMillis() - e.eventTime}ms")
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                deckScroller.forceFinished(true)
                if (animating) {
                    pagePos = currentPos()
                    animFrom = pagePos
                    animTo = pagePos
                }
                mode = Mode.PENDING
                downX = x
                downY = y
                lastY = y
                downPos = currentPos()
                swipe.down(e)
                pressed = targetAt(x, y)
                timer.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                swipe.move(e)
                val dx = x - downX
                val dy = y - downY
                when (mode) {
                    Mode.PENDING -> if (hypot(dx, dy) > slop) {
                        timer.removeCallbacks(longPress)
                        pressed = null
                        mode = when {
                            abs(dx) > abs(dy) && game.save.deck -> Mode.PAGE
                            !onHomePage() && deckScrollable() -> Mode.SCROLL
                            else -> Mode.SWIPE
                        }
                    }
                    Mode.PAGE -> {
                        pagePos = (downPos - dx / width).coerceIn(-0.06f, 1.06f)
                        animFrom = pagePos
                        animTo = pagePos
                    }
                    Mode.SCROLL -> deckScroll = (deckScroll - (y - lastY)).coerceIn(0f, maxDeckScroll())
                    Mode.DRAG -> dragMove(x, y)
                    else -> Unit
                }
                lastY = y
                if (mode != before) touchLog?.invoke("mode $before -> $mode at dx=${dx.toInt()} dy=${dy.toInt()}")
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                timer.removeCallbacks(longPress)
                swipe.up(e, minFling)
                val vx = swipe.vx
                val vy = swipe.vy
                val dy = y - downY
                val dx = x - downX
                when (mode) {
                    // Lifted far from where it landed with no moves in between (it happens with
                    // injected or coalesced input): still a swipe, not a tap.
                    Mode.PENDING -> when {
                        abs(dx) > Cy.dp(80f) && abs(dx) > abs(dy) && game.save.deck -> showPage(if (dx > 0) Game.PAGE_DECK else Game.PAGE_HOME)
                        dy < -Cy.dp(80f) && abs(dy) > abs(dx) -> if (onHomePage()) game.ui.openDrawer(search = false, pick = false)
                        dy > Cy.dp(80f) && abs(dy) > abs(dx) -> game.actions.expandNotifications()
                        else -> pressed?.let { tap(it, x, y) }
                    }
                    Mode.PAGE -> {
                        // Where the finger let go counts, not just the last move before that.
                        pagePos = (downPos - dx / width).coerceIn(-0.06f, 1.06f)
                        val target = when {
                            vx < -minFling * 4 -> 1
                            vx > minFling * 4 -> 0
                            else -> pagePos.roundToInt().coerceIn(0, 1)
                        }
                        showPage(target)
                    }
                    Mode.SCROLL -> {
                        if (deckScroll <= 0f && dy > Cy.dp(80f) && vy > 0f) {
                            game.actions.expandNotifications()
                        } else {
                            deckScroller.fling(0, deckScroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxDeckScroll().toInt())
                            postInvalidateOnAnimation()
                        }
                    }
                    Mode.SWIPE -> when {
                        dy < -Cy.dp(60f) || vy < -minFling * 8 -> if (onHomePage()) game.ui.openDrawer(search = false, pick = false)
                        dy > Cy.dp(60f) || vy > minFling * 8 -> game.actions.expandNotifications()
                    }
                    Mode.DRAG -> dragEnd(x, y)
                    else -> Unit
                }
                touchLog?.invoke("UP handled as $mode: dx=${dx.toInt()} dy=${dy.toInt()} vx=${vx.toInt()} vy=${vy.toInt()} slop=${slop.toInt()} deck=${game.save.deck} -> page target $animTo")
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                timer.removeCallbacks(longPress)
                if (mode == Mode.PAGE) showPage(pagePos.roundToInt().coerceIn(0, 1))
                drag = null
                reset()
            }
        }
        return true
    }

    private fun reset() {
        mode = Mode.IDLE
        pressed = null
        swipe.clear()
        invalidate()
    }

    private fun onLongPress() {
        touchLog?.invoke("long press fired: mode=$mode pressed=$pressed")
        if (mode != Mode.PENDING) return
        val t = pressed
        pressed = null
        when (t) {
            is Target.Cell -> {
                val app = game.slotApp(t.slot)
                if (app != null) {
                    mode = Mode.DRAG
                    drag = Drag(app, t.slot, downX, downY)
                    game.appMenu(app, t.slot, downX, downY - iconSize)
                } else {
                    mode = Mode.DONE
                    game.homeMenu(downX, downY)
                }
            }
            is Target.Deck -> {
                mode = Mode.DONE
                t.hit.long?.invoke(downX, downY) ?: game.homeMenu(downX, downY)
            }
            Target.Map -> {
                mode = Mode.DONE
                game.haptic()
                game.travel()
            }
            else -> {
                mode = Mode.DONE
                game.homeMenu(downX, downY)
            }
        }
        invalidate()
    }

    private fun tap(t: Target, x: Float, y: Float) {
        when (t) {
            is Target.Cell -> {
                val app = game.slotApp(t.slot) ?: return
                val r = if (t.slot.dock) dockCells[t.slot.index] else RectF(cellRect(t.slot.index)).apply { offset(homeOffset(), 0f) }
                game.launch(app, screenRect(r))
            }
            is Target.Deck -> t.hit.tap()
            Target.Clock -> {
                game.haptic()
                game.actions.alarms()
            }
            Target.Date -> {
                game.haptic()
                game.actions.calendar()
            }
            Target.Battery -> {
                game.haptic()
                game.actions.battery()
            }
            Target.Status, Target.Task -> {
                game.haptic()
                showPage(Game.PAGE_DECK)
            }
            Target.Map -> {
                game.haptic()
                if (!game.actions.maps()) game.feed(Tag.ERR, "No map app installed. You're navigating by vibes.")
            }
            Target.Search -> {
                game.haptic()
                game.beginSearch()
            }
            Target.Cat -> game.catTapped()
            Target.Empty -> Unit
        }
    }

    private fun screenRect(r: RectF): Rect {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect((loc[0] + r.left).toInt(), (loc[1] + r.top).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom).toInt())
    }

    // ------------------------------------------------------------------ drag and drop

    private fun dragMove(x: Float, y: Float) {
        val d = drag ?: return
        d.x = x
        d.y = y
        if (!d.moved && hypot(x - downX, y - downY) > slop * 1.5f) {
            d.moved = true
            game.ui.hideMenu()
        }
    }

    private fun dragEnd(x: Float, y: Float) {
        val d = drag ?: return
        // Work out the drop target while the drag is still live; dropAt() reads it.
        val t = dropAt(x, y)
        drag = null
        // Held without moving: the menu stays open.
        if (!d.moved) return
        when {
            t == DROP_REMOVE -> d.from?.let { game.removeFrom(it) }
            t == DROP_INFO -> game.source.details(d.app)
            t == DROP_UNINSTALL -> game.uninstall(d.app)
            t >= 0 -> {
                val slot = if (t >= DOCK_BASE) Game.Slot(true, t - DOCK_BASE) else Game.Slot(false, t)
                val from = d.from
                if (from != null) game.move(from, slot) else game.place(d.app, slot)
            }
        }
        game.haptic()
        invalidate()
    }

    /** Grid index, DOCK_BASE + dock index, one of the DROP_ targets, or -1. */
    private fun dropAt(x: Float, y: Float): Int {
        val d = drag ?: return -1
        if (targets[0].contains(x, y) && d.from != null) return DROP_REMOVE
        if (targets[1].contains(x, y)) return DROP_INFO
        if (targets[2].contains(x, y)) return DROP_UNINSTALL
        val di = dockIndexAt(x, y)
        if (di >= 0) return DOCK_BASE + di
        return gridIndexAt(x, y)
    }

    /** Called by the drawer: an app held there and dragged out lands on the home screen. */
    fun beginExternalDrag(app: AppEntry, x: Float, y: Float) {
        showPage(Game.PAGE_HOME, animate = false)
        timer.removeCallbacks(longPress)
        downX = x
        downY = y
        drag = Drag(app, null, x, y).also { it.moved = true }
        mode = Mode.DRAG
        invalidate()
    }

    fun externalDragMove(x: Float, y: Float) {
        dragMove(x, y)
        invalidate()
    }

    fun externalDragEnd(x: Float, y: Float, cancelled: Boolean) {
        if (cancelled) drag = null else dragEnd(x, y)
        mode = Mode.IDLE
        invalidate()
    }

    // ------------------------------------------------------------------ deck scrolling

    private fun maxDeckScroll() = max(0f, deck.height - deckArea.height())
    private fun deckScrollable() = maxDeckScroll() > 0f

    override fun computeScroll() {
        if (deckScroller.computeScrollOffset()) {
            deckScroll = deckScroller.currY.toFloat().coerceIn(0f, maxDeckScroll())
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (map == null || mapKey != "${mapR.toInt()}:${Cy.palette.id}:$mapSeed") {
            mapKey = "${mapR.toInt()}:${Cy.palette.id}:$mapSeed"
            map = buildMap()
        }
        if (mode != Mode.PAGE) pagePos = currentPos()
        val w = width.toFloat()
        val ho = homeOffset()
        if (ho < w) {
            c.save()
            c.translate(ho, 0f)
            drawHome(c)
            c.restore()
        }
        val dOff = deckOffset()
        if (game.save.deck && dOff > -w) {
            c.save()
            c.clipRect(deckArea.left + dOff - Cy.dp(4f), deckArea.top, deckArea.right + dOff + Cy.dp(4f), deckArea.bottom)
            c.translate(deckArea.left + dOff, deckArea.top - deckScroll)
            deck.draw(c, deckArea.width())
            c.restore()
            deckScroll = deckScroll.coerceIn(0f, maxDeckScroll())
        }
        drawDots(c)
        drawDock(c)
        drawSearch(c)
        drag?.let { if (it.moved) drawDrag(c, it) }
        if (animating || (deck.animating && !onHomePage())) postInvalidateOnAnimation()
    }

    private fun drawHome(c: Canvas) {
        drawClock(c)
        drawMap(c)
        val dragging = drag?.takeIf { it.moved }
        val hover = dragging?.let { dropAt(it.x, it.y) } ?: -1
        val pressedCell = (pressed as? Target.Cell)?.slot
        for (i in 0 until SaveData.HOME) {
            val r = cellRect(i)
            val app = game.homeApp(i)
            val hidden = dragging != null && dragging.from == Game.Slot(false, i)
            if (app != null && !hidden) {
                AppIcon.cell(c, game, app, r, iconSize, game.save.labels, pressed = pressedCell == Game.Slot(false, i))
            } else if (dragging != null) {
                AppIcon.slot(c, r, iconSize, hover == i)
            }
            if (dragging != null && app != null && !hidden && hover == i) Cy.brackets(c, RectF(r).apply { inset(Cy.dp(6f), Cy.dp(6f)) }, Cy.accent, Cy.dp(8f), Cy.px * 2f)
        }
    }

    private fun drawClock(c: Canvas) {
        val cal = Calendar.getInstance()
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val time: String
        var suffix = ""
        if (game.save.clock24) {
            time = String.format(Locale.US, "%02d:%02d", h24, min)
        } else {
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            time = String.format(Locale.US, "%d:%02d", h12, min)
            suffix = if (h24 < 12) "AM" else "PM"
        }
        val x = clockX - Cy.dp(4f)
        Cy.split(c, time, x, clockY, clockPaint, Cy.WHITE, Cy.dp(1.4f), Cy.dp(10f))
        if (suffix.isNotEmpty()) Cy.text(c, suffix, x + clockPaint.measureText(time) + Cy.dp(4f), clockY, ampmPaint, Cy.primary)
        val year = cal.get(Calendar.YEAR) + if (game.save.realYear) 0 else 51
        val date = "${dayFormat.format(cal.time).uppercase(Locale.getDefault()).take(3)} · " +
            String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH)) + " " +
            monthFormat.format(cal.time).uppercase(Locale.getDefault()).take(3) + " $year"
        Cy.text(c, date, clockX, dateY, datePaint, Cy.primary)

        // Status line: battery as a little health bar, then level, eddies and heat.
        val s = game.stats
        val critical = s.battery <= 15 && !s.charging
        val col = if (critical && (SystemClock.uptimeMillis() / 400) % 2 == 0L) Cy.DANGER else if (s.charging) Cy.accent else Cy.primary
        tmp.set(clockX, statusY - Cy.dp(8f), clockX + Cy.dp(54f), statusY - Cy.dp(1f))
        Cy.segments(c, tmp, 10, s.battery / 10f, col, Cy.withAlpha(col, 0x2A))
        var sx = tmp.right + Cy.dp(8f)
        val pct = "${s.battery}%" + if (s.charging) "+" else ""
        Cy.text(c, pct, sx, statusY, statusPaint, col)
        sx += statusPaint.measureText(pct)
        batteryBox.set(0f, statusY - Cy.dp(16f), sx + Cy.dp(6f), statusY + Cy.dp(8f))
        val rest = "  ·  LVL ${game.level()}  ·  €$ ${Game.number(game.save.eddies)}"
        Cy.text(c, rest, sx, statusY, statusPaint, Cy.hi)
        sx += statusPaint.measureText(rest)
        if (game.heat > 0) {
            val flash = (SystemClock.uptimeMillis() / 350) % 2 == 0L
            for (i in 0 until game.heat) {
                Glyphs.draw(c, Glyph.STAR, sx + Cy.dp(12f) + i * Cy.dp(12f), statusY - Cy.dp(4f), Cy.dp(10f), if ((i % 2 == 0) == flash) Cy.DANGER else 0xFF3F7CFF.toInt(), 1.2f)
            }
            sx += Cy.dp(12f) + game.heat * Cy.dp(12f)
        }
        statusBox.set(batteryBox.right, statusY - Cy.dp(16f), sx + Cy.dp(8f), statusY + Cy.dp(8f))
        val next = game.nextTask()
        if (next != null && !landscape) {
            val label = Cy.ellipsize(next.title.uppercase(Locale.US), taskPaint, width - clockX - Cy.dp(40f))
            Cy.diamond(c, clockX + Cy.dp(4f), taskY - Cy.dp(5f), Cy.dp(4f), Cy.hi)
            Cy.glow(c, label, clockX + Cy.dp(14f), taskY, taskPaint, Cy.hi, Cy.dp(4f))
            taskBox.set(0f, taskY - Cy.dp(16f), clockX + Cy.dp(20f) + taskPaint.measureText(label), taskY + Cy.dp(8f))
        } else {
            taskBox.setEmpty()
        }
    }

    private var mapSeed = (System.currentTimeMillis() / 86_400_000L).toInt()

    fun travel() {
        mapSeed = Random.nextInt()
        invalidate()
    }

    fun themeChanged() {
        map = null
        invalidate()
    }

    private fun buildMap(): Bitmap? {
        val n = (mapR * 2).toInt()
        if (n <= 0) return null
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = Random(mapSeed * 31 + 7)
        c.drawColor(Cy.mix(Cy.bg, 0xFF000000.toInt(), 0.25f))
        fill.color = Cy.mix(Cy.bg, Cy.accent, 0.12f)
        path.reset()
        val k = n * (0.18f + r.nextFloat() * 0.2f)
        when (r.nextInt(4)) {
            0 -> { path.moveTo(0f, 0f); path.lineTo(k * 1.4f, 0f); path.lineTo(k, n.toFloat()); path.lineTo(0f, n.toFloat()) }
            1 -> { path.moveTo(n.toFloat(), 0f); path.lineTo(n - k, 0f); path.lineTo(n - k * 1.3f, n.toFloat()); path.lineTo(n.toFloat(), n.toFloat()) }
            2 -> { path.moveTo(0f, n.toFloat()); path.lineTo(0f, n - k); path.lineTo(n.toFloat(), n - k * 1.5f); path.lineTo(n.toFloat(), n.toFloat()) }
            else -> { path.moveTo(0f, 0f); path.lineTo(n.toFloat(), 0f); path.lineTo(n.toFloat(), k); path.lineTo(0f, k * 1.4f) }
        }
        path.close()
        c.drawPath(path, fill)
        c.save()
        c.rotate(-25f + r.nextFloat() * 50f, n / 2f, n / 2f)
        val block = Cy.dp(8f + r.nextInt(4))
        fill.color = Cy.mix(Cy.bg, Cy.primary, 0.16f)
        var gy = -n.toFloat()
        while (gy < n * 2f) {
            var gx = -n.toFloat()
            while (gx < n * 2f) {
                if (r.nextInt(5) != 0) c.drawRect(gx + Cy.dp(1.2f), gy + Cy.dp(1.2f), gx + block - Cy.dp(1.2f), gy + block - Cy.dp(1.2f), fill)
                gx += block
            }
            gy += block
        }
        stroke.color = Cy.withAlpha(Cy.primary, 0xC0)
        stroke.strokeWidth = Cy.dp(1.4f)
        var i = 0
        var g = -n.toFloat()
        while (g < n * 2f) {
            if (i % 3 == 1) {
                c.drawLine(g, -n.toFloat(), g, n * 2f, stroke)
                c.drawLine(-n.toFloat(), g, n * 2f, g, stroke)
            }
            g += block
            i++
        }
        c.restore()
        fill.color = Cy.accent
        repeat(3) {
            val x = n * (0.15f + r.nextFloat() * 0.7f)
            val y = n * (0.15f + r.nextFloat() * 0.7f)
            c.drawRect(x - Cy.dp(2f), y - Cy.dp(2f), x + Cy.dp(2f), y + Cy.dp(2f), fill)
        }
        fill.color = Cy.DANGER
        repeat(3) { c.drawCircle(n * (0.1f + r.nextFloat() * 0.8f), n * (0.1f + r.nextFloat() * 0.8f), Cy.dp(1.4f), fill) }
        Cy.diamond(c, n * (0.3f + r.nextFloat() * 0.4f), n * (0.18f + r.nextFloat() * 0.2f), Cy.dp(3.5f), Cy.hi)
        return b
    }

    private fun drawMap(c: Canvas) {
        val m = map ?: return
        val cx = mapCx
        val cy = mapCy
        val r = mapR
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        bmpPaint.alpha = 235
        c.drawBitmap(m, cx - r, cy - r, bmpPaint)
        c.restore()
        stroke.color = Cy.withAlpha(Cy.primary, 0xD0)
        stroke.strokeWidth = Cy.dp(1.3f)
        c.drawCircle(cx, cy, r, stroke)
        stroke.color = Cy.faint
        stroke.strokeWidth = Cy.px
        c.drawCircle(cx, cy, r + Cy.dp(4f), stroke)
        tmp.set(cx - Cy.dp(7f), cy - r - Cy.dp(7f), cx + Cy.dp(7f), cy - r + Cy.dp(5f))
        Cy.rect(c, tmp, Cy.bg)
        Cy.text(c, "N", cx, tmp.bottom - Cy.dp(2.5f), northPaint, Cy.hi)
        path.reset()
        val a = Cy.dp(6f)
        path.moveTo(cx, cy - a)
        path.lineTo(cx + a * 0.62f, cy + a * 0.7f)
        path.lineTo(cx, cy + a * 0.32f)
        path.lineTo(cx - a * 0.62f, cy + a * 0.7f)
        path.close()
        fill.color = Cy.WHITE
        c.drawPath(path, fill)
        val d = game.district
        Cy.text(c, d.second, cx, cy + r + Cy.dp(16f), districtPaint, Cy.dim)
    }

    private fun drawDots(c: Canvas) {
        if (!game.save.deck) return
        val w = Cy.dp(18f)
        val gap = Cy.dp(8f)
        val cx = (if (landscape) (dock.left + dock.right) / 2f else width / 2f)
        val pos = currentPos()
        for (i in 0..1) {
            val x = cx - w - gap / 2f + i * (w + gap)
            val on = abs(pos - i) < 0.5f
            Cy.rect(c, x, dotsY - Cy.dp(1.5f), x + w, dotsY + Cy.dp(1.5f), if (on) Cy.accent else Cy.withAlpha(Cy.WHITE, 0x50))
        }
    }

    private fun drawDock(c: Canvas) {
        Cy.panel(c, dock, Cy.dp(12f), Cy.withAlpha(Cy.bg, 0xB8), Cy.withAlpha(Cy.primary, 0x70), Cy.TR or Cy.BL)
        Cy.scanlines(c, dock)
        val dragging = drag?.takeIf { it.moved }
        val hover = dragging?.let { dropAt(it.x, it.y) } ?: -1
        val pressedCell = (pressed as? Target.Cell)?.slot
        for (i in 0 until SaveData.DOCK) {
            val r = dockCells[i]
            val app = game.dockApp(i)
            val hidden = dragging != null && dragging.from == Game.Slot(true, i)
            if (app != null && !hidden) {
                AppIcon.cell(c, game, app, r, dockIcon, labels = false, pressed = pressedCell == Game.Slot(true, i))
            } else if (dragging != null) {
                AppIcon.slot(c, RectF(r).apply { offset(0f, Cy.dp(6f)) }, dockIcon, hover == DOCK_BASE + i)
            }
            if (dragging != null && app != null && !hidden && hover == DOCK_BASE + i) Cy.brackets(c, RectF(r).apply { inset(Cy.dp(8f), Cy.dp(8f)) }, Cy.accent, Cy.dp(8f), Cy.px * 2f)
        }
    }

    private fun drawSearch(c: Canvas) {
        val p = pressed === Target.Search
        Cy.panel(c, search, Cy.dp(10f), Cy.withAlpha(Cy.bg, if (p) 0xF0 else 0xD0), if (p) Cy.accent else Cy.withAlpha(Cy.primary, 0x90), Cy.TR or Cy.BL, ticks = false)
        Glyphs.draw(c, Glyph.SEARCH, search.left + Cy.dp(22f), search.centerY(), Cy.dp(17f), Cy.accent, 2f)
        val by = Cy.baseline(searchPaint, search.top, search.bottom)
        Cy.text(c, "SEARCH APPS", search.left + Cy.dp(42f), by, searchPaint, Cy.withAlpha(Cy.TEXT, 0xC0))
        if (!landscape) Cy.text(c, "/HELP FOR COMMANDS", search.right - Cy.dp(14f), by - Cy.dp(1f), searchHint, Cy.withAlpha(Cy.MUTED, 0xB0))
    }

    private fun drawDrag(c: Canvas, d: Drag) {
        val hover = dropAt(d.x, d.y)
        val labels = arrayOf("REMOVE", "APP INFO", "UNINSTALL")
        val codes = intArrayOf(DROP_REMOVE, DROP_INFO, DROP_UNINSTALL)
        for (i in 0..2) {
            if (i == 0 && d.from == null) continue
            val r = targets[i]
            val on = hover == codes[i]
            val col = if (i == 2) Cy.DANGER else Cy.accent
            Cy.panel(c, r, Cy.dp(8f), Cy.withAlpha(Cy.bg, if (on) 0xF4 else 0xD8), if (on) col else Cy.withAlpha(col, 0x80), Cy.TR or Cy.BL, ticks = false)
            Cy.text(c, labels[i], r.centerX(), Cy.baseline(targetPaint, r.top, r.bottom), targetPaint, if (on) col else Cy.WHITE)
        }
        AppIcon.icon(c, game, d.app, d.x, d.y - iconSize * 0.6f, iconSize * 1.1f, alpha = 230)
    }

    companion object {
        /** Debug builds log touches here so CI can see what a device did with a gesture. */
        var touchLog: ((String) -> Unit)? = null

        const val PAGE_MS = 260L
        const val DOCK_BASE = 1000
        const val DROP_REMOVE = -2
        const val DROP_INFO = -3
        const val DROP_UNINSTALL = -4
    }
}
