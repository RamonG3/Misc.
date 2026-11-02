package com.ramong.cyberlauncher.ui

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
import com.ramong.cyberlauncher.core.Choice
import com.ramong.cyberlauncher.core.ChoiceOption
import com.ramong.cyberlauncher.core.Content
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Lifepath
import com.ramong.cyberlauncher.core.Palette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Launcher settings: a plain scrolling list, dressed in the current theme. */
@SuppressLint("ViewConstructor")
class SettingsView(context: Context, private val game: Game) : View(context) {
    private var topInset = 0
    private var bottomInset = 0
    private var openedAt = 0L
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private val header = RectF()
    private val closeBtn = RectF()
    private val list = RectF()
    private val row = RectF()

    private val titlePaint = Cy.paint(22f, Cy.Face.BOLD, spacing = 0.14f)
    private val section = Cy.paint(9.5f, Cy.Face.MONO, spacing = 0.16f)
    private val label = Cy.paint(15f, Cy.Face.BOLD, spacing = 0.03f)
    private val note = Cy.paint(11.5f, Cy.Face.BODY)
    private val value = Cy.paint(11f, Cy.Face.MONO, Paint.Align.RIGHT, 0.08f)
    private val about = Cy.paint(9f, Cy.Face.MONO, Paint.Align.CENTER, 0.06f)

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
        header.set(0f, topInset.toFloat(), w, topInset + Cy.dp(58f))
        closeBtn.set(w - Cy.dp(58f), header.top + Cy.dp(6f), w - Cy.dp(8f), header.bottom - Cy.dp(6f))
        list.set(Cy.dp(16f), header.bottom, w - Cy.dp(16f), h - bottomInset)
    }

    // ------------------------------------------------------------------ rows

    private sealed class Row(val h: Float) {
        class Head(val text: () -> String) : Row(Cy.dp(36f))
        class Opt(val name: String, val note: String, val value: () -> String, val danger: Boolean = false, val run: () -> Unit) :
            Row(if (note.isEmpty()) Cy.dp(48f) else Cy.dp(58f))
        class Theme(val p: Palette) : Row(Cy.dp(56f))
        class Info(val text: () -> String) : Row(Cy.dp(30f))
        class About : Row(Cy.dp(64f))
    }

    private fun on(b: Boolean) = if (b) "ON" else "OFF"

    private val rows: List<Row> by lazy {
        val s = game.save
        listOf(
            Row.Head { "LOOK" },
            Row.Opt("Background", "The animated city, or your own wallpaper.", { if (s.scene == 0) "NIGHT CITY" else "WALLPAPER" }) { s.scene = 1 - s.scene; done() },
            Row.Opt("Icon style", "Chrome adds scanlines and a colour fringe. Holo tints icons to the theme.",
                { arrayOf("STOCK", "CHROME", "HOLO")[s.iconStyle.coerceIn(0, 2)] }) { game.setIconStyle((s.iconStyle + 1) % 3) },
            Row.Opt("App labels", "", { on(s.labels) }) { s.labels = !s.labels; done() },
            Row.Opt("Rarity colours", "A coloured bar under apps you use a lot.", { on(s.rarityGlow) }) { s.rarityGlow = !s.rarityGlow; done() },
            Row.Opt("Rain", "", { on(s.rain) }) { s.rain = !s.rain; done() },
            Row.Opt("Glitch effects", "", { on(s.glitch) }) { s.glitch = !s.glitch; done() },
            Row.Opt("Boot animation", "Plays when the launcher starts.", { on(s.boot) }) { s.boot = !s.boot; done() },
            Row.Head { "HOME SCREEN" },
            Row.Opt("Deck page", "Profile, quick settings, radio and to-dos, one swipe right of home.", { on(s.deck) }) {
                s.deck = !s.deck
                if (!s.deck) game.ui.showPage(Game.PAGE_HOME)
                done()
            },
            Row.Opt("24-hour clock", "", { on(s.clock24) }) { s.clock24 = !s.clock24; done() },
            Row.Opt("Year", "Night City runs 51 years ahead.", { if (s.realYear) "REAL" else "2077-ISH" }) { s.realYear = !s.realYear; done() },
            Row.Opt("Haptics", "", { on(s.haptics) }) { s.haptics = !s.haptics; done() },
            Row.Head { "PROFILE" },
            Row.Opt("Handle", "", { s.name.uppercase(Locale.US) }) { game.askName() },
            Row.Opt("Lifepath", "Changes your colours, not your past.", { game.lifepath()?.title?.uppercase(Locale.US) ?: "NONE" }) { pickLifepath() },
            Row.Info { "LVL ${game.level()}  ·  STREET CRED ${game.credLevel()}  ·  €$ ${Game.number(s.eddies)}  ·  JOBS ${game.jobsDone()}/${game.jobsTotal()}" },
            Row.Head { "THEMES  ·  €$ ${Game.number(s.eddies)}" },
        ) + Content.palettes.map { Row.Theme(it) } + listOf(
            Row.Head { "SYSTEM" },
            Row.Opt("Default home app", "Pick which launcher Home opens.", { "" }) { game.quit() },
            Row.Opt("Change wallpaper", "", { "" }) { game.actions.wallpaper() },
            Row.Opt("Android settings", "", { "" }) { game.actions.settings(Settings.ACTION_SETTINGS) },
            Row.Opt("Reset progress", "Level, Street Cred, eddies and rarity. Your home screen stays.", { "" }, danger = true) { game.confirmReset() },
            Row.About(),
        )
    }

    private fun done() {
        game.haptic()
        game.save.save()
        game.ui.invalidateAll()
        invalidate()
    }

    private fun pickLifepath() {
        game.ui.showChoice(Choice(
            "SYSTEM", "Pick a lifepath. It changes your colours, not your past.",
            Lifepath.values().map { lp -> ChoiceOption("${lp.title}. ${lp.tagline}", primary = lp == game.lifepath()) { game.chooseLifepath(lp) } },
        ))
    }

    private fun contentHeight() = rows.sumOf { it.h.toDouble() }.toFloat() + Cy.dp(16f)
    private fun maxScroll() = max(0f, contentHeight() - list.height())

    private var versionName: String? = null
    private fun version(): String {
        versionName?.let { return it }
        val v = try {
            game.ctx.packageManager.getPackageInfo(game.ctx.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
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
                    val vx = swipe.vx
                    if (x - downX > Cy.dp(90f) && abs(x - downX) > abs(y - downY) * 2 && vx > 0f) {
                        hide()
                    } else {
                        scroller.fling(0, scroll.toInt(), 0, -swipe.vy.toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                } else if (closeBtn.contains(x, y) || (y < header.bottom && x < Cy.dp(60f))) {
                    game.haptic()
                    hide()
                } else {
                    val i = rowAt(y)
                    when (val r = rows.getOrNull(i)) {
                        is Row.Opt -> {
                            game.haptic()
                            r.run()
                        }
                        is Row.Theme -> game.vendor(r.p)
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
        c.save()
        c.translate((1f - ease) * Cy.dp(48f), 0f)
        Cy.rect(c, 0f, 0f, w, h, Cy.withAlpha(Cy.bg, (0xF6 * ease).toInt()))
        Cy.scanlines(c, RectF(0f, 0f, w, h))

        Glyphs.draw(c, Glyph.PREV, Cy.dp(26f), header.centerY(), Cy.dp(14f), Cy.primary, 2f)
        Cy.split(c, "SETTINGS", Cy.dp(46f), Cy.baseline(titlePaint, header.top, header.bottom), titlePaint, Cy.primary, Cy.dp(1.2f), Cy.dp(6f))
        Glyphs.draw(c, Glyph.CLOSE, closeBtn.centerX(), closeBtn.centerY(), Cy.dp(18f), Cy.primary, 2f)
        Cy.line(c, Cy.dp(12f), header.bottom, w - Cy.dp(12f), header.bottom, Cy.faint)

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
            val th = max(Cy.dp(28f), list.height() * list.height() / contentHeight())
            val ty = list.top + (list.height() - th) * (scroll / ms)
            Cy.rect(c, w - Cy.dp(7f), ty, w - Cy.dp(4f), ty + th, Cy.primary)
        }
        c.restore()
        if (k < 1f) postInvalidateOnAnimation()
    }

    private fun drawRow(c: Canvas, r: Row, top: Float, pressed: Boolean) {
        row.set(list.left, top, list.right, top + r.h)
        when (r) {
            is Row.Head -> {
                val by = top + Cy.dp(24f)
                val text = r.text()
                Cy.text(c, text, row.left + Cy.dp(2f), by, section, Cy.dim)
                Cy.line(c, row.left + Cy.dp(10f) + section.measureText(text), by - Cy.dp(3.5f), row.right, by - Cy.dp(3.5f), Cy.faint)
            }
            is Row.Opt -> {
                if (pressed) Cy.rect(c, row, Cy.withAlpha(Cy.primary, 0x1E))
                val by = if (r.note.isEmpty()) Cy.baseline(label, row.top, row.bottom) else row.top + Cy.dp(24f)
                Cy.text(c, r.name, row.left + Cy.dp(4f), by, label, if (r.danger) Cy.DANGER else Cy.WHITE)
                if (r.note.isNotEmpty()) Cy.text(c, Cy.ellipsize(r.note, note, row.width() * 0.72f), row.left + Cy.dp(4f), by + Cy.dp(17f), note, Cy.MUTED)
                val v = r.value()
                if (v.isNotEmpty()) Cy.text(c, v, row.right - Cy.dp(6f), Cy.baseline(value, row.top, row.bottom), value, Cy.accent)
                else Glyphs.draw(c, Glyph.NEXT, row.right - Cy.dp(12f), row.centerY(), Cy.dp(11f), if (r.danger) Cy.DANGER else Cy.dim, 2f)
                Cy.line(c, row.left, row.bottom - Cy.px, row.right, row.bottom - Cy.px, Cy.withAlpha(Cy.primary, 0x18))
            }
            is Row.Theme -> drawTheme(c, r.p, pressed)
            is Row.Info -> Cy.text(c, r.text(), row.left + Cy.dp(4f), Cy.baseline(value, row.top, row.bottom), section, Cy.hi)
            is Row.About -> {
                Cy.text(c, "CYBERLAUNCHER v${version()} · A FAN PROJECT", row.centerX(), row.top + Cy.dp(28f), about, Cy.MUTED)
                Cy.text(c, "NOT AFFILIATED WITH CD PROJEKT RED", row.centerX(), row.top + Cy.dp(42f), about, Cy.withAlpha(Cy.MUTED, 0xA0))
            }
        }
    }

    private fun drawTheme(c: Canvas, p: Palette, pressed: Boolean) {
        val equipped = game.save.theme == p.id
        val owned = p.price == 0 || p.id in game.save.owned
        val box = RectF(row.left, row.top + Cy.dp(4f), row.right, row.bottom - Cy.dp(4f))
        Cy.panel(c, box, Cy.dp(8f), Cy.mix(p.bg, if (pressed) p.primary else 0xFF000000.toInt(), if (pressed) 0.15f else 0.2f),
            if (equipped) p.primary else Cy.withAlpha(p.primary, 0x50), Cy.TR, ticks = false)
        var x = box.left + Cy.dp(12f)
        for (col in listOf(p.primary, p.accent, p.hi)) {
            Cy.rect(c, x, box.centerY() - Cy.dp(7f), x + Cy.dp(9f), box.centerY() + Cy.dp(7f), col)
            x += Cy.dp(12f)
        }
        val by = Cy.baseline(label, box.top, box.bottom) - Cy.dp(6f)
        label.color = p.primary
        c.drawText(p.name.uppercase(Locale.US), x + Cy.dp(8f), by, label)
        Cy.text(c, Cy.ellipsize(p.tagline, note, box.width() * 0.5f), x + Cy.dp(8f), by + Cy.dp(15f), note, Cy.MUTED)
        val status = when {
            equipped -> "IN USE"
            owned -> "OWNED"
            else -> "€$ ${Game.number(p.price.toLong())}"
        }
        val col = when {
            equipped -> p.accent
            owned -> Cy.TEXT
            game.save.eddies >= p.price -> Cy.hi
            else -> Cy.MUTED
        }
        Cy.text(c, status, box.right - Cy.dp(12f), Cy.baseline(value, box.top, box.bottom), value, col)
    }
}
