package com.ramong.cyberlauncher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import com.ramong.cyberlauncher.core.Attr
import com.ramong.cyberlauncher.core.Content
import com.ramong.cyberlauncher.core.Curves
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Tag
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The page left of home: all the 2077 extras as plain, labelled cards. Profile, quick
 * settings, radio, a to-do list and recent apps. Draws in its own content coordinates
 * and records tap targets as it goes.
 */
class DeckPage(private val game: Game) {
    class Hit(val r: RectF, val tap: () -> Unit, val long: ((Float, Float) -> Unit)? = null)

    val hits = ArrayList<Hit>()
    var height = 0f
        private set
    private var flashId: String? = null
    private var flashStart = 0L

    val animating: Boolean
        get() = SystemClock.uptimeMillis() - flashStart < FLASH_MS || game.stats.musicActive

    private val title = Cy.paint(22f, Cy.Face.BOLD, spacing = 0.14f)
    private val hint = Cy.paint(9f, Cy.Face.MONO, spacing = 0.12f)
    private val cardTitle = Cy.paint(9.5f, Cy.Face.MONO, spacing = 0.14f)
    private val cardInfo = Cy.paint(9.5f, Cy.Face.MONO, Paint.Align.RIGHT, 0.1f)
    private val name = Cy.paint(21f, Cy.Face.BOLD, spacing = 0.04f)
    private val big = Cy.paint(22f, Cy.Face.BOLD, Paint.Align.RIGHT)
    private val mono = Cy.paint(9.5f, Cy.Face.MONO, spacing = 0.06f)
    private val monoRight = Cy.paint(9.5f, Cy.Face.MONO, Paint.Align.RIGHT, 0.06f)
    private val attrName = Cy.paint(8.5f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val attrNum = Cy.paint(16f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val tileLabel = Cy.paint(11.5f, Cy.Face.BOLD, Paint.Align.CENTER, 0.04f)
    private val tileHack = Cy.paint(7.5f, Cy.Face.MONO, Paint.Align.CENTER, 0.06f)
    private val freq = Cy.paint(26f, Cy.Face.BOLD)
    private val fm = Cy.paint(10f, Cy.Face.MONO, spacing = 0.1f)
    private val station = Cy.paint(14f, Cy.Face.BOLD, spacing = 0.06f)
    private val body = Cy.paint(12f, Cy.Face.BODY)
    private val task = Cy.paint(14f, Cy.Face.BOLD, spacing = 0.02f)
    private val button = Cy.paint(10f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val footer = Cy.paint(9f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val card = RectF()
    private val tmp = RectF()

    fun hitAt(x: Float, y: Float): Hit? = hits.lastOrNull { it.r.contains(x, y) }

    private fun hit(l: Float, t: Float, r: Float, b: Float, tap: () -> Unit, long: ((Float, Float) -> Unit)? = null) {
        hits += Hit(RectF(l, t, r, b), tap, long)
    }

    /** Draws everything at (0, 0) with the given [w]idth. [height] holds the total afterwards. */
    fun draw(c: Canvas, w: Float) {
        hits.clear()
        var y = Cy.dp(4f)
        Cy.glow(c, "DECK", Cy.dp(2f), y + Cy.dp(22f), title, Cy.primary, Cy.dp(6f))
        Cy.text(c, "YOUR CYBERDECK · SWIPE LEFT FOR HOME", Cy.dp(3f), y + Cy.dp(38f), hint, Cy.MUTED)
        y += Cy.dp(52f)
        y = profile(c, w, y) + GAP
        y = quick(c, w, y) + GAP
        y = radio(c, w, y) + GAP
        y = tasks(c, w, y) + GAP
        y = recent(c, w, y) + GAP
        val f = "JOBS ${game.jobsDone()}/${game.jobsTotal()} · ${Game.number(game.save.totalLaunches)} LAUNCHES · ${game.legendaries()} LEGENDARY"
        Cy.text(c, f, w / 2f, y + Cy.dp(12f), footer, Cy.MUTED)
        height = y + Cy.dp(28f)
    }

    private fun cardFrame(c: Canvas, w: Float, top: Float, h: Float, label: String, info: String = ""): RectF {
        card.set(0f, top, w, top + h)
        Cy.panel(c, card, Cy.dp(12f), Cy.withAlpha(Cy.bg, 0xE4), Cy.dim, Cy.TR or Cy.BL)
        Cy.text(c, "◢ $label", Cy.dp(14f), top + Cy.dp(20f), cardTitle, Cy.primary)
        if (info.isNotEmpty()) Cy.text(c, info, w - Cy.dp(16f), top + Cy.dp(20f), cardInfo, Cy.dim)
        return card
    }

    private fun profile(c: Canvas, w: Float, top: Float): Float {
        val h = Cy.dp(158f)
        cardFrame(c, w, top, h, "PROFILE", game.lifepath()?.title?.uppercase(Locale.US) ?: "")
        val save = game.save
        val lv = game.level()
        val x = Cy.dp(14f)
        val r = w - Cy.dp(16f)
        var y = top + Cy.dp(48f)
        val handle = save.name.uppercase(Locale.US)
        Cy.glow(c, handle, x, y, name, Cy.WHITE, Cy.dp(4f))
        hit(0f, top + Cy.dp(26f), x + name.measureText(handle) + Cy.dp(16f), y + Cy.dp(8f), { game.askName() })
        Cy.glow(c, "$lv", r, y, big, Cy.primary, Cy.dp(5f))
        Cy.text(c, "LVL", r - big.measureText("$lv") - Cy.dp(6f) - mono.measureText("LVL"), y - Cy.dp(1f), mono, Cy.dim)
        y += Cy.dp(16f)
        Cy.text(c, "${game.district.first} · ${game.district.second}", x, y, mono, Cy.MUTED)
        val next = if (lv >= Curves.MAX_LEVEL) "MAX LEVEL" else
            "${Game.number(save.xp - Curves.xpForLevel(lv))} / ${Game.number(Curves.xpForLevel(lv + 1) - Curves.xpForLevel(lv))} XP"
        Cy.text(c, next, r, y, monoRight, Cy.MUTED)
        y += Cy.dp(7f)
        tmp.set(x, y, r, y + Cy.dp(3f))
        Cy.bar(c, tmp, game.levelProgress(), Cy.primary)
        y += Cy.dp(18f)
        Cy.text(c, "STREET CRED ${game.credLevel()}", x, y, mono, Cy.hi)
        Cy.text(c, "€$ ${Game.number(save.eddies)}", r, y, monoRight, Cy.hi)
        tmp.set(x, y + Cy.dp(5f), x + (r - x) * 0.45f, y + Cy.dp(7f))
        Cy.bar(c, tmp, game.credProgress(), Cy.hi)
        y += Cy.dp(22f)
        val cw = (r - x) / Attr.values().size
        Attr.values().forEachIndexed { i, a ->
            val cx = x + cw * (i + 0.5f)
            Cy.text(c, a.short, cx, y, attrName, Cy.dim)
            Cy.text(c, "${game.attrLevel(a)}", cx, y + Cy.dp(19f), attrNum, Cy.WHITE)
            tmp.set(cx - cw * 0.32f, y + Cy.dp(25f), cx + cw * 0.32f, y + Cy.dp(27f))
            Cy.bar(c, tmp, game.attrProgress(a), Cy.accent)
            val fx = x + cw * i
            hit(fx, y - Cy.dp(12f), fx + cw, y + Cy.dp(30f), {
                game.feed(Tag.XP, "<col=pr>${a.title} ${game.attrLevel(a)}</col> (${a.skill}). Trained by ${a.trains}.")
            })
        }
        return top + h
    }

    private fun quick(c: Canvas, w: Float, top: Float): Float {
        val tileH = Cy.dp(66f)
        val h = Cy.dp(32f) + tileH * 2 + Cy.dp(8f)
        cardFrame(c, w, top, h, "QUICK SETTINGS", "RAM ${game.ramFree().toInt()}/8")
        val now = SystemClock.uptimeMillis()
        val x0 = Cy.dp(8f)
        val tw = (w - Cy.dp(16f)) / 5f
        Content.quickhacks.forEachIndexed { i, q ->
            val col = i % 5
            val row = i / 5
            tmp.set(x0 + col * tw + Cy.dp(3f), top + Cy.dp(30f) + row * tileH + Cy.dp(3f), x0 + (col + 1) * tw - Cy.dp(3f), top + Cy.dp(30f) + (row + 1) * tileH - Cy.dp(3f))
            val on = q.id == "optics" && game.actions.torchOn
            val flash = flashId == q.id && now - flashStart < FLASH_MS
            Cy.panel(c, tmp, Cy.dp(6f), if (flash) Cy.withAlpha(Cy.accent, 0x40) else Cy.withAlpha(Cy.panelLight, 0xE0),
                if (on) Cy.hi else Cy.withAlpha(Cy.primary, 0x40), Cy.TR, ticks = false)
            val glyph = when (q.id) {
                "ping" -> Glyph.PING
                "optics" -> Glyph.OPTICS
                "sonic" -> Glyph.SONIC
                "wipe" -> Glyph.WIPE
                "cripple" -> Glyph.CRIPPLE
                "malfunction" -> Glyph.MALFUNCTION
                "overheat" -> Glyph.OVERHEAT
                "backup" -> Glyph.BACKUP
                "reset" -> Glyph.RESET
                else -> Glyph.SETTINGS
            }
            Glyphs.draw(c, glyph, tmp.centerX(), tmp.top + Cy.dp(19f), Cy.dp(20f), if (on) Cy.hi else Cy.accent, 1.7f)
            Cy.text(c, Cy.ellipsize(q.label.uppercase(Locale.US), tileLabel, tmp.width() - Cy.dp(4f)), tmp.centerX(), tmp.top + Cy.dp(43f), tileLabel, Cy.WHITE)
            Cy.text(c, Cy.ellipsize(q.hack.uppercase(Locale.US), tileHack, tmp.width() - Cy.dp(4f)), tmp.centerX(), tmp.top + Cy.dp(54f), tileHack, Cy.withAlpha(Cy.accent, 0xA8))
            val r = RectF(tmp)
            hit(r.left, r.top, r.right, r.bottom, {
                flashId = q.id
                flashStart = SystemClock.uptimeMillis()
                game.quickhack(q)
            })
        }
        return top + h
    }

    private fun radio(c: Canvas, w: Float, top: Float): Float {
        val h = Cy.dp(136f)
        val playing = game.stats.musicActive
        cardFrame(c, w, top, h, "RADIO", if (playing) "ON AIR" else "STANDBY")
        val st = Content.stations[game.save.station.coerceIn(0, Content.stations.size - 1)]
        val x = Cy.dp(14f)
        Cy.glow(c, st.freq, x, top + Cy.dp(54f), freq, Cy.hi, Cy.dp(6f))
        Cy.text(c, "FM", x + freq.measureText(st.freq) + Cy.dp(5f), top + Cy.dp(53f), fm, Cy.hi)
        Cy.text(c, st.name.uppercase(Locale.US), x, top + Cy.dp(73f), station, Cy.WHITE)
        Cy.text(c, Cy.ellipsize(st.genre, body, w * 0.62f), x, top + Cy.dp(89f), body, Cy.MUTED)
        hit(0f, top + Cy.dp(28f), w * 0.66f, top + Cy.dp(94f), {
            game.haptic()
            if (!game.actions.music()) game.feed(Tag.ERR, "No music app. Night City is quiet tonight.")
        })
        // Equaliser: it only dances while something is actually playing.
        val t = SystemClock.uptimeMillis() / 1000f
        val bars = 10
        val bw = Cy.dp(4f)
        val ex = w - Cy.dp(16f) - bars * (bw + Cy.dp(2.5f))
        val base = top + Cy.dp(82f)
        for (k in 0 until bars) {
            val hgt = if (playing) Cy.dp(5f) + abs(sin(t * (3f + k * 0.7f) + k)) * Cy.dp(30f) else Cy.dp(3f) + (k % 3) * Cy.dp(1.5f)
            val bx = ex + k * (bw + Cy.dp(2.5f))
            Cy.rect(c, bx, base - hgt, bx + bw, base, if (playing) Cy.accent else Cy.withAlpha(Cy.accent, 0x50))
        }
        val by = top + Cy.dp(100f)
        val bh = Cy.dp(26f)
        val glyphs = listOf(Glyph.PREV, if (playing) Glyph.PAUSE else Glyph.PLAY, Glyph.NEXT)
        val codes = listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT)
        val bwid = Cy.dp(46f)
        for (i in 0 until 3) {
            tmp.set(x + i * (bwid + Cy.dp(8f)), by, x + i * (bwid + Cy.dp(8f)) + bwid, by + bh)
            Cy.panel(c, tmp, Cy.dp(5f), Cy.withAlpha(Cy.panelLight, 0xE0), Cy.withAlpha(Cy.primary, if (i == 1) 0xA0 else 0x50), Cy.TR, ticks = false)
            Glyphs.draw(c, glyphs[i], tmp.centerX(), tmp.centerY(), Cy.dp(14f), if (i == 1) Cy.hi else Cy.accent, 2f)
            val code = codes[i]
            hit(tmp.left, tmp.top - Cy.dp(4f), tmp.right, tmp.bottom + Cy.dp(4f), { game.mediaKey(code) })
        }
        tmp.set(w - Cy.dp(16f) - Cy.dp(118f), by, w - Cy.dp(16f), by + bh)
        Cy.panel(c, tmp, Cy.dp(5f), Cy.withAlpha(Cy.panelLight, 0xE0), Cy.withAlpha(Cy.accent, 0x70), Cy.TR, ticks = false)
        Cy.text(c, "NEXT STATION ▸", tmp.centerX(), Cy.baseline(button, tmp.top, tmp.bottom), button, Cy.accent)
        hit(tmp.left, tmp.top - Cy.dp(4f), tmp.right, tmp.bottom + Cy.dp(4f), {
            game.haptic()
            game.nextStation(1)
        })
        return top + h
    }

    private fun tasks(c: Canvas, w: Float, top: Float): Float {
        val list = game.tasks()
        val rowH = Cy.dp(40f)
        val anyDone = list.any { it.done }
        val h = Cy.dp(34f) + (if (list.isEmpty()) Cy.dp(36f) else list.size * rowH) + (if (anyDone) Cy.dp(26f) else 0f) + Cy.dp(6f)
        cardFrame(c, w, top, h, "GIGS · TO-DO")
        tmp.set(w - Cy.dp(16f) - Cy.dp(64f), top + Cy.dp(7f), w - Cy.dp(16f), top + Cy.dp(28f))
        Cy.panel(c, tmp, Cy.dp(4f), Cy.withAlpha(Cy.hi, 0x18), Cy.withAlpha(Cy.hi, 0xB0), Cy.TR, ticks = false)
        Cy.text(c, "+ NEW", tmp.centerX(), Cy.baseline(button, tmp.top, tmp.bottom), button, Cy.hi)
        hit(tmp.left - Cy.dp(6f), tmp.top - Cy.dp(6f), tmp.right + Cy.dp(6f), tmp.bottom + Cy.dp(6f), {
            game.haptic()
            game.newTask()
        })
        var y = top + Cy.dp(34f)
        val x = Cy.dp(14f)
        if (list.isEmpty()) {
            Cy.text(c, "No gigs. Tap + New to add a to-do.", x, y + Cy.dp(20f), body, Cy.MUTED)
        }
        for (j in list) {
            val cy = y + rowH / 2f
            if (j.done) Glyphs.draw(c, Glyph.CHECK, x + Cy.dp(5f), cy, Cy.dp(14f), Cy.MUTED, 2f)
            else Cy.diamond(c, x + Cy.dp(5f), cy, Cy.dp(5f), Cy.accent, filled = false)
            val tx = x + Cy.dp(20f)
            val label = Cy.ellipsize(j.title, task, w - tx - Cy.dp(16f))
            Cy.text(c, label, tx, Cy.baseline(task, y, y + rowH), task, if (j.done) Cy.MUTED else Cy.WHITE)
            if (j.done) Cy.line(c, tx, cy, tx + task.measureText(label), cy, Cy.MUTED, Cy.px)
            Cy.line(c, tx, y + rowH, w - Cy.dp(16f), y + rowH, Cy.withAlpha(Cy.primary, 0x1A))
            hit(0f, y, w, y + rowH, { game.taskTap(j) }, { rx, ry -> game.taskMenu(j, rx, ry) })
            y += rowH
        }
        if (anyDone) {
            Cy.text(c, "CLEAR DONE", w - Cy.dp(16f), y + Cy.dp(18f), monoRight, Cy.dim)
            hit(w - Cy.dp(110f), y, w, y + Cy.dp(26f), {
                game.haptic()
                game.clearDoneTasks()
            })
        }
        return top + h
    }

    private fun recent(c: Canvas, w: Float, top: Float): Float {
        val list = game.recentApps(5)
        val h = Cy.dp(30f) + Cy.dp(84f)
        cardFrame(c, w, top, h, "RECENT")
        if (list.isEmpty()) {
            Cy.text(c, "Nothing yet. Open some apps.", Cy.dp(14f), top + Cy.dp(64f), body, Cy.MUTED)
            return top + h
        }
        val cw = (w - Cy.dp(16f)) / 5f
        list.forEachIndexed { i, app ->
            tmp.set(Cy.dp(8f) + i * cw, top + Cy.dp(30f), Cy.dp(8f) + (i + 1) * cw, top + h - Cy.dp(6f))
            AppIcon.cell(c, game, app, tmp, min(Cy.dp(42f), cw * 0.6f), labels = true, pressed = false)
            hit(tmp.left, tmp.top, tmp.right, tmp.bottom, { game.launch(app) }, { rx, ry -> game.appMenu(app, null, rx, ry) })
        }
        return top + h
    }

    companion object {
        const val FLASH_MS = 700L
        private val GAP get() = Cy.dp(12f)
    }
}
