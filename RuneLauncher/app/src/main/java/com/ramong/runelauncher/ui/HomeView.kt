package com.ramong.runelauncher.ui

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
import com.ramong.runelauncher.core.AppEntry
import com.ramong.runelauncher.core.Folder
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.SaveData
import com.ramong.runelauncher.core.Tag
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
 * The home screen, laid out like any launcher: a clock and minimap up top, a 4x5 grid of apps
 * and folders, page markers, then a dock and a search bar that stay put. Swipe right for the
 * Adventurer's Log, up for the bank (all apps), down for notifications. Hold for options; hold
 * and drag to move; drop one app onto another to make a folder.
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
    val toastBottom: Float get() = dock.top - R3.dp(12f)

    /** Where XP drops start: just under the minimap. */
    val dropAnchorX: Float get() = mapCx - mapR - R3.dp(52f)
    val dropAnchorY: Float get() = mapCy + R3.dp(16f)

    // ------------------------------------------------------------------ pages

    private var pagePos = 1f
    private var animFrom = 1f
    private var animTo = 1f
    private var animStart = 0L
    private val animating get() = SystemClock.uptimeMillis() - animStart < PAGE_MS && animFrom != animTo

    val page: Int get() = if (animTo < 0.5f) Game.PAGE_LOG else Game.PAGE_HOME

    fun showPage(p: Int, animate: Boolean = true) {
        val target = if (p == Game.PAGE_LOG && game.save.logPage) 0f else 1f
        if (target == 0f) game.achievement("log")
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
    private var questY = 0f
    private val clockBox = RectF()
    private val dateBox = RectF()
    private val lifeBox = RectF()
    private val statusBox = RectF()
    private val questBox = RectF()
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
    private val logArea = RectF()
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
        clockPaint.textSize = R3.dp(if (landscape) 46f else 56f)

        if (!landscape) {
            search.set(left + R3.dp(16f), bottom - R3.dp(60f), right - R3.dp(16f), bottom - R3.dp(12f))
            dock.set(left + R3.dp(12f), search.top - R3.dp(88f), right - R3.dp(12f), search.top - R3.dp(10f))
            dotsY = dock.top - R3.dp(16f)
            clockX = left + R3.dp(22f)
            clockY = top + R3.dp(74f)
            mapR = min(R3.dp(46f), w * 0.115f)
            mapCx = right - R3.dp(22f) - mapR
            mapCy = top + R3.dp(22f) + mapR
            cols = SaveData.HOME_COLS
            rows = SaveData.HOME / cols
            grid.set(left + R3.dp(10f), top + R3.dp(184f), right - R3.dp(10f), dotsY - R3.dp(10f))
            logArea.set(left + R3.dp(14f), top + R3.dp(10f), right - R3.dp(14f), dotsY - R3.dp(8f))
        } else {
            val split = left + (right - left) * 0.36f
            search.set(left + R3.dp(16f), bottom - R3.dp(54f), split - R3.dp(8f), bottom - R3.dp(10f))
            dock.set(split + R3.dp(4f), bottom - R3.dp(78f), right - R3.dp(12f), bottom - R3.dp(8f))
            dotsY = dock.top - R3.dp(10f)
            clockX = left + R3.dp(22f)
            clockY = top + R3.dp(60f)
            mapR = R3.dp(36f)
            mapCx = split - R3.dp(18f) - mapR
            mapCy = search.top - R3.dp(30f) - mapR
            cols = 5
            rows = SaveData.HOME / cols
            grid.set(split, top + R3.dp(6f), right - R3.dp(8f), dotsY - R3.dp(6f))
            logArea.set(left + R3.dp(14f), top + R3.dp(8f), right - R3.dp(14f), dotsY - R3.dp(4f))
        }
        dateY = clockY + R3.dp(26f)
        statusY = dateY + R3.dp(26f)
        questY = statusY + R3.dp(26f)
        clockBox.set(0f, clockY - clockPaint.textSize * 0.85f, clockX + clockPaint.measureText("88:88") + R3.dp(24f), clockY + R3.dp(6f))
        dateBox.set(0f, clockY + R3.dp(6f), clockBox.right + R3.dp(40f), dateY + R3.dp(8f))
        cellW = grid.width() / cols
        cellH = grid.height() / rows
        iconSize = min(R3.dp(58f), min(cellW * 0.62f, cellH - R3.dp(if (game.save.labels) 30f else 12f)))
        val dw = dock.width() / SaveData.DOCK
        for (i in 0 until SaveData.DOCK) dockCells[i].set(dock.left + i * dw, dock.top, dock.left + (i + 1) * dw, dock.bottom)
        dockIcon = min(R3.dp(52f), min(dw * 0.68f, dock.height() - R3.dp(24f)))
        val tw = (right - left - R3.dp(48f)) / 3f
        for (i in 0..2) targets[i].set(left + R3.dp(16f) + i * (tw + R3.dp(8f)), top + R3.dp(8f), left + R3.dp(16f) + i * (tw + R3.dp(8f)) + tw, top + R3.dp(54f))
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
        if (y < dock.top - R3.dp(6f) || y > dock.bottom + R3.dp(6f)) return -1
        return dockCells.indexOfFirst { x >= it.left && x < it.right }
    }

    // ------------------------------------------------------------------ paints

    private val clockPaint = R3.paint(56f, R3.Face.TITLE_BOLD, spacing = 0.02f)
    private val ampmPaint = R3.paint(17f, R3.Face.TITLE_BOLD)
    private val datePaint = R3.paint(15f, R3.Face.BOLD, spacing = 0.03f)
    private val statusPaint = R3.paint(13f, R3.Face.BOLD)
    private val questPaint = R3.paint(14f, R3.Face.BOLD)
    private val placePaint = R3.paint(11.5f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val worldPaint = R3.paint(10f, R3.Face.BOLD, Paint.Align.CENTER)
    private val northPaint = R3.paint(9.5f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val keyPaint = R3.paint(8.5f, R3.Face.BOLD, Paint.Align.CENTER)
    private val searchPaint = R3.paint(15f, R3.Face.BODY)
    private val searchHint = R3.paint(11f, R3.Face.BOLD, Paint.Align.RIGHT)
    private val targetPaint = R3.paint(13f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
    private val log = LogPage(game)
    private var logScroll = 0f
    private val logScroller = OverScroller(context)

    // ------------------------------------------------------------------ touch

    private enum class Mode { IDLE, PENDING, PAGE, SCROLL, SWIPE, DRAG, DONE }

    private sealed class Target {
        class Cell(val slot: Game.Slot) : Target()
        class Log(val hit: LogPage.Hit) : Target()
        object Clock : Target()
        object Date : Target()
        object Life : Target()
        object Status : Target()
        object Quest : Target()
        object Map : Target()
        object Search : Target()
        object Empty : Target()
    }

    /** What's being dragged: an app (from a cell, a folder or the bank) or a whole folder. */
    private class Drag(val app: AppEntry?, val folder: Folder?, val from: Game.Slot?, val fromFolder: String?, var x: Float, var y: Float) {
        var moved = false
        val key: String? get() = app?.key ?: folder?.let { SaveData.FOLDER + it.id }
    }

    private class Drop(val code: Int, val merge: Boolean)

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
    private fun logOffset() = -currentPos() * width

    private fun targetAt(x: Float, y: Float): Target {
        if (search.contains(x, y)) return Target.Search
        val d = dockIndexAt(x, y)
        if (d >= 0 && y >= dock.top - R3.dp(6f)) return Target.Cell(Game.Slot(true, d))
        if (onHomePage()) {
            val hx = x - homeOffset()
            if (hypot(hx - mapCx, y - mapCy) < mapR * 1.2f) return Target.Map
            if (clockBox.contains(hx, y)) return Target.Clock
            if (dateBox.contains(hx, y)) return Target.Date
            if (lifeBox.contains(hx, y)) return Target.Life
            if (statusBox.contains(hx, y)) return Target.Status
            if (!questBox.isEmpty && questBox.contains(hx, y)) return Target.Quest
            val g = gridIndexAt(hx, y)
            if (g >= 0) return Target.Cell(Game.Slot(false, g))
            return Target.Empty
        }
        val lx = x - logOffset() - logArea.left
        val ly = y - logArea.top + logScroll
        if (logArea.contains(x - logOffset(), y)) log.hitAt(lx, ly)?.let { return Target.Log(it) }
        return Target.Empty
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        val before = mode
        touchLog?.invoke("${MotionEvent.actionToString(e.actionMasked)} x=${x.toInt()} y=${y.toInt()} mode=$mode pos=${"%.2f".format(currentPos())} n=${e.historySize}")
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                logScroller.forceFinished(true)
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
                            abs(dx) > abs(dy) && game.save.logPage -> Mode.PAGE
                            !onHomePage() && logScrollable() -> Mode.SCROLL
                            else -> Mode.SWIPE
                        }
                    }
                    Mode.PAGE -> {
                        pagePos = (downPos - dx / width).coerceIn(-0.06f, 1.06f)
                        animFrom = pagePos
                        animTo = pagePos
                    }
                    Mode.SCROLL -> logScroll = (logScroll - (y - lastY)).coerceIn(0f, maxLogScroll())
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
                    // Lifted far from where it landed with no moves in between (injected or
                    // coalesced input): still a swipe, not a tap.
                    Mode.PENDING -> when {
                        abs(dx) > R3.dp(80f) && abs(dx) > abs(dy) && game.save.logPage -> showPage(if (dx > 0) Game.PAGE_LOG else Game.PAGE_HOME)
                        dy < -R3.dp(80f) && abs(dy) > abs(dx) -> if (onHomePage()) game.ui.openDrawer(search = false, pick = false)
                        dy > R3.dp(80f) && abs(dy) > abs(dx) -> game.actions.expandNotifications()
                        else -> pressed?.let { tap(it) }
                    }
                    Mode.PAGE -> {
                        pagePos = (downPos - dx / width).coerceIn(-0.06f, 1.06f)
                        val target = when {
                            vx < -minFling * 4 -> 1
                            vx > minFling * 4 -> 0
                            else -> pagePos.roundToInt().coerceIn(0, 1)
                        }
                        showPage(target)
                    }
                    Mode.SCROLL -> {
                        if (logScroll <= 0f && dy > R3.dp(80f) && vy > 0f) {
                            game.actions.expandNotifications()
                        } else {
                            logScroller.fling(0, logScroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxLogScroll().toInt())
                            postInvalidateOnAnimation()
                        }
                    }
                    Mode.SWIPE -> when {
                        dy < -R3.dp(60f) || vy < -minFling * 8 -> if (onHomePage()) game.ui.openDrawer(search = false, pick = false)
                        dy > R3.dp(60f) || vy > minFling * 8 -> game.actions.expandNotifications()
                    }
                    Mode.DRAG -> dragEnd(x, y)
                    else -> Unit
                }
                touchLog?.invoke("UP handled as $mode: dx=${dx.toInt()} dy=${dy.toInt()} vx=${vx.toInt()} vy=${vy.toInt()} -> page target $animTo")
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
            is Target.Cell -> when (val c = game.cell(t.slot)) {
                is Game.Cell.App -> {
                    mode = Mode.DRAG
                    drag = Drag(c.app, null, t.slot, null, downX, downY)
                    game.appMenu(c.app, t.slot, null, downX, downY - iconSize)
                }
                is Game.Cell.Dir -> {
                    mode = Mode.DRAG
                    drag = Drag(null, c.folder, t.slot, null, downX, downY)
                    game.folderMenu(t.slot, downX, downY - iconSize)
                }
                null -> {
                    mode = Mode.DONE
                    game.homeMenu(downX, downY)
                }
            }
            is Target.Log -> {
                mode = Mode.DONE
                t.hit.long?.invoke(downX, downY) ?: game.homeMenu(downX, downY)
            }
            Target.Map -> {
                mode = Mode.DONE
                game.haptic()
                game.worldHop()
            }
            else -> {
                mode = Mode.DONE
                game.homeMenu(downX, downY)
            }
        }
        invalidate()
    }

    private fun tap(t: Target) {
        when (t) {
            is Target.Cell -> {
                val r = if (t.slot.dock) RectF(dockCells[t.slot.index]) else RectF(cellRect(t.slot.index)).apply { offset(homeOffset(), 0f) }
                when (val c = game.cell(t.slot)) {
                    is Game.Cell.App -> game.launch(c.app, screenRect(r))
                    is Game.Cell.Dir -> {
                        game.haptic()
                        game.ui.openFolder(c.folder.id)
                    }
                    null -> Unit
                }
            }
            is Target.Log -> t.hit.tap()
            Target.Clock -> {
                game.haptic()
                game.actions.alarms()
            }
            Target.Date -> {
                game.haptic()
                game.actions.calendar()
            }
            Target.Life -> {
                game.haptic()
                game.actions.battery()
            }
            Target.Status, Target.Quest -> {
                game.haptic()
                showPage(Game.PAGE_LOG)
            }
            Target.Map -> {
                game.haptic()
                if (!game.actions.maps()) game.feed(Tag.ERR, "You don't have a map. You'll have to ask for directions.")
            }
            Target.Search -> {
                game.haptic()
                game.beginSearch()
            }
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
        val t = dropAt(x, y)
        drag = null
        // Held without moving: the menu stays open.
        if (!d.moved) return
        val key = d.key ?: return
        when {
            t == null -> Unit
            t.code == DROP_REMOVE -> {
                val ff = d.fromFolder
                if (ff != null) game.removeFromFolder(ff, key) else d.from?.let { game.removeFrom(it) }
            }
            t.code == DROP_INFO -> d.app?.let { game.source.details(it) }
            t.code == DROP_UNINSTALL -> d.app?.let { game.uninstall(it) }
            t.code >= 0 -> {
                val slot = if (t.code >= DOCK_BASE) Game.Slot(true, t.code - DOCK_BASE) else Game.Slot(false, t.code)
                val ff = d.fromFolder
                when {
                    t.merge && d.app != null -> game.mergeInto(slot, key, d.from, ff)
                    ff != null -> game.moveOutOfFolder(ff, key, slot)
                    d.from != null -> game.move(d.from, slot)
                    else -> game.place(key, slot)
                }
            }
        }
        game.haptic()
        invalidate()
    }

    /**
     * Where a drop at ([x],[y]) lands: a grid cell, a dock cell (DOCK_BASE + index) or one of the
     * targets along the top. [Drop.merge] is set when an app is right over another app or a folder.
     */
    private fun dropAt(x: Float, y: Float): Drop? {
        val d = drag ?: return null
        if (d.app != null || d.from != null || d.fromFolder != null) {
            if (targets[0].contains(x, y) && (d.from != null || d.fromFolder != null)) return Drop(DROP_REMOVE, false)
            if (d.app != null && targets[1].contains(x, y)) return Drop(DROP_INFO, false)
            if (d.app != null && targets[2].contains(x, y)) return Drop(DROP_UNINSTALL, false)
        }
        // The dragged icon floats above the finger, so test where the icon is.
        val iy = y - iconSize * 0.6f
        val di = dockIndexAt(x, iy)
        if (di >= 0) {
            val r = dockCells[di]
            return Drop(DOCK_BASE + di, canMerge(d, Game.Slot(true, di), r.centerX(), AppIcon.iconCenterY(r, dockIcon, false), x, iy, dockIcon))
        }
        val g = gridIndexAt(x, iy)
        if (g < 0) return null
        val r = cellRect(g)
        return Drop(g, canMerge(d, Game.Slot(false, g), r.centerX(), AppIcon.iconCenterY(r, iconSize, game.save.labels), x, iy, iconSize))
    }

    private fun canMerge(d: Drag, slot: Game.Slot, cx: Float, cy: Float, x: Float, y: Float, size: Float): Boolean {
        if (d.app == null || slot == d.from) return false
        val c = game.cell(slot) ?: return false
        if (c is Game.Cell.Dir && c.folder.id == d.fromFolder) return false
        return hypot(x - cx, y - cy) < size * 0.5f
    }

    /** Called by the bank or a folder: an app held there and dragged out lands on the home screen. */
    fun beginExternalDrag(app: AppEntry, fromFolder: String?, x: Float, y: Float) {
        showPage(Game.PAGE_HOME, animate = false)
        timer.removeCallbacks(longPress)
        downX = x
        downY = y
        drag = Drag(app, null, null, fromFolder, x, y).also { it.moved = true }
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

    // ------------------------------------------------------------------ log page scrolling

    private fun maxLogScroll() = max(0f, log.height - logArea.height())
    private fun logScrollable() = maxLogScroll() > 0f

    override fun computeScroll() {
        if (logScroller.computeScrollOffset()) {
            logScroll = logScroller.currY.toFloat().coerceIn(0f, maxLogScroll())
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val key = "${mapR.toInt()}:${R3.palette.id}:${game.save.world}:${game.save.place}"
        if (map == null || mapKey != key) {
            mapKey = key
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
        val lo = logOffset()
        if (game.save.logPage && lo > -w) {
            c.save()
            c.clipRect(logArea.left + lo - R3.dp(4f), logArea.top, logArea.right + lo + R3.dp(4f), logArea.bottom)
            c.translate(logArea.left + lo, logArea.top - logScroll)
            log.draw(c, logArea.width())
            c.restore()
            logScroll = logScroll.coerceIn(0f, maxLogScroll())
        }
        drawDots(c)
        drawDock(c)
        drawSearch(c)
        drag?.let { if (it.moved) drawDrag(c, it) }
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawHome(c: Canvas) {
        drawClock(c)
        drawMap(c)
        val dragging = drag?.takeIf { it.moved }
        val hover = dragging?.let { dropAt(it.x, it.y) }
        val pressedCell = (pressed as? Target.Cell)?.slot
        val labels = game.save.labels
        for (i in 0 until SaveData.HOME) {
            val r = cellRect(i)
            val slot = Game.Slot(false, i)
            val hidden = dragging != null && dragging.from == slot
            val content = game.cell(slot)
            when {
                content != null && !hidden -> {
                    val press = pressedCell == slot
                    when (content) {
                        is Game.Cell.App -> AppIcon.cell(c, game, content.app, r, iconSize, labels, press)
                        is Game.Cell.Dir -> AppIcon.folderCell(c, game, content.folder, r, iconSize, labels, press)
                    }
                    if (hover?.code == i) {
                        val cy = AppIcon.iconCenterY(r, iconSize, labels)
                        if (hover.merge) AppIcon.mergeRing(c, r.centerX(), cy, iconSize) else AppIcon.slot(c, r, iconSize, labels, true)
                    }
                }
                dragging != null -> AppIcon.slot(c, r, iconSize, labels, hover?.code == i)
            }
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
        R3.text(c, time, clockX, clockY, clockPaint, R3.WHITE, shadow = true)
        if (suffix.isNotEmpty()) R3.text(c, suffix, clockX + clockPaint.measureText(time) + R3.dp(6f), clockY, ampmPaint, R3.hi, shadow = true)
        val date = "${dayFormat.format(cal.time)}, ${cal.get(Calendar.DAY_OF_MONTH)} ${monthFormat.format(cal.time)}"
        R3.text(c, date, clockX + R3.dp(2f), dateY, datePaint, R3.hi, shadow = true)

        // Life points (the battery), then total and combat level.
        val s = game.stats
        val frac = s.battery / 100f
        val col = if (s.charging) R3.LIFE_OK else R3.lifeColor(frac)
        val hx = clockX + R3.dp(10f)
        Glyphs.draw(c, Glyph.HEART, hx, statusY - R3.dp(5f), R3.dp(17f), col, 1.4f, shadow = true)
        tmp.set(hx + R3.dp(13f), statusY - R3.dp(10f), hx + R3.dp(77f), statusY - R3.dp(1f))
        R3.bar(c, tmp, frac, col)
        var sx = tmp.right + R3.dp(8f)
        val pct = "${s.battery}%" + if (s.charging) " +" else ""
        R3.text(c, pct, sx, statusY, statusPaint, R3.WHITE, shadow = true)
        sx += statusPaint.measureText(pct)
        lifeBox.set(0f, statusY - R3.dp(18f), sx + R3.dp(6f), statusY + R3.dp(8f))
        val rest = "   Total ${game.totalLevel()}  ·  Combat ${game.combatLevel()}"
        R3.text(c, rest, sx, statusY, statusPaint, R3.TEXT, shadow = true)
        sx += statusPaint.measureText(rest)
        statusBox.set(lifeBox.right, statusY - R3.dp(18f), sx + R3.dp(8f), statusY + R3.dp(8f))
        val next = game.save.quests.firstOrNull { !it.done }
        if (next != null && !landscape) {
            val label = R3.ellipsize(next.title, questPaint, width - clockX - R3.dp(44f))
            Glyphs.draw(c, Glyph.BOOK, clockX + R3.dp(8f), questY - R3.dp(5f), R3.dp(15f), R3.hi, 1.6f, shadow = true)
            R3.text(c, label, clockX + R3.dp(22f), questY, questPaint, R3.hi, shadow = true)
            questBox.set(0f, questY - R3.dp(18f), clockX + R3.dp(30f) + questPaint.measureText(label), questY + R3.dp(8f))
        } else {
            questBox.setEmpty()
        }
    }

    fun worldHop() {
        map = null
        invalidate()
    }

    fun themeChanged() {
        map = null
        invalidate()
    }

    /** A little top-down Gielinor: grass, a river, a road, a walled building and some trees. */
    private fun buildMap(): Bitmap? {
        val n = (mapR * 2).toInt()
        if (n <= 0) return null
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = Random(game.save.world * 131 + game.save.place * 7)
        c.drawColor(0xFF3E6B33.toInt())
        repeat(40) {
            fill.color = if (r.nextBoolean()) 0xFF477A3A.toInt() else 0xFF35602C.toInt()
            c.drawCircle(r.nextFloat() * n, r.nextFloat() * n, n * (0.04f + r.nextFloat() * 0.08f), fill)
        }
        val rx = n * (0.15f + r.nextFloat() * 0.3f)
        val path = Path()
        path.moveTo(rx, 0f)
        path.cubicTo(rx + n * 0.25f, n * 0.3f, rx - n * 0.2f, n * 0.65f, rx + n * 0.1f, n.toFloat())
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = 0xFF3B5F9C.toInt()
        stroke.strokeWidth = n * 0.11f
        c.drawPath(path, stroke)
        stroke.color = 0xFF2C4A86.toInt()
        stroke.strokeWidth = n * 0.07f
        c.drawPath(path, stroke)
        val ry = n * (0.55f + r.nextFloat() * 0.2f)
        path.reset()
        path.moveTo(0f, ry)
        path.quadTo(n * 0.5f, ry - n * 0.15f, n.toFloat(), ry + n * 0.05f)
        stroke.color = 0xFF7A6A4C.toInt()
        stroke.strokeWidth = n * 0.05f
        c.drawPath(path, stroke)
        val bx = n * (0.5f + r.nextFloat() * 0.18f)
        val by = n * (0.18f + r.nextFloat() * 0.15f)
        val bw = n * 0.22f
        fill.color = 0xFF6E6E6E.toInt()
        c.drawRect(bx, by, bx + bw, by + bw * 0.8f, fill)
        stroke.color = 0xFFF2F2F2.toInt()
        stroke.strokeWidth = R3.dp(1.2f)
        stroke.strokeCap = Paint.Cap.BUTT
        c.drawRect(bx, by, bx + bw, by + bw * 0.8f, stroke)
        repeat(14) {
            val tx = r.nextFloat() * n
            val ty = r.nextFloat() * n
            fill.color = 0xFF1F4A18.toInt()
            c.drawCircle(tx, ty, n * 0.035f, fill)
        }
        repeat(5) {
            fill.color = 0xFFF3E04A.toInt()
            c.drawCircle(n * (0.1f + r.nextFloat() * 0.8f), n * (0.1f + r.nextFloat() * 0.8f), R3.dp(1.6f), fill)
        }
        fill.color = 0xFFE04A3A.toInt()
        c.drawCircle(n * (0.2f + r.nextFloat() * 0.6f), n * (0.2f + r.nextFloat() * 0.6f), R3.dp(1.6f), fill)
        fill.color = 0xFFFFFFFF.toInt()
        c.drawRect(n / 2f - R3.dp(2f), n / 2f - R3.dp(2f), n / 2f + R3.dp(2f), n / 2f + R3.dp(2f), fill)
        return b
    }

    private fun drawMap(c: Canvas) {
        val m = map ?: return
        val cx = mapCx
        val cy = mapCy
        val r = mapR
        fill.shader = null
        fill.color = R3.withAlpha(R3.BLACK, 0x70)
        fill.setShadowLayer(R3.dp(6f), 0f, R3.dp(2f), 0xA0000000.toInt())
        c.drawCircle(cx, cy, r + R3.dp(5f), fill)
        fill.clearShadowLayer()
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawBitmap(m, cx - r, cy - r, bmpPaint)
        c.restore()
        stroke.shader = android.graphics.LinearGradient(0f, cy - r, 0f, cy + r, R3.frameLight, R3.frameDark, android.graphics.Shader.TileMode.CLAMP)
        stroke.strokeWidth = R3.dp(4f)
        c.drawCircle(cx, cy, r + R3.dp(2f), stroke)
        stroke.shader = null
        R3.stud(c, cx, cy - r - R3.dp(2f), R3.dp(5f))
        R3.text(c, "N", cx, cy - r + R3.dp(1.5f), northPaint, R3.BLACK)
        R3.text(c, game.place.name, cx, cy + r + R3.dp(20f), placePaint, R3.hi, shadow = true)
        R3.text(c, "World ${game.save.world}", cx, cy + r + R3.dp(33f), worldPaint, R3.TEXT, shadow = true)
    }

    private fun drawDots(c: Canvas) {
        if (!game.save.logPage) return
        val cx = if (landscape) (dock.left + dock.right) / 2f else width / 2f
        val pos = currentPos()
        for (i in 0..1) {
            val x = cx + (i - 0.5f) * R3.dp(20f)
            if (abs(pos - i) < 0.5f) R3.stud(c, x, dotsY, R3.dp(5f)) else R3.diamond(c, x, dotsY, R3.dp(3.5f), R3.withAlpha(R3.WHITE, 0x70))
        }
    }

    private fun drawDock(c: Canvas) {
        R3.panel(c, dock, R3.withAlpha(R3.bg, 0xC8), R3.dp(12f))
        val dragging = drag?.takeIf { it.moved }
        val hover = dragging?.let { dropAt(it.x, it.y) }
        val pressedCell = (pressed as? Target.Cell)?.slot
        for (i in 0 until SaveData.DOCK) {
            val r = dockCells[i]
            val slot = Game.Slot(true, i)
            val hidden = dragging != null && dragging.from == slot
            val content = game.cell(slot)
            val cy = AppIcon.iconCenterY(r, dockIcon, false)
            if (content != null && !hidden) {
                when (content) {
                    is Game.Cell.App -> AppIcon.cell(c, game, content.app, r, dockIcon, false, pressedCell == slot)
                    is Game.Cell.Dir -> AppIcon.folderCell(c, game, content.folder, r, dockIcon, false, pressedCell == slot)
                }
                if (hover?.code == DOCK_BASE + i && hover.merge) AppIcon.mergeRing(c, r.centerX(), cy, dockIcon)
            } else if (dragging != null) {
                AppIcon.slot(c, r, dockIcon, false, hover?.code == DOCK_BASE + i)
            }
            // Action-bar key numbers, because the dock is your ability bar now.
            R3.text(c, "${i + 1}", r.left + R3.dp(10f), r.top + R3.dp(15f), keyPaint, R3.withAlpha(R3.hi, 0x90))
        }
    }

    private fun drawSearch(c: Canvas) {
        val p = pressed === Target.Search
        R3.panel(c, search, R3.withAlpha(R3.bg, if (p) 0xF4 else 0xD8), R3.dp(22f), studs = false)
        Glyphs.draw(c, Glyph.SEARCH, search.left + R3.dp(24f), search.centerY(), R3.dp(19f), R3.hi, 2f)
        val by = R3.baseline(searchPaint, search.top, search.bottom)
        R3.text(c, "Search your bank", search.left + R3.dp(44f), by, searchPaint, R3.withAlpha(R3.TEXT, 0xC8))
        if (!landscape) R3.text(c, "::help", search.right - R3.dp(18f), by, searchHint, R3.withAlpha(R3.MUTED, 0xC0))
    }

    private fun drawDrag(c: Canvas, d: Drag) {
        val hover = dropAt(d.x, d.y)
        val labels = arrayOf("Remove", "App info", "Uninstall")
        val codes = intArrayOf(DROP_REMOVE, DROP_INFO, DROP_UNINSTALL)
        for (i in 0..2) {
            if (i == 0 && d.from == null && d.fromFolder == null) continue
            if (i > 0 && d.app == null) continue
            val r = targets[i]
            val on = hover?.code == codes[i]
            R3.button(c, r, pressed = on, lit = on)
            R3.text(c, labels[i], r.centerX(), R3.baseline(targetPaint, r.top, r.bottom), targetPaint, if (i == 2) R3.DANGER else R3.hi)
        }
        val iy = d.y - iconSize * 0.6f
        val app = d.app
        val f = d.folder
        if (app != null) AppIcon.icon(c, game, app, d.x, iy, iconSize * 1.1f, alpha = 235)
        else if (f != null) AppIcon.folder(c, game, f, d.x, iy, iconSize * 1.1f, alpha = 235)
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
