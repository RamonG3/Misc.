package com.ramong.cyberlauncher.ui.panels

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.ramong.cyberlauncher.core.Attr
import com.ramong.cyberlauncher.core.Curves
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Tag
import com.ramong.cyberlauncher.ui.Cy
import com.ramong.cyberlauncher.ui.Glyph
import com.ramong.cyberlauncher.ui.Glyphs
import com.ramong.cyberlauncher.ui.Panel
import com.ramong.cyberlauncher.ui.PanelView
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Handle, level, Street Cred and the five attributes on a pentagon. */
class CharacterPanel(game: Game, host: PanelView) : Panel(game, host) {
    override val title = "CHARACTER"
    override val glyph = Glyph.CHARACTER
    override val scrollable get() = true

    private val name = Cy.paint(22f, Cy.Face.BOLD, spacing = 0.04f)
    private val big = Cy.paint(22f, Cy.Face.BOLD, Paint.Align.RIGHT)
    private val nodeNum = Cy.paint(15f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val nodeLabel = Cy.paint(11f, Cy.Face.BOLD, Paint.Align.CENTER, 0.08f)
    private val nodeSkill = Cy.paint(8.5f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val stat = Cy.paint(9.5f, Cy.Face.MONO, Paint.Align.CENTER, 0.06f)
    private val statNum = Cy.paint(15f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val bar = RectF()
    private val nameBox = RectF()
    private val nodes = Array(5) { PointF() }
    private var nodeR = 0f

    private val headH get() = Cy.dp(80f)
    private val statsH get() = Cy.dp(46f)

    private fun pentaH(r: RectF) = max(Cy.dp(176f), r.height() - headH - statsH)
    override fun contentHeight(r: RectF) = headH + pentaH(r) + statsH

    override fun info() = "LVL ${game.level()} · CRED ${game.credLevel()}"

    override fun draw(c: Canvas, r: RectF) {
        val save = game.save
        val lv = game.level()
        var y = r.top + Cy.dp(22f)
        Cy.glow(c, save.name.uppercase(Locale.US), r.left + Cy.dp(2f), y, name, Cy.WHITE, Cy.dp(5f))
        nameBox.set(r.left, r.top, r.left + Cy.dp(16f) + name.measureText(save.name.uppercase(Locale.US)), y + Cy.dp(8f))
        Cy.text(c, "LEVEL", r.right - big.measureText("$lv") - Cy.dp(6f) - mono.measureText("LEVEL"), y - Cy.dp(1f), mono, Cy.dim)
        Cy.glow(c, "$lv", r.right, y, big, Cy.primary, Cy.dp(5f))
        y += Cy.dp(15f)
        val life = game.lifepath()?.title?.uppercase(Locale.US) ?: "NO LIFEPATH"
        Cy.text(c, "$life · ${game.district.first}", r.left + Cy.dp(2f), y, mono, Cy.MUTED)
        val next = if (lv >= Curves.MAX_LEVEL) "MAX" else "${Game.number(save.xp - Curves.xpForLevel(lv))} / ${Game.number(Curves.xpForLevel(lv + 1) - Curves.xpForLevel(lv))} XP"
        Cy.text(c, next, r.right, y, monoRight, Cy.MUTED)
        y += Cy.dp(8f)
        bar.set(r.left + Cy.dp(2f), y, r.right, y + Cy.dp(3f))
        Cy.bar(c, bar, game.levelProgress(), Cy.primary)
        y += Cy.dp(12f)
        bar.set(r.left + Cy.dp(2f), y, r.left + r.width() * 0.48f, y + Cy.dp(2f))
        Cy.bar(c, bar, game.credProgress(), Cy.hi)
        Cy.text(c, "STREET CRED ${game.credLevel()}", r.left + Cy.dp(2f), y + Cy.dp(12f), section, Cy.hi)
        Cy.text(c, "€$ ${Game.number(save.eddies)}", r.right, y + Cy.dp(12f), monoRight, Cy.hi)

        // The pentagon.
        val top = r.top + headH
        val ph = pentaH(r)
        val cx = r.centerX()
        val cy = top + ph * 0.54f
        val rad = min(r.width() * 0.3f, ph * 0.32f)
        nodeR = min(Cy.dp(17f), rad * 0.3f)
        for (k in 0 until 5) {
            val a = Math.toRadians(-90.0 + k * 72.0)
            nodes[k].set(cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat())
        }
        stroke.strokeWidth = Cy.px
        for (ring in 1..3) {
            val f = ring / 3f
            path.reset()
            for (k in 0 until 5) {
                val x = cx + (nodes[k].x - cx) * f
                val yy = cy + (nodes[k].y - cy) * f
                if (k == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            }
            path.close()
            stroke.color = if (ring == 3) Cy.dim else Cy.faint
            c.drawPath(path, stroke)
        }
        for (k in 0 until 5) Cy.line(c, cx, cy, nodes[k].x, nodes[k].y, Cy.faint)
        path.reset()
        Attr.values().forEachIndexed { k, a ->
            val f = 0.12f + 0.88f * (game.attrLevel(a) - Curves.ATTR_MIN) / (Curves.ATTR_MAX - Curves.ATTR_MIN).toFloat()
            val x = cx + (nodes[k].x - cx) * f
            val yy = cy + (nodes[k].y - cy) * f
            if (k == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        path.close()
        fill.color = Cy.withAlpha(Cy.primary, 0x36)
        c.drawPath(path, fill)
        stroke.color = Cy.primary
        stroke.strokeWidth = Cy.px * 1.5f
        stroke.setShadowLayer(Cy.dp(5f), 0f, 0f, Cy.withAlpha(Cy.primary, 0x90))
        c.drawPath(path, stroke)
        stroke.clearShadowLayer()
        Attr.values().forEachIndexed { k, a -> drawNode(c, a, nodes[k], k) }

        // Stats row.
        val sy = top + ph + Cy.dp(6f)
        val stats = listOf(
            "LAUNCHES" to Game.number(save.totalLaunches),
            "ITEMS" to "${game.apps.size}",
            "LEGENDARY" to "${game.legendaries()}",
            "HACKS" to "${save.hacks}",
        )
        val cw = r.width() / stats.size
        stats.forEachIndexed { i, (k, v) ->
            val x = r.left + cw * (i + 0.5f)
            Cy.text(c, v, x, sy + Cy.dp(18f), statNum, Cy.WHITE)
            Cy.text(c, k, x, sy + Cy.dp(32f), stat, Cy.MUTED)
            if (i > 0) Cy.line(c, r.left + cw * i, sy + Cy.dp(6f), r.left + cw * i, sy + Cy.dp(34f), Cy.faint)
        }
    }

    private fun drawNode(c: Canvas, a: Attr, p: PointF, k: Int) {
        val rr = nodeR
        path.reset()
        for (i in 0 until 6) {
            val ang = Math.toRadians(i * 60.0)
            val x = p.x + rr * cos(ang).toFloat()
            val y = p.y + rr * sin(ang).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        fill.color = Cy.bg
        c.drawPath(path, fill)
        stroke.color = Cy.primary
        stroke.strokeWidth = Cy.px * 1.4f
        c.drawPath(path, stroke)
        Cy.text(c, "${game.attrLevel(a)}", p.x, p.y + nodeNum.textSize * 0.36f, nodeNum, Cy.WHITE)
        val below = k == 0 || p.y > nodes[0].y + nodeR
        val ly = if (k == 0) p.y - rr - Cy.dp(16f) else p.y + rr + Cy.dp(13f)
        val glyphX = p.x - nodeLabel.measureText(a.short) / 2f - Cy.dp(9f)
        Glyphs.draw(c, glyphFor(a), glyphX, ly - Cy.dp(4f), Cy.dp(11f), Cy.accent, 2f)
        Cy.text(c, a.short, p.x, ly, nodeLabel, Cy.primary)
        if (below) Cy.text(c, a.skill.uppercase(Locale.US), p.x, ly + Cy.dp(11f), nodeSkill, Cy.MUTED)
    }

    private fun glyphFor(a: Attr) = when (a) {
        Attr.BODY -> Glyph.BODY
        Attr.REFLEXES -> Glyph.REFLEXES
        Attr.TECH -> Glyph.TECH
        Attr.INT -> Glyph.INT
        Attr.COOL -> Glyph.COOL
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        if (nameBox.contains(x, y)) {
            game.haptic()
            game.askName()
            return
        }
        val k = nodes.indexOfFirst { hypot(x - it.x, y - it.y) < nodeR * 2.2f }
        if (k < 0) return
        val a = Attr.values()[k]
        game.haptic()
        val top = game.apps.filter { it.attr == a }.sortedByDescending { game.launches(it) }.take(3).filter { game.launches(it) > 0 }
        val tops = if (top.isEmpty()) "Nothing yet." else "Top: " + top.joinToString(", ") { game.esc(it.label) } + "."
        game.feed(Tag.XP, "<col=pr>${a.title} ${game.attrLevel(a)}</col> (${a.skill}). Trained by ${a.trains}. $tops")
        game.ui.openStash(a, null)
    }
}
