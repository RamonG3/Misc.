package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.ramong.runelauncher.core.AppEntry
import com.ramong.runelauncher.core.Game
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * An open folder: a window in the middle of the screen with its apps in a grid. Tap the title to
 * rename it, + to add apps. Hold an app for options; drag it out of the window to move it home.
 */
@SuppressLint("ViewConstructor")
class FolderView(context: Context, private val game: Game, private val home: HomeView) : View(context) {
    var folderId: String? = null
        private set
    private var openedAt = 0L
    private val box = RectF()
    private val titleBox = RectF()
    private val addBtn = RectF()
    private val grid = RectF()
    private val cell = RectF()
    private var cols = 4
    private var cellW = 0f
    private var cellH = 0f
    private var iconSize = 0f
    private var apps: List<AppEntry> = emptyList()

    private val titlePaint = R3.paint(17f, R3.Face.TITLE_BOLD, Paint.Align.CENTER, 0.03f)
    private val hint = R3.paint(11.5f, R3.Face.BODY, Paint.Align.CENTER)

    val showing: Boolean get() = visibility == VISIBLE && mode != Mode.DRAGGING_OUT

    fun open(id: String) {
        folderId = id
        openedAt = SystemClock.uptimeMillis()
        alpha = 1f
        visibility = VISIBLE
        layoutParts()
        invalidate()
    }

    fun close(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = INVISIBLE
        folderId = null
        return true
    }

    private fun layoutParts() {
        val f = game.folder(folderId) ?: return
        apps = game.folderApps(f)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        cols = if (w > h * 1.15f) 5 else 4
        val bw = min(w - R3.dp(32f), R3.dp(380f))
        cellW = (bw - R3.dp(24f)) / cols
        cellH = min(cellW * 1.15f, R3.dp(100f))
        iconSize = min(R3.dp(54f), cellW * 0.64f)
        val rows = max(1, ceil(apps.size / cols.toFloat()).toInt())
        val visibleRows = min(rows, 4)
        val bh = R3.dp(52f) + visibleRows * cellH + R3.dp(40f)
        val top = (h - bh) / 2f - R3.dp(20f)
        box.set((w - bw) / 2f, top, (w + bw) / 2f, top + bh)
        titleBox.set(box.left + R3.dp(50f), box.top, box.right - R3.dp(50f), box.top + R3.dp(44f))
        addBtn.set(box.right - R3.dp(48f), box.top + R3.dp(4f), box.right - R3.dp(8f), box.top + R3.dp(40f))
        grid.set(box.left + R3.dp(12f), box.top + R3.dp(52f), box.right - R3.dp(12f), box.top + R3.dp(52f) + visibleRows * cellH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutParts()

    private fun cellRect(i: Int): RectF {
        val col = i % cols
        val row = i / cols
        cell.set(grid.left + col * cellW, grid.top + row * cellH, grid.left + (col + 1) * cellW, grid.top + (row + 1) * cellH)
        return cell
    }

    private fun appAt(x: Float, y: Float): AppEntry? {
        if (!grid.contains(x, y)) return null
        val col = ((x - grid.left) / cellW).toInt()
        val row = ((y - grid.top) / cellH).toInt()
        return if (col in 0 until cols) apps.getOrNull(row * cols + col) else null
    }

    // ------------------------------------------------------------------ touch

    private enum class Mode { IDLE, PENDING, HELD, DRAGGING_OUT }

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var pressed: AppEntry? = null
    private var held: AppEntry? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val timer = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        val a = pressed
        val id = folderId
        if (mode == Mode.PENDING && a != null && id != null) {
            mode = Mode.HELD
            held = a
            pressed = null
            game.appMenu(a, null, id, downX, downY - iconSize)
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                mode = Mode.PENDING
                pressed = appAt(x, y)
                if (pressed != null) timer.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PENDING -> if (hypot(x - downX, y - downY) > slop) {
                    timer.removeCallbacks(longPress)
                    pressed = null
                    mode = Mode.IDLE
                }
                Mode.HELD -> if (hypot(x - downX, y - downY) > slop * 2f) {
                    val a = held
                    val id = folderId
                    if (a != null && id != null) {
                        // Out of the folder and onto the home screen, still under the finger.
                        mode = Mode.DRAGGING_OUT
                        game.ui.hideMenu()
                        alpha = 0f
                        home.beginExternalDrag(a, id, x, y)
                    }
                }
                Mode.DRAGGING_OUT -> home.externalDragMove(x, y)
                else -> Unit
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                timer.removeCallbacks(longPress)
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                when (mode) {
                    Mode.PENDING -> if (!cancel) tap(x, y)
                    Mode.DRAGGING_OUT -> {
                        home.externalDragEnd(x, y, cancel)
                        alpha = 1f
                        close()
                    }
                    else -> Unit
                }
                mode = Mode.IDLE
                pressed = null
                held = null
                invalidate()
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val id = folderId ?: return
        if (!box.contains(x, y)) {
            close()
            return
        }
        if (addBtn.contains(x, y)) {
            game.haptic()
            game.pickForFolder(id)
            return
        }
        if (titleBox.contains(x, y)) {
            game.haptic()
            game.renameFolder(id)
            return
        }
        val a = appAt(x, y) ?: return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val r = RectF(cellRect(apps.indexOf(a)))
        close()
        game.launch(a, Rect((loc[0] + r.left).toInt(), (loc[1] + r.top).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom).toInt()))
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (alpha == 0f) return
        val f = game.folder(folderId)
        if (f == null) {
            close()
            return
        }
        layoutParts()
        val k = ((SystemClock.uptimeMillis() - openedAt) / 160f).coerceIn(0f, 1f)
        val ease = 1f - (1f - k) * (1f - k)
        R3.rect(c, 0f, 0f, width.toFloat(), height.toFloat(), R3.withAlpha(R3.BLACK, (0x99 * ease).toInt()))
        c.save()
        val s = 0.9f + 0.1f * ease
        c.scale(s, s, box.centerX(), box.centerY())
        R3.panel(c, box, R3.withAlpha(R3.bg, 0xF4), R3.dp(12f), titleText = f.name, titlePaint = titlePaint, titleH = R3.dp(44f))
        Glyphs.draw(c, Glyph.PENCIL, box.left + R3.dp(26f), box.top + R3.dp(22f), R3.dp(15f), R3.withAlpha(R3.hi, 0xA0), 1.6f)
        R3.button(c, addBtn)
        Glyphs.draw(c, Glyph.PLUS, addBtn.centerX(), addBtn.centerY(), R3.dp(16f), R3.hi, 2.2f)
        c.save()
        c.clipRect(grid)
        apps.forEachIndexed { i, a ->
            AppIcon.cell(c, game, a, cellRect(i), iconSize, labels = true, pressed = pressed === a)
        }
        c.restore()
        if (apps.isEmpty()) R3.text(c, "Empty. Tap + to add apps.", box.centerX(), grid.centerY(), hint, R3.MUTED)
        R3.text(c, "Hold an app for options. Drag it out to move it.", box.centerX(), box.bottom - R3.dp(16f), hint, R3.MUTED)
        c.restore()
        if (k < 1f) postInvalidateOnAnimation()
    }
}
