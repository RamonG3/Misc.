package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ramong.runelauncher.core.Content
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.Palette
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Launcher settings: a plain scrolling list, dressed as an RS3 options window. */
@SuppressLint("ViewConstructor")
class SettingsView(context: Context, private val game: Game) : View(context) {
    private var topInset = 0
    private var bottomInset = 0
    private var openedAt = 0L
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private val frameBox = RectF()
    private val closeBtn = RectF()
    private val list = RectF()
    private val row = RectF()

    private val titlePaint = R3.paint(18f, R3.Face.TITLE_BOLD, Paint.Align.CENTER, 0.03f)
    private val section = R3.paint(14f, R3.Face.TITLE_BOLD, spacing = 0.03f)
    private val label = R3.paint(16f, R3.Face.BOLD)
    private val note = R3.paint(12.5f, R3.Face.BODY)
    private val value = R3.paint(14f, R3.Face.BOLD, Paint.Align.RIGHT)
    private val about = R3.paint(11.5f, R3.Face.BODY, Paint.Align.CENTER)

    val showing: Boolean get() = visibility == VISIBLE

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        layoutParts()
    }

    fun open() {
        openedAt = SystemClock.uptimeMillis()
        scroll = 0f
        scroller.forceFinished(true)
        visibility = VISIBLE
        layoutParts()
        invalidate()
    }

    fun hide(): Boolean {
        if (visibility != VISIBLE) return false
        visibility = INVISIBLE
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutParts()

    private fun layoutParts() {
        val w = width.toFloat()
        val h = height.toFloat()
        frameBox.set(R3.dp(6f), topInset + R3.dp(6f), w - R3.dp(6f), h - bottomInset - R3.dp(6f))
        closeBtn.set(frameBox.right - R3.dp(50f), frameBox.top + R3.dp(4f), frameBox.right - R3.dp(8f), frameBox.top + R3.dp(42f))
        list.set(frameBox.left + R3.dp(16f), frameBox.top + R3.dp(48f), frameBox.right - R3.dp(16f), frameBox.bottom - R3.dp(8f))
    }

    // ------------------------------------------------------------------ rows

    private sealed class Row(val h: Float) {
        class Head(val text: () -> String) : Row(R3.dp(40f))
        class Opt(val name: String, val note: String, val value: () -> String, val danger: Boolean = false, val run: () -> Unit) :
            Row(if (note.isEmpty()) R3.dp(50f) else R3.dp(62f))
        class Theme(val p: Palette) : Row(R3.dp(60f))
        class About : Row(R3.dp(70f))
    }

    private fun on(b: Boolean) = if (b) "On" else "Off"

    private val rows: List<Row> by lazy {
        val s = game.save
        listOf(
            Row.Head { "Look" },
            Row.Opt("Background", "Gielinor drawn live behind your apps, or your own wallpaper.", { if (s.scene == 0) "Gielinor" else "Wallpaper" }) { s.scene = 1 - s.scene; done() },
            Row.Opt("Icon style", "Gilded sets icons in a gold rim. Old School turns them into pixel sprites.",
                { arrayOf("Stock", "Gilded", "Old School")[s.iconStyle.coerceIn(0, 2)] }) { game.setIconStyle((s.iconStyle + 1) % 3) },
            Row.Opt("App labels", "", { on(s.labels) }) { s.labels = !s.labels; done() },
            Row.Opt("XP drops", "Little numbers that float up when you open an app.", { on(s.xpDrops) }) { s.xpDrops = !s.xpDrops; done() },
            Row.Head { "Interface theme" },
        ) + Content.palettes.map { Row.Theme(it) } + listOf(
            Row.Head { "Home screen" },
            Row.Opt("Adventurer's Log", "Skills, quick settings, music and quests, one swipe right of home.", { on(s.logPage) }) {
                s.logPage = !s.logPage
                if (!s.logPage) game.ui.showPage(Game.PAGE_HOME)
                done()
            },
            Row.Opt("24-hour clock", "", { on(s.clock24) }) { s.clock24 = !s.clock24; done() },
            Row.Opt("Haptics", "", { on(s.haptics) }) { s.haptics = !s.haptics; done() },
            Row.Head { "Account" },
            Row.Opt("Display name", "", { s.name }) { game.askName() },
            Row.Opt("World hop", "A new world and a new place on the minimap.", { "World ${s.world}" }) { game.worldHop() },
            Row.Head { "System" },
            Row.Opt("Default home app", "Pick which launcher Home opens.", { "" }) { game.quit() },
            Row.Opt("Change wallpaper", "", { "" }) { game.actions.wallpaper() },
            Row.Opt("Android settings", "", { "" }) { game.actions.settings(Settings.ACTION_SETTINGS) },
            Row.Opt("Reset XP", "Skills back to level 1. Your home screen and folders stay.", { "" }, danger = true) { game.confirmReset() },
            Row.About(),
        )
    }

    private fun done() {
        game.haptic()
        game.save.save()
        game.ui.invalidateAll()
        invalidate()
    }

    private fun contentHeight() = rows.sumOf { it.h.toDouble() }.toFloat() + R3.dp(16f)
    private fun maxScroll() = max(0f, contentHeight() - list.height())

    private var versionName: String? = null
    private fun version(): String {
        versionName?.let { return it }
        val v = try {
            game.ctx.packageManager.getPackageInfo(game.ctx.packageName, 0).versionName ?: "2.0"
        } catch (e: Exception) {
            "2.0"
        }
        versionName = v
        return v
    }

    // ------------------------------------------------------------------ touch

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressedRow = -1
    private val swipe = Swipe()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private fun rowAt(y: Float): Int {
        if (y < list.top) return -1
        var top = list.top - scroll
        rows.forEachIndexed { i, r ->
            if (y >= top && y < top + r.h) return i
            top += r.h
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = x
                downY = y
                lastY = y
                dragging = false
                pressedRow = rowAt(y)
                swipe.down(e)
            }
            MotionEvent.ACTION_MOVE -> {
                swipe.move(e)
                if (!dragging && hypot(x - downX, y - downY) > slop) {
                    dragging = true
                    pressedRow = -1
                }
                if (dragging) scroll = (scroll - (y - lastY)).coerceIn(0f, maxScroll())
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                swipe.up(e, minFling)
                if (dragging) {
                    if (x - downX > R3.dp(90f) && abs(x - downX) > abs(y - downY) * 2 && swipe.vx > 0f) {
                        hide()
                    } else {
                        scroller.fling(0, scroll.toInt(), 0, -swipe.vy.toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                } else if (closeBtn.contains(x, y) || !frameBox.contains(x, y)) {
                    game.haptic()
                    hide()
                } else {
                    when (val r = rows.getOrNull(rowAt(y))) {
                        is Row.Opt -> {
                            game.haptic()
                            r.run()
                        }
                        is Row.Theme -> {
                            game.haptic()
                            game.setTheme(r.p.id)
                        }
                        else -> Unit
                    }
                }
                pressedRow = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRow = -1
                dragging = false
                swipe.clear()
            }
        }
        invalidate()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (!showing) return
        val w = width.toFloat()
        val h = height.toFloat()
        val k = ((SystemClock.uptimeMillis() - openedAt) / 180f).coerceIn(0f, 1f)
        val ease = 1f - (1f - k) * (1f - k)
        R3.rect(c, 0f, 0f, w, h, R3.withAlpha(R3.BLACK, (0x99 * ease).toInt()))
        c.save()
        c.translate((1f - ease) * R3.dp(40f), 0f)
        R3.panel(c, frameBox, R3.withAlpha(R3.bg, 0xF6), R3.dp(10f), titleText = "Settings", titlePaint = titlePaint, titleH = R3.dp(44f))
        Glyphs.draw(c, Glyph.CLOSE, closeBtn.centerX(), closeBtn.centerY(), R3.dp(18f), R3.hi, 2.2f)

        scroll = scroll.coerceIn(0f, maxScroll())
        c.save()
        c.clipRect(0f, list.top, w, list.bottom)
        var y = list.top - scroll
        rows.forEachIndexed { i, r ->
            if (y + r.h >= list.top && y <= list.bottom) drawRow(c, r, y, i == pressedRow)
            y += r.h
        }
        c.restore()
        val ms = maxScroll()
        if (ms > 0f) {
            val th = max(R3.dp(28f), list.height() * list.height() / contentHeight())
            val ty = list.top + (list.height() - th) * (scroll / ms)
            R3.round(c, RectF(frameBox.right - R3.dp(11f), ty, frameBox.right - R3.dp(7f), ty + th), R3.dp(2f), R3.frame)
        }
        c.restore()
        if (k < 1f) postInvalidateOnAnimation()
    }

    private fun drawRow(c: Canvas, r: Row, top: Float, pressed: Boolean) {
        row.set(list.left, top, list.right, top + r.h)
        when (r) {
            is Row.Head -> {
                val by = top + R3.dp(28f)
                R3.text(c, r.text(), row.left + R3.dp(2f), by, section, R3.hi)
                R3.ornament(c, row.left + R3.dp(12f) + section.measureText(r.text()), row.right, by - R3.dp(5f))
            }
            is Row.Opt -> {
                if (pressed) R3.round(c, row, R3.dp(6f), R3.withAlpha(R3.hi, 0x1E))
                val by = if (r.note.isEmpty()) R3.baseline(label, row.top, row.bottom) else row.top + R3.dp(26f)
                R3.text(c, r.name, row.left + R3.dp(4f), by, label, if (r.danger) R3.DANGER else R3.WHITE)
                if (r.note.isNotEmpty()) R3.text(c, R3.ellipsize(r.note, note, row.width() * 0.72f), row.left + R3.dp(4f), by + R3.dp(19f), note, R3.MUTED)
                val v = r.value()
                if (v.isNotEmpty()) R3.text(c, v, row.right - R3.dp(6f), R3.baseline(value, row.top, row.bottom), value, R3.accent)
                else Glyphs.draw(c, Glyph.NEXT, row.right - R3.dp(12f), row.centerY(), R3.dp(12f), if (r.danger) R3.DANGER else R3.frameLight, 2f)
                R3.line(c, row.left, row.bottom - R3.px, row.right, row.bottom - R3.px, R3.withAlpha(R3.frame, 0x26))
            }
            is Row.Theme -> drawTheme(c, r.p, pressed)
            is Row.About -> {
                R3.text(c, "RuneLauncher v${version()} · a fan project", row.centerX(), row.top + R3.dp(30f), about, R3.MUTED)
                R3.text(c, "Not affiliated with Jagex. RuneScape is a trademark of Jagex Ltd.", row.centerX(), row.top + R3.dp(46f), about, R3.withAlpha(R3.MUTED, 0xA0))
            }
        }
    }

    private fun drawTheme(c: Canvas, p: Palette, pressed: Boolean) {
        val equipped = game.save.theme == p.id
        val box = RectF(row.left, row.top + R3.dp(4f), row.right, row.bottom - R3.dp(4f))
        R3.round(c, box, R3.dp(8f), R3.mix(p.bg, if (pressed) p.frame else R3.BLACK, if (pressed) 0.2f else 0.15f))
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (equipped) R3.dp(2f) else R3.px
            color = if (equipped) p.hi else R3.withAlpha(p.frame, 0x90)
        }
        c.drawRoundRect(box, R3.dp(8f), R3.dp(8f), stroke)
        var x = box.left + R3.dp(14f)
        for (col in listOf(p.frame, p.hi, p.accent)) {
            val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
            c.drawCircle(x + R3.dp(6f), box.centerY(), R3.dp(7f), f)
            x += R3.dp(17f)
        }
        val by = R3.baseline(label, box.top, box.bottom) - R3.dp(7f)
        label.color = p.hi
        c.drawText(p.name, x + R3.dp(8f), by, label)
        R3.text(c, R3.ellipsize(p.tagline, note, box.right - x - R3.dp(80f)), x + R3.dp(8f), by + R3.dp(17f), note, R3.MUTED)
        if (equipped) R3.text(c, "In use", box.right - R3.dp(12f), R3.baseline(value, box.top, box.bottom), value, p.accent)
    }
}
