package com.ramong.runelauncher.ui.panels

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ramong.runelauncher.core.EquipSlot
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.Quest
import com.ramong.runelauncher.core.Skill
import com.ramong.runelauncher.core.Xp
import com.ramong.runelauncher.ui.ItemDraw
import com.ramong.runelauncher.ui.Panel
import com.ramong.runelauncher.ui.Rs
import com.ramong.runelauncher.ui.SidePanelView
import com.ramong.runelauncher.ui.Sprite
import com.ramong.runelauncher.ui.Sprites
import kotlin.math.max
import kotlin.math.min

/** 28 slots, 4 x 7. Tap to open, hold to get the menu, hold and drag to rearrange. */
class InventoryPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val cell = RectF()
    private val label = Rs.paint(10.5f, align = Paint.Align.CENTER)

    fun cellSize(r: RectF) = min(r.width() / 4f, r.height() / 7f)

    fun slotRect(i: Int, r: RectF, out: RectF = cell): RectF {
        val cw = r.width() / 4f
        val ch = r.height() / 7f
        val col = i % 4
        val row = i / 4
        out.set(r.left + col * cw, r.top + row * ch, r.left + (col + 1) * cw, r.top + (row + 1) * ch)
        return out
    }

    fun slotAt(x: Float, y: Float, r: RectF): Int {
        if (!r.contains(x, y)) return -1
        val col = ((x - r.left) / (r.width() / 4f)).toInt().coerceIn(0, 3)
        val row = ((y - r.top) / (r.height() / 7f)).toInt().coerceIn(0, 6)
        return row * 4 + col
    }

    override fun draw(c: Canvas, r: RectF) {
        val d = host.drag
        val labels = game.save.invLabels
        var any = false
        for (i in 0 until 28) {
            val app = game.invApp(i) ?: continue
            any = true
            val s = slotRect(i, r)
            val size = min(s.width(), s.height() * if (labels) 0.66f else 0.92f) * 0.82f
            val cy = if (labels) s.top + s.height() * 0.4f else s.centerY()
            val dragging = d != null && d.slot == i && d.moved
            ItemDraw.icon(c, game, app, s.centerX(), cy, size, highlight = game.useSlot == i, alpha = if (dragging) 70 else 255)
            if (labels) {
                Rs.text(c, Rs.ellipsize(app.label, label, s.width() - Rs.dp(4f)), s.centerX(), s.bottom - Rs.dp(4f), label, Rs.ORANGE)
            }
        }
        if (d != null && d.moved) {
            val t = slotAt(d.x, d.y + host.scrollOf(this), r)
            if (t >= 0) Rs.outline(c, slotRect(t, r), 0x90FFFFFF.toInt(), Rs.px)
        }
        if (!any) {
            var y = r.centerY() - Rs.dp(10f)
            for (l in Rs.wrap("Your inventory is empty. Swipe up or tap the bank chest, then Withdraw-1.", bodyCenter, r.width() - Rs.dp(24f))) {
                Rs.text(c, l, r.centerX(), y, bodyCenter, Rs.ORANGE)
                y += Rs.lineHeight(bodyCenter)
            }
        }
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val s = slotAt(x, y, r)
        if (s < 0) return
        game.inventoryTap(s, host.screenRect(slotRect(s, r, RectF())))
    }
}

/** The paper-doll: apps you "wield" live here as a second set of favourites. */
class EquipmentPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val grid = mapOf(
        EquipSlot.HEAD to (1 to 0), EquipSlot.CAPE to (0 to 1), EquipSlot.NECK to (1 to 1), EquipSlot.AMMO to (2 to 1),
        EquipSlot.WEAPON to (0 to 2), EquipSlot.BODY to (1 to 2), EquipSlot.SHIELD to (2 to 2), EquipSlot.LEGS to (1 to 3),
        EquipSlot.HANDS to (0 to 4), EquipSlot.FEET to (1 to 4), EquipSlot.RING to (2 to 4),
    )
    private val rects = HashMap<EquipSlot, RectF>()
    private val statsBtn = RectF()
    private val deathBtn = RectF()
    private val btnText = Rs.paint(12.5f, align = Paint.Align.CENTER)
    private val ghost = Sprites.silhouette(0x66000000)

    private fun silhouette(s: EquipSlot): Sprite = when (s) {
        EquipSlot.HEAD -> Sprites.HELMET
        EquipSlot.CAPE -> Sprites.CAPE
        EquipSlot.NECK -> Sprites.AMULET
        EquipSlot.AMMO -> Sprites.ARROWS
        EquipSlot.WEAPON -> Sprites.ATTACK
        EquipSlot.BODY -> Sprites.PLATEBODY
        EquipSlot.SHIELD -> Sprites.DEFENCE
        EquipSlot.LEGS -> Sprites.LEGS
        EquipSlot.HANDS -> Sprites.GLOVE
        EquipSlot.FEET -> Sprites.BOOT
        EquipSlot.RING -> Sprites.RING
    }

    private fun layout(r: RectF) {
        val btnH = Rs.dp(34f)
        val gap = Rs.dp(6f)
        deathBtn.set(r.left + Rs.dp(8f), r.bottom - btnH, r.right - Rs.dp(8f), r.bottom)
        statsBtn.set(deathBtn.left, deathBtn.top - gap - btnH, deathBtn.right, deathBtn.top - gap)
        val top = r.top + Rs.dp(30f)
        val gridH = statsBtn.top - gap - top
        val size = min(gridH / 5.5f, r.width() / 4.6f)
        val rowStep = (gridH - size) / 4f
        val colStep = size * 1.5f
        for ((slot, pos) in grid) {
            val cx = r.centerX() + (pos.first - 1) * colStep
            val y = top + pos.second * rowStep
            rects.getOrPut(slot) { RectF() }.set(cx - size / 2f, y, cx + size / 2f, y + size)
        }
    }

    override fun draw(c: Canvas, r: RectF) {
        header(c, r, "Worn Equipment")
        layout(r)
        val line = 0xFF2A241C.toInt()
        val head = rects.getValue(EquipSlot.HEAD)
        val feet = rects.getValue(EquipSlot.FEET)
        Rs.rect(c, head.centerX() - Rs.px, head.centerY(), head.centerX() + Rs.px, feet.centerY(), line)
        for (row in listOf(EquipSlot.CAPE, EquipSlot.WEAPON, EquipSlot.HANDS)) {
            val a = rects.getValue(row)
            val b = rects.getValue(
                when (row) {
                    EquipSlot.CAPE -> EquipSlot.AMMO
                    EquipSlot.WEAPON -> EquipSlot.SHIELD
                    else -> EquipSlot.RING
                }
            )
            Rs.rect(c, a.centerX(), a.centerY() - Rs.px, b.centerX(), a.centerY() + Rs.px, line)
        }
        for ((slot, rect) in rects) {
            Rs.stone(c, rect, light = true)
            Rs.bevel(c, rect, sunken = true)
            val app = game.equipApp(slot)
            if (app != null) {
                ItemDraw.icon(c, game, app, rect.centerX(), rect.centerY(), rect.width() * 0.78f)
            } else {
                silhouette(slot).draw(c, rect.centerX(), rect.centerY(), rect.width() * 0.62f, ghost)
            }
        }
        Rs.button(c, statsBtn)
        Rs.text(c, "Equipment Stats", statsBtn.centerX(), Rs.baseline(btnText, statsBtn.top, statsBtn.bottom), btnText, Rs.ORANGE)
        Rs.button(c, deathBtn)
        Rs.text(c, "Items Kept on Death", deathBtn.centerX(), Rs.baseline(btnText, deathBtn.top, deathBtn.bottom), btnText, Rs.ORANGE)
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        layout(r)
        if (statsBtn.contains(x, y)) return game.equipmentStats()
        if (deathBtn.contains(x, y)) return game.itemsKeptOnDeath()
        for ((slot, rect) in rects) {
            if (rect.contains(x, y)) {
                val app = game.equipApp(slot)
                if (app != null) game.launch(app, host.screenRect(rect)) else game.message("You have nothing in your ${slot.title.lowercase()} slot.")
                return
            }
        }
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        layout(r)
        for ((slot, rect) in rects) if (rect.contains(x, y)) return game.equipmentMenu(slot, menuX(x), menuY(y))
    }
}

/** Attack styles double as the icon style picker. Auto Retaliate is haptics. Obviously. */
class CombatPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val styles = arrayOf(
        Triple("Punch", "Chunky pixels", Sprites.STRENGTH),
        Triple("Kick", "Fine pixels", Sprites.BOOT),
        Triple("Block", "HD icons", Sprites.DEFENCE),
    )
    private val boxes = Array(3) { RectF() }
    private val retaliate = RectF()
    private val spec = RectF()
    private val name = Rs.paint(14f, bold = true, align = Paint.Align.CENTER)
    private val sub = Rs.paint(11f, align = Paint.Align.CENTER)

    private fun layout(r: RectF) {
        val top = r.top + Rs.dp(56f)
        val gap = Rs.dp(6f)
        val bw = (r.width() - gap - Rs.dp(8f)) / 2f
        val bh = min(Rs.dp(70f), (r.height() - Rs.dp(56f) - Rs.dp(110f)) / 2f)
        for (i in 0 until 3) {
            val col = i % 2
            val row = i / 2
            val l = r.left + Rs.dp(4f) + col * (bw + gap)
            val t = top + row * (bh + gap)
            boxes[i].set(l, t, l + bw, t + bh)
        }
        retaliate.set(r.left + Rs.dp(4f), boxes[2].bottom + gap * 2, r.right - Rs.dp(4f), boxes[2].bottom + gap * 2 + Rs.dp(38f))
        spec.set(retaliate.left, retaliate.bottom + gap * 2, retaliate.right, retaliate.bottom + gap * 2 + Rs.dp(28f))
    }

    override fun draw(c: Canvas, r: RectF) {
        val weapon = game.equipApp(EquipSlot.WEAPON)?.label ?: "Unarmed"
        val y = header(c, r, Rs.ellipsize(weapon, title, r.width() - Rs.dp(12f)))
        Rs.text(c, "Combat Lvl: ${game.combatLevel()}", r.centerX(), y + Rs.dp(8f), bodyCenter, Rs.ORANGE)
        layout(r)
        for (i in 0 until 3) {
            val b = boxes[i]
            Rs.button(c, b, selected = game.save.iconStyle == i)
            styles[i].third.draw(c, b.centerX(), b.top + b.height() * 0.32f, b.height() * 0.36f)
            Rs.text(c, styles[i].first, b.centerX(), b.top + b.height() * 0.72f, name, Rs.ORANGE)
            Rs.text(c, styles[i].second, b.centerX(), b.top + b.height() * 0.9f, sub, Rs.WHITE)
        }
        Rs.button(c, retaliate, selected = game.save.haptics)
        val state = if (game.save.haptics) "(On)" else "(Off)"
        Rs.text(c, "Auto Retaliate $state", retaliate.centerX(), Rs.baseline(bodyCenter, retaliate.top, retaliate.bottom), bodyCenter, Rs.ORANGE)
        Rs.rect(c, spec, Rs.BLACK)
        val frac = game.stats.volumePct / 100f
        Rs.rect(c, spec.left + Rs.px, spec.top + Rs.px, spec.left + Rs.px + (spec.width() - 2 * Rs.px) * frac, spec.bottom - Rs.px, 0xFF3A8A2A.toInt())
        Rs.outline(c, spec, 0xFF5D5447.toInt())
        Rs.text(c, "Special Attack: ${game.stats.volumePct}%", spec.centerX(), Rs.baseline(bodyCenter, spec.top, spec.bottom), bodyCenter, Rs.WHITE)
        Rs.text(c, "(Item style, haptics and media volume.)", r.centerX(), spec.bottom + Rs.dp(18f), small, 0xFFB0A080.toInt())
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        layout(r)
        for (i in 0 until 3) {
            if (boxes[i].contains(x, y)) {
                game.haptic()
                game.setIconStyle(i)
                return
            }
        }
        if (retaliate.contains(x, y)) {
            game.save.haptics = !game.save.haptics
            game.save.save()
            game.haptic()
        } else if (spec.contains(x, y)) {
            game.actions.showVolume()
        }
    }
}

/** 23 skills in the classic 3-column grid, plus total level. Tap for XP, hold to see the apps. */
class SkillsPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private var tip = -1
    private val cell = RectF()
    private val lvl = Rs.paint(13.5f, bold = true, align = Paint.Align.CENTER)
    private val tipPaint = Rs.paint(12f)
    private val totalPaint = Rs.paint(11.5f, align = Paint.Align.CENTER)
    private val tipRect = RectF()

    private fun cellRect(i: Int, r: RectF): RectF {
        val cw = r.width() / 3f
        val ch = r.height() / 8f
        val col = i % 3
        val row = i / 3
        cell.set(r.left + col * cw, r.top + row * ch, r.left + (col + 1) * cw, r.top + (row + 1) * ch)
        cell.inset(Rs.dp(1.5f), Rs.dp(1.5f))
        return cell
    }

    override fun draw(c: Canvas, r: RectF) {
        for (s in Skill.values()) {
            val b = cellRect(s.ordinal, r)
            Rs.well(c, b, 0x40)
            val icon = min(b.height() * 0.66f, b.width() * 0.34f)
            Sprites.skill(s).draw(c, b.left + Rs.dp(6f) + icon / 2f, b.centerY(), icon)
            val l = game.level(s).toString()
            val nx = b.left + b.width() * 0.68f
            Rs.text(c, l, nx - Rs.dp(5f), b.top + b.height() * 0.46f, lvl, Rs.YELLOW)
            Rs.text(c, "/", nx + Rs.dp(2f), b.top + b.height() * 0.66f, lvl, Rs.YELLOW)
            Rs.text(c, l, nx + Rs.dp(10f), b.top + b.height() * 0.88f, lvl, Rs.YELLOW)
        }
        val t = cellRect(23, r)
        Rs.well(c, t, 0x40)
        Rs.text(c, "Total level:", t.centerX(), t.top + t.height() * 0.42f, totalPaint, Rs.YELLOW)
        Rs.text(c, game.totalLevel().toString(), t.centerX(), t.top + t.height() * 0.84f, lvl, Rs.YELLOW)
        if (tip >= 0) drawTip(c, r)
    }

    private fun drawTip(c: Canvas, r: RectF) {
        val lines = if (tip < 23) {
            val s = Skill.values()[tip]
            val xp = game.xp(s)
            val level = game.level(s)
            val out = arrayListOf("${s.title} XP: ${Rs.number(xp)}")
            if (level < 99) {
                val next = Xp.forLevel(level + 1)
                out += "Next level at: ${Rs.number(next)}"
                out += "Remaining XP: ${Rs.number(next - xp)}"
            }
            val n = game.appsTraining(s)
            out += "Trained by $n item${if (n == 1) "" else "s"}. Hold to view."
            out
        } else {
            listOf(
                "Total XP: ${Rs.number(game.totalXp())}",
                "Combat level: ${game.combatLevel()}",
                "Quest points: ${game.questPoints()}",
            )
        }
        val pad = Rs.dp(6f)
        val lh = Rs.lineHeight(tipPaint)
        val w = lines.maxOf { tipPaint.measureText(it) } + pad * 2
        val h = lh * lines.size + pad * 2 - (lh - tipPaint.textSize)
        val anchor = RectF(cellRect(tip, r))
        var left = anchor.centerX() - w / 2f
        left = left.coerceIn(r.left, max(r.left, r.right - w))
        var top = anchor.bottom + Rs.dp(2f)
        if (top + h > r.bottom) top = anchor.top - h - Rs.dp(2f)
        tipRect.set(left, top, left + w, top + h)
        Rs.rect(c, tipRect, Rs.TOOLTIP)
        Rs.outline(c, tipRect, Rs.BLACK)
        var y = top + pad + tipPaint.textSize * 0.9f
        for (l in lines) {
            Rs.text(c, l, left + pad, y, tipPaint, Rs.BLACK, shadow = false)
            y += lh
        }
    }

    private fun indexAt(x: Float, y: Float, r: RectF): Int {
        if (!r.contains(x, y)) return -1
        val col = ((x - r.left) / (r.width() / 3f)).toInt().coerceIn(0, 2)
        val row = ((y - r.top) / (r.height() / 8f)).toInt().coerceIn(0, 7)
        return row * 3 + col
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val i = indexAt(x, y, r)
        tip = if (i == tip || i < 0) -1 else i
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        val i = indexAt(x, y, r)
        tip = -1
        if (i in 0 until 23) {
            game.haptic()
            game.ui.openBank(false, Skill.values()[i])
        }
    }
}

/** A real to-do list wearing a quest log. Red, yellow, green. Tap to progress, hold to abandon. */
class QuestPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val rowH get() = Rs.dp(30f)
    private val questPaint = Rs.paint(14f)

    override val scrollable = true

    private fun listTop(r: RectF) = r.top + Rs.dp(56f)

    override fun contentHeight(r: RectF): Float = listTop(r) - r.top + (game.save.quests.size + 1) * rowH + Rs.dp(12f)

    override fun draw(c: Canvas, r: RectF) {
        val y = header(c, r, "Quest List")
        Rs.text(c, "Quest Points: ${game.questPoints()}", r.centerX(), y + Rs.dp(8f), bodyCenter, Rs.ORANGE)
        var top = listTop(r)
        Rs.rect(c, r.left + Rs.dp(6f), top - Rs.dp(4f), r.right - Rs.dp(6f), top - Rs.dp(4f) + Rs.px, 0xFF1E1A14.toInt())
        for (q in game.save.quests) {
            val color = when (q.state) {
                Quest.DONE -> Rs.GREEN
                Quest.STARTED -> Rs.YELLOW
                else -> Rs.RED
            }
            val text = Rs.ellipsize(q.name, questPaint, r.width() - Rs.dp(20f))
            Rs.text(c, text, r.left + Rs.dp(10f), Rs.baseline(questPaint, top, top + rowH), questPaint, color)
            top += rowH
        }
        Rs.text(c, "+ Start a new quest...", r.left + Rs.dp(10f), Rs.baseline(questPaint, top, top + rowH), questPaint, Rs.WHITE)
    }

    private fun rowAt(y: Float, r: RectF): Int {
        val top = listTop(r)
        if (y < top) return -2
        return ((y - top) / rowH).toInt()
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val i = rowAt(y, r)
        val quests = game.save.quests
        when {
            i in quests.indices -> {
                game.haptic()
                game.questTap(quests[i])
            }
            i == quests.size -> {
                game.haptic()
                game.newQuest()
            }
        }
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        val i = rowAt(y, r)
        val quests = game.save.quests
        if (i in quests.indices) game.questMenu(quests[i], menuX(x), menuY(y))
    }
}
