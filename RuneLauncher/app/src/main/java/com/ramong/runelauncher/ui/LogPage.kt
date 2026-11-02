package com.ramong.runelauncher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import com.ramong.runelauncher.core.Content
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.Rs3Format
import com.ramong.runelauncher.core.Skill
import com.ramong.runelauncher.core.Tag
import com.ramong.runelauncher.core.Xp
import kotlin.math.ceil
import kotlin.math.min

/**
 * The page left of home: your Adventurer's Log. Profile, all 29 skills, quick settings as the
 * prayer book, music, the quest journal (a to-do list) and recent apps. Draws in its own content
 * coordinates and records tap targets as it goes.
 */
class LogPage(private val game: Game) {
    class Hit(val r: RectF, val tap: () -> Unit, val long: ((Float, Float) -> Unit)? = null)

    val hits = ArrayList<Hit>()
    var height = 0f
        private set
    private var flashId: String? = null
    private var flashStart = 0L

    private val title = R3.paint(24f, R3.Face.TITLE_BOLD)
    private val hint = R3.paint(12f, R3.Face.BODY)
    private val cardTitle = R3.paint(14.5f, R3.Face.TITLE_BOLD, Paint.Align.CENTER, 0.03f)
    private val name = R3.paint(22f, R3.Face.TITLE_BOLD)
    private val bodyBold = R3.paint(14f, R3.Face.BOLD)
    private val body = R3.paint(13f, R3.Face.BODY)
    private val bodyRight = R3.paint(13f, R3.Face.BODY, Paint.Align.RIGHT)
    private val bigNum = R3.paint(22f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val small = R3.paint(10.5f, R3.Face.BOLD, Paint.Align.CENTER)
    private val level = R3.paint(14f, R3.Face.BOLD, Paint.Align.CENTER)
    private val tileLabel = R3.paint(12f, R3.Face.BOLD, Paint.Align.CENTER)
    private val tilePrayer = R3.paint(9.5f, R3.Face.BODY, Paint.Align.CENTER)
    private val track = R3.paint(17f, R3.Face.TITLE_BOLD)
    private val questPaint = R3.paint(15f, R3.Face.BOLD)
    private val button = R3.paint(12f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val footer = R3.paint(11.5f, R3.Face.BODY, Paint.Align.CENTER)
    private val card = RectF()
    private val tmp = RectF()

    fun hitAt(x: Float, y: Float): Hit? = hits.lastOrNull { it.r.contains(x, y) }

    private fun hit(l: Float, t: Float, r: Float, b: Float, tap: () -> Unit, long: ((Float, Float) -> Unit)? = null) {
        hits += Hit(RectF(l, t, r, b), tap, long)
    }

    /** Draws everything at (0, 0) with the given [w]idth. [height] holds the total afterwards. */
    fun draw(c: Canvas, w: Float) {
        hits.clear()
        var y = R3.dp(4f)
        R3.text(c, "Adventurer's Log", R3.dp(4f), y + R3.dp(26f), title, R3.hi, shadow = true)
        R3.text(c, "Swipe left to go home", R3.dp(5f), y + R3.dp(44f), hint, R3.TEXT, shadow = true)
        y += R3.dp(58f)
        y = profile(c, w, y) + GAP
        y = skills(c, w, y) + GAP
        y = prayers(c, w, y) + GAP
        y = music(c, w, y) + GAP
        y = quests(c, w, y) + GAP
        y = recent(c, w, y) + GAP
        val f = "Achievements ${game.achievementsDone()} of ${Content.achievements.size}  ·  ${Rs3Format.number(game.save.launches.values.sum().toLong())} apps opened"
        R3.text(c, f, w / 2f, y + R3.dp(14f), footer, R3.TEXT, shadow = true)
        hit(0f, y, w, y + R3.dp(28f), {
            val next = Content.achievements.firstOrNull { it.id !in game.save.achievements }
            game.feed(Tag.NEWS, if (next == null) "Every achievement done. The Wise Old Man is impressed." else "Next achievement: <col=hi>${next.title}</col>. ${next.hint}")
        })
        height = y + R3.dp(34f)
    }

    private fun cardFrame(c: Canvas, w: Float, top: Float, h: Float, label: String): RectF {
        card.set(0f, top, w, top + h)
        R3.panel(c, card, titleText = label, titlePaint = cardTitle, titleH = R3.dp(36f))
        return card
    }

    private fun flash(id: String) {
        flashId = id
        flashStart = SystemClock.uptimeMillis()
    }

    private fun flashing(id: String) = flashId == id && SystemClock.uptimeMillis() - flashStart < FLASH_MS

    private fun profile(c: Canvas, w: Float, top: Float): Float {
        val h = R3.dp(146f)
        cardFrame(c, w, top, h, "Profile")
        val x = R3.dp(16f)
        val r = w - R3.dp(16f)
        var y = top + R3.dp(70f)
        R3.text(c, game.save.name, x, y, name, R3.WHITE)
        hit(0f, top + R3.dp(40f), x + name.measureText(game.save.name) + R3.dp(20f), y + R3.dp(8f), { game.askName() })
        R3.text(c, "World ${game.save.world} · ${game.place.name}", x, y + R3.dp(19f), body, R3.MUTED)
        val stats = listOf("${game.combatLevel()}" to "Combat", "${game.totalLevel()}" to "Total", "${game.save.questPoints}" to "Quest pts")
        val cw = R3.dp(66f)
        stats.forEachIndexed { i, (v, l) ->
            val cx = r - cw * (stats.size - i) + cw / 2f
            R3.text(c, v, cx, top + R3.dp(68f), bigNum, R3.hi)
            R3.text(c, l, cx, top + R3.dp(84f), small, R3.MUTED)
        }
        y = top + R3.dp(114f)
        R3.text(c, "${Rs3Format.number(game.totalXp())} total XP", x, y, bodyBold, R3.TEXT)
        val days = ((System.currentTimeMillis() - game.save.installTime) / 86_400_000L).coerceAtLeast(0) + 1
        R3.text(c, "Day $days in Gielinor", r, y, bodyRight, R3.MUTED)
        tmp.set(x, y + R3.dp(9f), r, y + R3.dp(15f))
        val maxTotal = Skill.values().sumOf { it.maxLevel }
        R3.bar(c, tmp, game.totalLevel() / maxTotal.toFloat(), R3.accent)
        return top + h
    }

    private fun skills(c: Canvas, w: Float, top: Float): Float {
        val cols = 5
        val all = Skill.values()
        val rows = ceil((all.size + 1) / cols.toFloat()).toInt()
        val cellH = R3.dp(66f)
        val h = R3.dp(44f) + rows * cellH + R3.dp(6f)
        cardFrame(c, w, top, h, "Skills")
        val x0 = R3.dp(8f)
        val cw = (w - R3.dp(16f)) / cols
        for (i in 0..all.size) {
            val col = i % cols
            val row = i / cols
            tmp.set(x0 + col * cw, top + R3.dp(42f) + row * cellH, x0 + (col + 1) * cw, top + R3.dp(42f) + (row + 1) * cellH)
            val cx = tmp.centerX()
            if (i == all.size) {
                R3.text(c, "${game.totalLevel()}", cx, tmp.top + R3.dp(30f), bigNum, R3.hi)
                R3.text(c, "Total level", cx, tmp.top + R3.dp(47f), small, R3.MUTED)
                val rr = RectF(tmp)
                hit(rr.left, rr.top, rr.right, rr.bottom, { game.command("::stats") })
                continue
            }
            val s = all[i]
            val lv = game.level(s)
            if (flashing("skill${s.ordinal}")) R3.round(c, RectF(tmp).apply { inset(R3.dp(2f), R3.dp(2f)) }, R3.dp(8f), R3.withAlpha(R3.hi, 0x20))
            R3.skillBadge(c, s, cx, tmp.top + R3.dp(22f), R3.dp(38f))
            R3.text(c, if (lv >= s.maxLevel) "$lv ★" else "$lv", cx, tmp.top + R3.dp(56f), level, if (lv >= s.maxLevel) R3.hi else R3.WHITE)
            tmp.set(cx - cw * 0.3f, tmp.top + R3.dp(60f), cx + cw * 0.3f, tmp.top + R3.dp(62.5f))
            R3.bar(c, tmp, Xp.progress(game.xp(s), lv, s.maxLevel), R3.accent)
            val rr = RectF(x0 + col * cw, top + R3.dp(42f) + row * cellH, x0 + (col + 1) * cw, top + R3.dp(42f) + (row + 1) * cellH)
            hit(rr.left, rr.top, rr.right, rr.bottom, {
                flash("skill${s.ordinal}")
                skillInfo(s)
            })
        }
        return top + h
    }

    private fun skillInfo(s: Skill) {
        game.haptic()
        val lv = game.level(s)
        val xp = game.xp(s)
        val next = if (lv >= s.maxLevel) "Maximum level." else "${Rs3Format.number(Xp.forLevel(lv + 1) - xp)} XP to level ${lv + 1}."
        val apps = game.appsTraining(s)
        val who = when {
            apps.isEmpty() -> "Trained by ${s.trains}."
            apps.size <= 3 -> "Trained by ${apps.joinToString(", ") { it.label }}."
            else -> "Trained by ${apps.take(3).joinToString(", ") { it.label }} and ${apps.size - 3} more."
        }
        game.feed(Tag.XP, "<col=hi>${s.title} $lv</col> · ${Rs3Format.number(xp)} XP. $next $who")
    }

    private fun prayers(c: Canvas, w: Float, top: Float): Float {
        val tileH = R3.dp(72f)
        val h = R3.dp(44f) + tileH * 2 + R3.dp(8f)
        cardFrame(c, w, top, h, "Prayers · Quick settings")
        val x0 = R3.dp(8f)
        val tw = (w - R3.dp(16f)) / 5f
        Content.prayers.forEachIndexed { i, p ->
            val col = i % 5
            val row = i / 5
            tmp.set(x0 + col * tw + R3.dp(3f), top + R3.dp(42f) + row * tileH + R3.dp(3f), x0 + (col + 1) * tw - R3.dp(3f), top + R3.dp(42f) + (row + 1) * tileH - R3.dp(3f))
            val on = p.id == "torch" && game.actions.torchOn
            R3.button(c, tmp, pressed = flashing(p.id), lit = on)
            val glyph = when (p.id) {
                "internet" -> Glyph.WIFI
                "torch" -> Glyph.TORCH
                "bluetooth" -> Glyph.BLUETOOTH
                "dnd" -> Glyph.MOON
                "volume" -> Glyph.VOLUME
                "location" -> Glyph.PIN
                "saver" -> Glyph.BATTERY
                "hotspot" -> Glyph.HOTSPOT
                "airplane" -> Glyph.PLANE
                else -> Glyph.GEAR
            }
            Glyphs.draw(c, glyph, tmp.centerX(), tmp.top + R3.dp(20f), R3.dp(21f), if (on) R3.hi else R3.accent, 1.8f)
            R3.text(c, R3.ellipsize(p.label, tileLabel, tmp.width() - R3.dp(4f)), tmp.centerX(), tmp.top + R3.dp(45f), tileLabel, R3.WHITE)
            R3.text(c, R3.ellipsize(p.prayer, tilePrayer, tmp.width() - R3.dp(4f)), tmp.centerX(), tmp.top + R3.dp(58f), tilePrayer, R3.withAlpha(R3.hi, 0xB0))
            val rr = RectF(tmp)
            hit(rr.left, rr.top, rr.right, rr.bottom, {
                flash(p.id)
                game.prayer(p)
            })
        }
        return top + h
    }

    private fun music(c: Canvas, w: Float, top: Float): Float {
        val h = R3.dp(150f)
        cardFrame(c, w, top, h, "Music player")
        val x = R3.dp(16f)
        val playing = game.stats.musicActive
        val name = Content.tracks[game.save.track.coerceIn(0, Content.tracks.size - 1)]
        Glyphs.draw(c, Glyph.NOTE, x + R3.dp(10f), top + R3.dp(66f), R3.dp(22f), R3.hi, 1.8f)
        R3.text(c, name, x + R3.dp(30f), top + R3.dp(70f), track, R3.WHITE)
        R3.text(c, if (playing) "Now playing" else "Paused · ${game.unlockedTracks()} of ${Content.tracks.size} tracks unlocked",
            x + R3.dp(30f), top + R3.dp(88f), body, R3.MUTED)
        hit(0f, top + R3.dp(40f), w, top + R3.dp(96f), {
            game.haptic()
            if (!game.actions.music()) game.feed(Tag.ERR, "You don't have a music app. Gielinor is silent.")
        })
        val by = top + R3.dp(104f)
        val bh = R3.dp(34f)
        val bw = R3.dp(54f)
        val glyphs = listOf(Glyph.PREV, if (playing) Glyph.PAUSE else Glyph.PLAY, Glyph.NEXT)
        val codes = listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT)
        for (i in 0 until 3) {
            tmp.set(x + i * (bw + R3.dp(8f)), by, x + i * (bw + R3.dp(8f)) + bw, by + bh)
            R3.button(c, tmp, lit = i == 1)
            Glyphs.draw(c, glyphs[i], tmp.centerX(), tmp.centerY(), R3.dp(17f), R3.hi, 2f)
            val code = codes[i]
            hit(tmp.left, tmp.top - R3.dp(4f), tmp.right, tmp.bottom + R3.dp(4f), { game.mediaKey(code) })
        }
        return top + h
    }

    private fun quests(c: Canvas, w: Float, top: Float): Float {
        val list = game.save.quests.sortedBy { it.done }
        val rowH = R3.dp(42f)
        val anyDone = list.any { it.done }
        val h = R3.dp(44f) + (if (list.isEmpty()) R3.dp(40f) else list.size * rowH) + (if (anyDone) R3.dp(30f) else 0f) + R3.dp(50f)
        cardFrame(c, w, top, h, "Quest journal")
        var y = top + R3.dp(42f)
        val x = R3.dp(16f)
        if (list.isEmpty()) {
            R3.text(c, "No quests yet. Your to-do list lives here.", x, y + R3.dp(24f), body, R3.MUTED)
            y += R3.dp(40f)
        }
        for (q in list) {
            val cy = y + rowH / 2f
            if (q.done) Glyphs.draw(c, Glyph.CHECK, x + R3.dp(6f), cy, R3.dp(16f), R3.LIFE_OK, 2.2f)
            else R3.diamond(c, x + R3.dp(6f), cy, R3.dp(5f), R3.hi, filled = false)
            val tx = x + R3.dp(22f)
            val label = R3.ellipsize(q.title, questPaint, w - tx - R3.dp(16f))
            // The game's colours: yellow in progress, green complete.
            R3.text(c, label, tx, R3.baseline(questPaint, y, y + rowH), questPaint, if (q.done) R3.LIFE_OK else 0xFFF0D44A.toInt())
            R3.line(c, tx, y + rowH, w - R3.dp(16f), y + rowH, R3.withAlpha(R3.frame, 0x30))
            hit(0f, y, w, y + rowH, { game.questTap(q) }, { rx, ry -> game.questMenu(q, rx, ry) })
            y += rowH
        }
        if (anyDone) {
            R3.text(c, "Clear completed", w - R3.dp(16f), y + R3.dp(20f), bodyRight, R3.MUTED)
            hit(w - R3.dp(140f), y, w, y + R3.dp(30f), {
                game.haptic()
                game.clearDoneQuests()
            })
            y += R3.dp(30f)
        }
        tmp.set(x, y + R3.dp(8f), w - R3.dp(16f), y + R3.dp(42f))
        R3.button(c, tmp)
        R3.text(c, "+ Start a new quest", tmp.centerX(), R3.baseline(button, tmp.top, tmp.bottom), button, R3.hi)
        hit(tmp.left, tmp.top, tmp.right, tmp.bottom, {
            game.haptic()
            game.newQuest()
        })
        return top + h
    }

    private fun recent(c: Canvas, w: Float, top: Float): Float {
        val list = game.recentApps(5)
        val h = R3.dp(40f) + R3.dp(90f)
        cardFrame(c, w, top, h, "Recently used")
        if (list.isEmpty()) {
            R3.text(c, "Nothing yet. Go and open something.", R3.dp(16f), top + R3.dp(80f), body, R3.MUTED)
            return top + h
        }
        val cw = (w - R3.dp(16f)) / 5f
        list.forEachIndexed { i, app ->
            tmp.set(R3.dp(8f) + i * cw, top + R3.dp(40f), R3.dp(8f) + (i + 1) * cw, top + h - R3.dp(6f))
            AppIcon.cell(c, game, app, tmp, min(R3.dp(44f), cw * 0.62f), labels = true, pressed = false)
            hit(tmp.left, tmp.top, tmp.right, tmp.bottom, { game.launch(app) }, { rx, ry -> game.appMenu(app, null, null, rx, ry) })
        }
        return top + h
    }

    companion object {
        const val FLASH_MS = 700L
        private val GAP get() = R3.dp(14f)
    }
}
