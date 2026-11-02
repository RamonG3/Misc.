package com.ramong.cyberlauncher.ui.panels

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import com.ramong.cyberlauncher.core.AppEntry
import com.ramong.cyberlauncher.core.Choice
import com.ramong.cyberlauncher.core.ChoiceOption
import com.ramong.cyberlauncher.core.Content
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Job
import com.ramong.cyberlauncher.core.Lifepath
import com.ramong.cyberlauncher.core.Palette
import com.ramong.cyberlauncher.core.QuickhackDef
import com.ramong.cyberlauncher.core.Tag
import com.ramong.cyberlauncher.ui.Cy
import com.ramong.cyberlauncher.ui.Glyph
import com.ramong.cyberlauncher.ui.Glyphs
import com.ramong.cyberlauncher.ui.ItemDraw
import com.ramong.cyberlauncher.ui.Panel
import com.ramong.cyberlauncher.ui.PanelView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val rowRect = RectF()

/** "2H AGO", "3D AGO", or "ONLINE" for the last quarter of an hour. */
internal fun ago(t: Long): String {
    if (t <= 0L) return "NEVER"
    val m = (System.currentTimeMillis() - t) / 60_000L
    return when {
        m < 15 -> "ONLINE"
        m < 60 -> "${m}M AGO"
        m < 60 * 24 -> "${m / 60}H AGO"
        else -> "${m / (60 * 24)}D AGO"
    }
}

/** Quick settings, each dressed up as a quickhack with a RAM cost. */
class HacksPanel(game: Game, host: PanelView) : Panel(game, host) {
    override val title = "QUICKHACKS"
    override val glyph = Glyph.HACKS
    override val scrollable get() = true
    override val animates get() = SystemClock.uptimeMillis() - flashStart < FLASH_MS
    private val rowH get() = Cy.dp(52f)
    private val cost = Cy.paint(9f, Cy.Face.MONO, Paint.Align.RIGHT, 0.1f)
    private var flash: String? = null
    private var flashStart = 0L

    override fun contentHeight(r: RectF) = Content.quickhacks.size * rowH
    override fun info() = "RAM ${game.ramFree().toInt()}/${game.ramCells()}"

    private fun glyphFor(q: QuickhackDef) = when (q.id) {
        "ping" -> Glyph.PING
        "optics" -> Glyph.OPTICS
        "sonic" -> Glyph.SONIC
        "wipe" -> Glyph.WIPE
        "cripple" -> Glyph.CRIPPLE
        "malfunction" -> Glyph.MALFUNCTION
        "overheat" -> Glyph.OVERHEAT
        "backup" -> Glyph.BACKUP
        "reset" -> Glyph.RESET
        else -> Glyph.BREACH
    }

    override fun draw(c: Canvas, r: RectF) {
        val now = SystemClock.uptimeMillis()
        Content.quickhacks.forEachIndexed { i, q ->
            val top = r.top + i * rowH
            rowRect.set(r.left, top + Cy.dp(2f), r.right, top + rowH - Cy.dp(3f))
            val active = q.id == "optics" && game.actions.torchOn
            Cy.panel(c, rowRect, Cy.dp(8f), Cy.panelLight, if (active) Cy.hi else Cy.withAlpha(Cy.primary, 0x38), Cy.TR, ticks = false)
            if (flash == q.id && now - flashStart < FLASH_MS) {
                val f = ((now - flashStart) / 380f).coerceIn(0f, 1f)
                val a = if (now - flashStart < 380) 0x50 else (0x50 * (1f - (now - flashStart - 380) / (FLASH_MS - 380f))).toInt()
                Cy.rect(c, rowRect.left, rowRect.top, rowRect.left + rowRect.width() * f, rowRect.bottom, Cy.withAlpha(Cy.accent, max(0, a)))
            }
            val gx = rowRect.left + Cy.dp(22f)
            Glyphs.draw(c, glyphFor(q), gx, rowRect.centerY(), Cy.dp(22f), if (active) Cy.hi else Cy.accent, 1.7f)
            Cy.line(c, rowRect.left + Cy.dp(42f), rowRect.top + Cy.dp(8f), rowRect.left + Cy.dp(42f), rowRect.bottom - Cy.dp(8f), Cy.faint)
            val tx = rowRect.left + Cy.dp(52f)
            Cy.text(c, q.name.uppercase(Locale.US), tx, rowRect.top + Cy.dp(20f), label, Cy.WHITE)
            Cy.text(c, q.does, tx, rowRect.top + Cy.dp(36f), sub, Cy.MUTED)
            val rx = rowRect.right - Cy.dp(12f)
            if (active) {
                Cy.text(c, "ACTIVE", rx, rowRect.top + Cy.dp(20f), cost, Cy.hi)
            } else if (q.ram == 0) {
                Cy.text(c, "FREE", rx, rowRect.top + Cy.dp(20f), cost, Cy.accent)
            } else {
                Cy.text(c, "RAM ${q.ram}", rx, rowRect.top + Cy.dp(20f), cost, Cy.accent)
                val cw = Cy.dp(5f)
                for (k in 0 until q.ram) {
                    val x = rx - (k + 1) * (cw + Cy.dp(2f)) + Cy.dp(2f)
                    Cy.rect(c, x, rowRect.top + Cy.dp(28f), x + cw, rowRect.top + Cy.dp(33f), if (k < game.ramFree()) Cy.accent else Cy.withAlpha(Cy.accent, 0x40))
                }
            }
        }
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val i = ((y - r.top) / rowH).toInt()
        val q = Content.quickhacks.getOrNull(i) ?: return
        flash = q.id
        flashStart = SystemClock.uptimeMillis()
        game.quickhack(q)
        host.invalidate()
    }

    companion object {
        const val FLASH_MS = 900L
    }
}

/** Main jobs (the launcher's tutorial) and the user's own gigs. */
class JournalPanel(game: Game, host: PanelView) : Panel(game, host) {
    override val title = "JOURNAL"
    override val glyph = Glyph.JOURNAL
    override val scrollable get() = true

    private sealed class Row(val h: Float) {
        class New(h: Float) : Row(h)
        class Head(h: Float, val text: String) : Row(h)
        class Item(h: Float, val job: Job) : Row(h)
    }

    private fun rows(): List<Row> {
        val jobs = game.save.jobs
        val active = jobs.filter { !it.done }.sortedByDescending { it.main }
        val done = jobs.filter { it.done }
        val out = ArrayList<Row>()
        out += Row.New(Cy.dp(46f))
        out += Row.Head(Cy.dp(26f), "ACTIVE · ${active.size}")
        active.forEach { out += Row.Item(Cy.dp(52f), it) }
        if (done.isNotEmpty()) {
            out += Row.Head(Cy.dp(26f), "COMPLETED · ${done.size}")
            done.forEach { out += Row.Item(Cy.dp(44f), it) }
        }
        return out
    }

    override fun contentHeight(r: RectF) = rows().sumOf { it.h.toDouble() }.toFloat()
    override fun info() = "${game.save.jobs.count { !it.done }} ACTIVE"

    override fun draw(c: Canvas, r: RectF) {
        var y = r.top
        val tracked = game.trackedJob()
        for (row in rows()) {
            when (row) {
                is Row.New -> {
                    rowRect.set(r.left, y + Cy.dp(4f), r.right, y + row.h - Cy.dp(4f))
                    Cy.panel(c, rowRect, Cy.dp(8f), Cy.withAlpha(Cy.hi, 0x14), Cy.withAlpha(Cy.hi, 0x90), Cy.TR, ticks = false)
                    Glyphs.draw(c, Glyph.PLUS, rowRect.left + Cy.dp(20f), rowRect.centerY(), Cy.dp(16f), Cy.hi, 2f)
                    Cy.text(c, "NEW GIG", rowRect.left + Cy.dp(38f), Cy.baseline(label, rowRect.top, rowRect.bottom), label, Cy.hi)
                    Cy.text(c, "ANY TO-DO COUNTS", rowRect.right - Cy.dp(12f), Cy.baseline(monoRight, rowRect.top, rowRect.bottom), monoRight, Cy.MUTED)
                }
                is Row.Head -> heading(c, r, y + Cy.dp(4f), row.text)
                is Row.Item -> {
                    val j = row.job
                    rowRect.set(r.left, y + Cy.dp(2f), r.right, y + row.h - Cy.dp(2f))
                    val isTracked = tracked === j
                    if (isTracked) Cy.rect(c, rowRect, Cy.withAlpha(Cy.hi, 0x12))
                    val gx = rowRect.left + Cy.dp(12f)
                    val gy = rowRect.top + Cy.dp(17f)
                    when {
                        j.done -> Glyphs.draw(c, Glyph.CHECK, gx, gy, Cy.dp(14f), Cy.MUTED, 2f)
                        j.main -> Cy.diamond(c, gx, gy, Cy.dp(5f), Cy.hi)
                        else -> Cy.diamond(c, gx, gy, Cy.dp(5f), Cy.accent, filled = false)
                    }
                    val tx = rowRect.left + Cy.dp(28f)
                    val maxW = rowRect.width() - Cy.dp(28f) - if (isTracked) Cy.dp(70f) else Cy.dp(8f)
                    Cy.text(c, Cy.ellipsize(j.title.uppercase(Locale.US), label, maxW), tx, rowRect.top + Cy.dp(21f), label, if (j.done) Cy.MUTED else Cy.WHITE)
                    if (!j.done) {
                        val note = if (j.main) j.note else "GIG · TAP WHEN IT'S DONE · HOLD FOR OPTIONS"
                        Cy.text(c, Cy.ellipsize(note, sub, rowRect.width() - Cy.dp(36f)), tx, rowRect.top + Cy.dp(38f), sub, Cy.MUTED)
                    }
                    if (isTracked) Cy.text(c, "TRACKED", rowRect.right - Cy.dp(6f), rowRect.top + Cy.dp(20f), monoRight, Cy.hi)
                    Cy.line(c, tx, rowRect.bottom, rowRect.right, rowRect.bottom, Cy.withAlpha(Cy.primary, 0x1A))
                }
            }
            y += row.h
        }
    }

    private fun rowAt(y: Float, r: RectF): Row? {
        var top = r.top
        for (row in rows()) {
            if (y >= top && y < top + row.h) return row
            top += row.h
        }
        return null
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        when (val row = rowAt(y, r)) {
            is Row.New -> {
                game.haptic()
                game.newGig()
            }
            is Row.Item -> game.jobTap(row.job)
            else -> Unit
        }
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        val row = rowAt(y, r) as? Row.Item ?: return
        game.jobMenu(row.job, menuX(x), menuY(y))
    }
}

/** Phone: one-tap contacts for the basics, and recently used apps as the call log. */
class PhonePanel(game: Game, host: PanelView) : Panel(game, host) {
    override val title = "PHONE"
    override val glyph = Glyph.PHONE
    override val scrollable get() = true
    private val quickH get() = Cy.dp(74f)
    private val rowH get() = Cy.dp(50f)
    private val quickLabel = Cy.paint(9f, Cy.Face.MONO, Paint.Align.CENTER, 0.08f)

    private class Quick(val glyph: Glyph, val label: String, val run: (Game) -> Boolean)

    private val quick = listOf(
        Quick(Glyph.DIAL, "CALL") { it.actions.dial() },
        Quick(Glyph.TEXT, "TEXTS") { it.actions.messages() },
        Quick(Glyph.CAMERA, "PHOTO MODE") { it.actions.camera() },
        Quick(Glyph.NET, "THE NET") { it.actions.browser() },
        Quick(Glyph.MAP, "MAP") { it.actions.maps() },
    )

    private fun recent(): List<AppEntry> = game.recentApps(10)
    override fun contentHeight(r: RectF) = quickH + Cy.dp(30f) + max(1, recent().size) * rowH
    override fun info() = "${recent().count { ago(game.lastUsed(it)) == "ONLINE" }} ONLINE"

    override fun draw(c: Canvas, r: RectF) {
        val w = r.width() / quick.size
        quick.forEachIndexed { i, q ->
            rowRect.set(r.left + i * w + Cy.dp(3f), r.top + Cy.dp(4f), r.left + (i + 1) * w - Cy.dp(3f), r.top + quickH - Cy.dp(4f))
            Cy.panel(c, rowRect, Cy.dp(7f), Cy.panelLight, Cy.withAlpha(Cy.primary, 0x40), Cy.TR, ticks = false)
            Glyphs.draw(c, q.glyph, rowRect.centerX(), rowRect.top + rowRect.height() * 0.42f, Cy.dp(22f), Cy.accent, 1.7f)
            Cy.text(c, q.label, rowRect.centerX(), rowRect.bottom - Cy.dp(9f), quickLabel, Cy.TEXT)
        }
        var y = heading(c, r, r.top + quickH + Cy.dp(2f), "RECENT CONTACTS")
        val list = recent()
        if (list.isEmpty()) {
            Cy.text(c, "Nobody yet. Open some apps, make some friends.", r.left + Cy.dp(4f), y + Cy.dp(20f), sub, Cy.MUTED)
            return
        }
        for (app in list) {
            rowRect.set(r.left, y, r.right, y + rowH)
            ItemDraw.icon(c, game, app, rowRect.left + Cy.dp(20f), rowRect.centerY(), Cy.dp(32f))
            val tx = rowRect.left + Cy.dp(46f)
            Cy.text(c, Cy.ellipsize(app.label, label, rowRect.width() * 0.55f), tx, rowRect.top + Cy.dp(22f), label, Cy.WHITE)
            val seen = ago(game.lastUsed(app))
            val online = seen == "ONLINE"
            if (online) Cy.rect(c, tx, rowRect.top + Cy.dp(31f), tx + Cy.dp(5f), rowRect.top + Cy.dp(36f), Cy.accent)
            Cy.text(c, if (online) "ONLINE" else "LAST SEEN $seen", tx + if (online) Cy.dp(9f) else 0f, rowRect.top + Cy.dp(37f), mono, if (online) Cy.accent else Cy.MUTED)
            Cy.text(c, "${game.launches(app)} USES", rowRect.right - Cy.dp(6f), rowRect.top + Cy.dp(22f), monoRight, Cy.withAlpha(game.rarity(app).color, 0xD0))
            Cy.line(c, tx, rowRect.bottom - Cy.px, rowRect.right, rowRect.bottom - Cy.px, Cy.withAlpha(Cy.primary, 0x1A))
            y += rowH
        }
    }

    private fun recentAt(y: Float, r: RectF): AppEntry? {
        val start = r.top + quickH + Cy.dp(30f)
        if (y < start) return null
        return recent().getOrNull(((y - start) / rowH).toInt())
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        if (y < r.top + quickH) {
            val i = ((x - r.left) / (r.width() / quick.size)).toInt()
            val q = quick.getOrNull(i) ?: return
            game.haptic()
            if (!q.run(game)) game.feed(Tag.ERR, "No app answers to ${q.label}. Dead line.")
            return
        }
        val app = recentAt(y, r) ?: return
        game.launch(app)
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        val app = recentAt(y, r) ?: return
        game.stashMenu(app, menuX(x), menuY(y))
    }
}

/** Media controls dressed as a car radio. Stations unlock with your level. */
class RadioPanel(game: Game, host: PanelView) : Panel(game, host) {
    override val title = "RADIO"
    override val glyph = Glyph.RADIO
    override val scrollable get() = true
    override val animates get() = game.stats.musicActive && host.tab == Game.TAB_RADIO
    private val nowH get() = Cy.dp(84f)
    private val ctrlH get() = Cy.dp(56f)
    private val rowH get() = Cy.dp(40f)
    private val freq = Cy.paint(28f, Cy.Face.BOLD)
    private val fm = Cy.paint(11f, Cy.Face.MONO, spacing = 0.1f)
    private val stationName = Cy.paint(15f, Cy.Face.BOLD, spacing = 0.06f)
    private val rowFreq = Cy.paint(11.5f, Cy.Face.MONO)
    private val buttons = Array(4) { RectF() }

    override fun contentHeight(r: RectF) = nowH + ctrlH + Cy.dp(30f) + Content.stations.size * rowH
    override fun info() = "${game.unlockedStations()}/${Content.stations.size} STATIONS"

    override fun draw(c: Canvas, r: RectF) {
        val st = Content.stations[game.save.station.coerceIn(0, Content.stations.size - 1)]
        val playing = game.stats.musicActive
        rowRect.set(r.left, r.top + Cy.dp(2f), r.right, r.top + nowH - Cy.dp(4f))
        Cy.panel(c, rowRect, Cy.dp(10f), Cy.panelLight, Cy.withAlpha(Cy.primary, 0x50), Cy.TR or Cy.BL)
        Cy.scanlines(c, rowRect)
        val tx = rowRect.left + Cy.dp(14f)
        Cy.glow(c, st.freq, tx, rowRect.top + Cy.dp(34f), freq, Cy.hi, Cy.dp(6f))
        Cy.text(c, "FM", tx + freq.measureText(st.freq) + Cy.dp(5f), rowRect.top + Cy.dp(33f), fm, Cy.hi)
        Cy.text(c, st.name.uppercase(Locale.US), tx, rowRect.top + Cy.dp(54f), stationName, Cy.WHITE)
        Cy.text(c, Cy.ellipsize(st.genre, sub, rowRect.width() * 0.62f), tx, rowRect.top + Cy.dp(70f), sub, Cy.MUTED)
        // Equaliser: dances while something is actually playing.
        val t = SystemClock.uptimeMillis() / 1000f
        val bars = 12
        val bw = Cy.dp(4f)
        val ex = rowRect.right - Cy.dp(14f) - bars * (bw + Cy.dp(2.5f))
        val base = rowRect.top + Cy.dp(48f)
        for (k in 0 until bars) {
            val hgt = if (playing) Cy.dp(6f) + abs(sin(t * (3f + k * 0.7f) + k)) * Cy.dp(26f) else Cy.dp(3f) + (k % 3) * Cy.dp(1.5f)
            val x = ex + k * (bw + Cy.dp(2.5f))
            Cy.rect(c, x, base - hgt, x + bw, base, if (playing) Cy.accent else Cy.withAlpha(Cy.accent, 0x50))
        }
        Cy.text(c, if (playing) "ON AIR" else "STANDBY", rowRect.right - Cy.dp(14f), rowRect.top + Cy.dp(66f), monoRight, if (playing) Cy.accent else Cy.MUTED)

        val cy = r.top + nowH
        val bwid = min(Cy.dp(58f), r.width() * 0.17f)
        val glyphs = listOf(Glyph.PREV, if (playing) Glyph.PAUSE else Glyph.PLAY, Glyph.NEXT)
        for (i in 0 until 3) {
            buttons[i].set(r.left + i * (bwid + Cy.dp(6f)), cy + Cy.dp(4f), r.left + i * (bwid + Cy.dp(6f)) + bwid, cy + ctrlH - Cy.dp(6f))
            Cy.panel(c, buttons[i], Cy.dp(6f), Cy.panelLight, Cy.withAlpha(Cy.primary, if (i == 1) 0xA0 else 0x50), Cy.TR, ticks = false)
            Glyphs.draw(c, glyphs[i], buttons[i].centerX(), buttons[i].centerY(), Cy.dp(18f), if (i == 1) Cy.hi else Cy.accent, 1.8f)
        }
        val vb = buttons[3]
        vb.set(buttons[2].right + Cy.dp(12f), cy + Cy.dp(4f), r.right, cy + ctrlH - Cy.dp(6f))
        Cy.text(c, "VOLUME", vb.left, vb.top + Cy.dp(16f), section, Cy.dim)
        Cy.text(c, "${game.stats.volumePct}%", vb.right, vb.top + Cy.dp(16f), monoRight, Cy.accent)
        Cy.segments(c, RectF(vb.left, vb.top + Cy.dp(24f), vb.right, vb.top + Cy.dp(33f)), 14, game.stats.volumePct / 100f * 14, Cy.accent, Cy.withAlpha(Cy.accent, 0x26))

        var y = heading(c, r, cy + ctrlH, "STATIONS")
        val unlocked = game.unlockedStations()
        Content.stations.forEachIndexed { i, s ->
            rowRect.set(r.left, y, r.right, y + rowH)
            val sel = i == game.save.station
            val locked = i >= unlocked
            if (sel) {
                Cy.rect(c, rowRect, Cy.withAlpha(Cy.accent, 0x16))
                Cy.rect(c, rowRect.left, rowRect.top + Cy.dp(6f), rowRect.left + Cy.dp(2.5f), rowRect.bottom - Cy.dp(6f), Cy.accent)
            }
            val by = Cy.baseline(label, rowRect.top, rowRect.bottom)
            Cy.text(c, s.freq, rowRect.left + Cy.dp(10f), by, rowFreq, if (locked) Cy.withAlpha(Cy.MUTED, 0x80) else if (sel) Cy.accent else Cy.hi)
            Cy.text(c, s.name.uppercase(Locale.US), rowRect.left + Cy.dp(62f), by, label, if (locked) Cy.withAlpha(Cy.MUTED, 0x90) else Cy.WHITE)
            if (locked) {
                Glyphs.draw(c, Glyph.LOCK, rowRect.right - Cy.dp(56f), rowRect.centerY(), Cy.dp(12f), Cy.MUTED, 2f)
                Cy.text(c, "LVL ${game.stationLevel(i)}", rowRect.right - Cy.dp(6f), by, monoRight, Cy.MUTED)
            } else if (sel) {
                Cy.text(c, "TUNED", rowRect.right - Cy.dp(6f), by, monoRight, Cy.accent)
            }
            y += rowH
        }
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val cy = r.top + nowH
        if (y < cy) {
            game.haptic()
            if (!game.actions.music()) game.feed(Tag.ERR, "No music app. Night City is quiet tonight.")
            return
        }
        if (y < cy + ctrlH) {
            when {
                buttons[0].contains(x, y) -> game.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                buttons[1].contains(x, y) -> game.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                buttons[2].contains(x, y) -> game.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                x > buttons[2].right -> {
                    game.haptic()
                    game.actions.volume()
                }
            }
            return
        }
        val i = ((y - cy - ctrlH - Cy.dp(30f)) / rowH).toInt()
        if (i in Content.stations.indices) game.tune(i)
    }
}

/** Launcher options, the palette vendor, and the exits. */
class SettingsPanel(game: Game, host: PanelView) : Panel(game, host) {
    override val title = "SETTINGS"
    override val glyph = Glyph.SETTINGS
    override val scrollable get() = true
    private val value = Cy.paint(11f, Cy.Face.MONO, Paint.Align.RIGHT, 0.08f)
    private val about = Cy.paint(9f, Cy.Face.MONO, Paint.Align.CENTER, 0.06f)

    private sealed class Row(val h: Float) {
        class Head(val text: String) : Row(Cy.dp(28f))
        class Opt(val name: String, val value: () -> String, val danger: Boolean = false, val run: () -> Unit) : Row(Cy.dp(42f))
        class Vend(val p: Palette) : Row(Cy.dp(46f))
        class About : Row(Cy.dp(54f))
    }

    private fun on(b: Boolean) = if (b) "ON" else "OFF"

    private val rows: List<Row> by lazy {
        val s = game.save
        listOf(
            Row.Head("DISPLAY"),
            Row.Opt("Lifepath", { game.lifepath()?.title?.uppercase(Locale.US) ?: "NONE" }) { pickLifepath() },
            Row.Opt("Icon style", { arrayOf("STOCK", "CHROME", "HOLO")[s.iconStyle.coerceIn(0, 2)] }) { game.setIconStyle((s.iconStyle + 1) % 3) },
            Row.Opt("Item labels", { on(s.labels) }) { s.labels = !s.labels; done() },
            Row.Opt("Clock", { if (s.clock24) "24H" else "12H" }) { s.clock24 = !s.clock24; done() },
            Row.Opt("Calendar", { if (s.realYear) "REAL YEAR" else "NIGHT CITY (+51)" }) { s.realYear = !s.realYear; done() },
            Row.Opt("Background", { if (s.scene == 0) "CITY" else "WALLPAPER" }) { s.scene = 1 - s.scene; done() },
            Row.Opt("Rain", { on(s.rain) }) { s.rain = !s.rain; done() },
            Row.Opt("Glitch effects", { on(s.glitch) }) { s.glitch = !s.glitch; done() },
            Row.Opt("Boot sequence", { on(s.boot) }) { s.boot = !s.boot; done() },
            Row.Opt("Haptics", { on(s.haptics) }) { s.haptics = !s.haptics; done() },
            Row.Opt("Handle", { s.name.uppercase(Locale.US) }) { game.askName() },
            Row.Head("VENDOR · €$ ${Game.number(s.eddies)}"),
        ) + Content.palettes.map { Row.Vend(it) } + listOf(
            Row.Head("SYSTEM"),
            Row.Opt("Default home app", { "" }) { game.actions.homeSettings() },
            Row.Opt("Change wallpaper", { "" }) { game.actions.wallpaper() },
            Row.Opt("Android settings", { "" }) { game.actions.settings(android.provider.Settings.ACTION_SETTINGS) },
            Row.Opt("Reset progress", { "" }, danger = true) { game.confirmReset() },
            Row.Opt("Quit game", { "" }, danger = true) { game.quit() },
            Row.About(),
        )
    }

    private fun done() {
        game.haptic()
        game.save.save()
        game.ui.invalidateAll()
    }

    private fun pickLifepath() {
        game.ui.showChoice(Choice(
            "SYSTEM", "Pick a lifepath. It changes your colours, not your past.",
            Lifepath.values().map { lp -> ChoiceOption("${lp.title}. ${lp.tagline}", primary = lp == game.lifepath()) { game.chooseLifepath(lp) } },
        ))
    }

    override fun contentHeight(r: RectF) = rows.sumOf { it.h.toDouble() }.toFloat()
    override fun info() = "v${version()}"

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

    override fun draw(c: Canvas, r: RectF) {
        var y = r.top
        for (row in rows) {
            rowRect.set(r.left, y, r.right, y + row.h)
            when (row) {
                is Row.Head -> heading(c, r, y + Cy.dp(4f), if (row.text.startsWith("VENDOR")) "VENDOR · €$ ${Game.number(game.save.eddies)}" else row.text)
                is Row.Opt -> {
                    val by = Cy.baseline(label, rowRect.top, rowRect.bottom)
                    Cy.text(c, row.name.uppercase(Locale.US), rowRect.left + Cy.dp(4f), by, label, if (row.danger) Cy.DANGER else Cy.WHITE)
                    val v = row.value()
                    if (v.isNotEmpty()) Cy.text(c, v, rowRect.right - Cy.dp(6f), by, value, Cy.accent)
                    else Glyphs.draw(c, Glyph.NEXT, rowRect.right - Cy.dp(12f), rowRect.centerY(), Cy.dp(11f), if (row.danger) Cy.DANGER else Cy.dim, 2f)
                    Cy.line(c, rowRect.left, rowRect.bottom - Cy.px, rowRect.right, rowRect.bottom - Cy.px, Cy.withAlpha(Cy.primary, 0x18))
                }
                is Row.Vend -> drawVendor(c, row.p)
                is Row.About -> {
                    Cy.text(c, "CYBERLAUNCHER v${version()} · A FAN PROJECT", rowRect.centerX(), rowRect.top + Cy.dp(24f), about, Cy.MUTED)
                    Cy.text(c, "NOT AFFILIATED WITH CD PROJEKT RED", rowRect.centerX(), rowRect.top + Cy.dp(38f), about, Cy.withAlpha(Cy.MUTED, 0xA0))
                }
            }
            y += row.h
        }
    }

    private fun drawVendor(c: Canvas, p: Palette) {
        val r = rowRect
        val equipped = game.save.theme == p.id
        val owned = p.price == 0 || p.id in game.save.owned
        val box = RectF(r.left, r.top + Cy.dp(3f), r.right, r.bottom - Cy.dp(3f))
        Cy.panel(c, box, Cy.dp(7f), Cy.mix(p.bg, 0xFF000000.toInt(), 0.2f), if (equipped) p.primary else Cy.withAlpha(p.primary, 0x50), Cy.TR, ticks = false)
        var x = box.left + Cy.dp(10f)
        for (col in listOf(p.primary, p.accent, p.hi)) {
            Cy.rect(c, x, box.centerY() - Cy.dp(6f), x + Cy.dp(8f), box.centerY() + Cy.dp(6f), col)
            x += Cy.dp(11f)
        }
        val by = Cy.baseline(label, box.top, box.bottom) - Cy.dp(5f)
        label.color = p.primary
        c.drawText(p.name.uppercase(Locale.US), x + Cy.dp(6f), by, label)
        Cy.text(c, Cy.ellipsize(p.tagline, sub, box.width() * 0.5f), x + Cy.dp(6f), by + Cy.dp(14f), sub, Cy.MUTED)
        val status = when {
            equipped -> "EQUIPPED"
            owned -> "OWNED"
            else -> "€$ ${Game.number(p.price.toLong())}"
        }
        val col = when {
            equipped -> p.accent
            owned -> Cy.TEXT
            game.save.eddies >= p.price -> Cy.hi
            else -> Cy.MUTED
        }
        Cy.text(c, status, box.right - Cy.dp(10f), Cy.baseline(value, box.top, box.bottom), value, col)
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        var top = r.top
        for (row in rows) {
            if (y >= top && y < top + row.h) {
                when (row) {
                    is Row.Opt -> row.run()
                    is Row.Vend -> game.vendor(row.p)
                    else -> Unit
                }
                host.invalidate()
                return
            }
            top += row.h
        }
    }
}
