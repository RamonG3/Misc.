package com.ramong.cyberlauncher.ui.panels

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.SaveData
import com.ramong.cyberlauncher.core.Ware
import com.ramong.cyberlauncher.ui.Cy
import com.ramong.cyberlauncher.ui.Glyph
import com.ramong.cyberlauncher.ui.ItemDraw
import com.ramong.cyberlauncher.ui.Panel
import com.ramong.cyberlauncher.ui.PanelView
import kotlin.math.max
import kotlin.math.min

/** 24 slots of favourite apps. Tap to use, hold for the menu, hold and drag to move. */
class InventoryPanel(game: Game, host: PanelView) : Panel(game, host) {
    override val title = "INVENTORY"
    override val glyph = Glyph.INVENTORY
    override val scrollable get() = true
    private val cell = RectF()

    private fun cellW(r: RectF) = r.width() / COLS
    private fun cellH(r: RectF): Float {
        val minH = Cy.dp(if (game.save.labels) 62f else 50f)
        return min(cellW(r) * 0.86f, max(minH, r.height() / ROWS))
    }

    fun cellSize(r: RectF) = min(cellW(r), cellH(r))

    override fun contentHeight(r: RectF) = ROWS * cellH(r)

    override fun info() = "${game.save.inventory.count { game.app(it) != null }}/${SaveData.INVENTORY}"

    fun slotAt(x: Float, y: Float, r: RectF): Int {
        if (x < r.left || x > r.right || y < r.top) return -1
        val col = ((x - r.left) / cellW(r)).toInt()
        val row = ((y - r.top) / cellH(r)).toInt()
        val i = row * COLS + col
        return if (col in 0 until COLS && i in 0 until SaveData.INVENTORY) i else -1
    }

    private fun rectOf(i: Int, r: RectF): RectF {
        val w = cellW(r)
        val h = cellH(r)
        val g = Cy.dp(3f)
        val col = i % COLS
        val row = i / COLS
        cell.set(r.left + col * w + g, r.top + row * h + g, r.left + (col + 1) * w - g, r.top + (row + 1) * h - g)
        return cell
    }

    override fun draw(c: Canvas, r: RectF) {
        val dragging = host.drag?.slot ?: -1
        for (i in 0 until SaveData.INVENTORY) {
            val app = if (i == dragging) null else game.invApp(i)
            ItemDraw.tile(c, game, app, rectOf(i, r), pressed = false, labels = game.save.labels, emptyHint = i != dragging)
        }
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val slot = slotAt(x, y, r)
        if (slot < 0) return
        game.inventoryTap(slot, host.screenRect(RectF(rectOf(slot, r))))
    }

    companion object {
        const val COLS = 4
        const val ROWS = SaveData.INVENTORY / COLS
    }
}

/** The ripperdoc's view: ten more slots wired into a wireframe body. */
class CyberwarePanel(game: Game, host: PanelView) : Panel(game, host) {
    override val title = "CYBERWARE"
    override val glyph = Glyph.CYBERWARE
    override val scrollable get() = true
    private val slot = RectF()
    private val body = RectF()
    private val path = Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wareName = Cy.paint(8.5f, Cy.Face.MONO, spacing = 0.1f)
    private val wareNameRight = Cy.paint(8.5f, Cy.Face.MONO, Paint.Align.RIGHT, 0.1f)
    private val appName = Cy.paint(12f, Cy.Face.BOLD)
    private val appNameRight = Cy.paint(12f, Cy.Face.BOLD, Paint.Align.RIGHT)

    private val leftSide = listOf(Ware.CORTEX, Ware.OCULAR, Ware.CIRCULATORY, Ware.NERVOUS, Ware.ARMS)
    private val rightSide = listOf(Ware.OS, Ware.SKIN, Ware.SKELETON, Ware.HANDS, Ware.LEGS)

    private fun rowH(r: RectF) = max(Cy.dp(50f), r.height() / 5f)
    override fun contentHeight(r: RectF) = rowH(r) * 5

    override fun info() = "${game.save.cyberware.count { game.app(it) != null }}/${Ware.values().size} INSTALLED"

    private fun box(w: Ware, r: RectF): RectF {
        val h = rowH(r)
        val size = min(h - Cy.dp(10f), Cy.dp(46f))
        val left = w in leftSide
        val row = (if (left) leftSide else rightSide).indexOf(w)
        val cy = r.top + h * (row + 0.5f)
        val x = if (left) r.left + Cy.dp(2f) else r.right - Cy.dp(2f) - size
        slot.set(x, cy - size / 2f, x + size, cy + size / 2f)
        return slot
    }

    private fun wareAt(x: Float, y: Float, r: RectF): Ware? {
        val h = rowH(r)
        val row = ((y - r.top) / h).toInt()
        if (row !in 0 until 5) return null
        return when {
            x < r.left + r.width() * 0.4f -> leftSide[row]
            x > r.right - r.width() * 0.4f -> rightSide[row]
            else -> null
        }
    }

    /** Where each slot's wire lands on the body, in body-box fractions. */
    private fun anchor(w: Ware, b: RectF): PointF {
        val (fx, fy) = when (w) {
            Ware.CORTEX -> 0.5f to 0.04f
            Ware.OCULAR -> 0.44f to 0.09f
            Ware.CIRCULATORY -> 0.42f to 0.3f
            Ware.NERVOUS -> 0.5f to 0.45f
            Ware.ARMS -> 0.16f to 0.36f
            Ware.OS -> 0.58f to 0.07f
            Ware.SKIN -> 0.62f to 0.34f
            Ware.SKELETON -> 0.56f to 0.52f
            Ware.HANDS -> 0.88f to 0.56f
            Ware.LEGS -> 0.6f to 0.82f
        }
        return PointF(b.left + b.width() * fx, b.top + b.height() * fy)
    }

    override fun draw(c: Canvas, r: RectF) {
        val total = contentHeight(r)
        val bw = min(r.width() * 0.26f, total * 0.42f)
        body.set(r.centerX() - bw / 2f, r.top + Cy.dp(8f), r.centerX() + bw / 2f, r.top + total - Cy.dp(8f))
        drawBody(c, body)
        for (w in Ware.values()) {
            val b = RectF(box(w, r))
            val a = anchor(w, body)
            val left = w in leftSide
            val sx = if (left) b.right + Cy.dp(4f) else b.left - Cy.dp(4f)
            val elbow = if (left) body.left - Cy.dp(10f) else body.right + Cy.dp(10f)
            val installed = game.wareApp(w) != null
            line.color = if (installed) Cy.withAlpha(Cy.accent, 0x90) else Cy.faint
            line.strokeWidth = Cy.px
            path.reset()
            path.moveTo(sx, b.centerY())
            path.lineTo(elbow, b.centerY())
            path.lineTo(a.x, a.y)
            c.drawPath(path, line)
            fill.color = if (installed) Cy.accent else Cy.dim
            c.drawCircle(a.x, a.y, Cy.dp(if (installed) 2.6f else 1.8f), fill)
            val app = game.wareApp(w)
            ItemDraw.tile(c, game, app, b, pressed = false, labels = false)
            val tx = if (left) b.right + Cy.dp(8f) else b.left - Cy.dp(8f)
            val maxW = (if (left) elbow - tx else tx - elbow) - Cy.dp(4f)
            if (left) {
                Cy.text(c, w.short, tx, b.centerY() - Cy.dp(3f), wareName, Cy.dim)
                app?.let { Cy.text(c, Cy.ellipsize(it.label, appName, maxW), tx, b.centerY() + Cy.dp(11f), appName, Cy.TEXT) }
            } else {
                Cy.text(c, w.short, tx, b.centerY() - Cy.dp(3f), wareNameRight, Cy.dim)
                app?.let { Cy.text(c, Cy.ellipsize(it.label, appNameRight, maxW), tx, b.centerY() + Cy.dp(11f), appNameRight, Cy.TEXT) }
            }
        }
    }

    /** A wireframe body, front view: head, torso, arms, legs, a spine and a few ribs. */
    private fun drawBody(c: Canvas, b: RectF) {
        val x = b.centerX()
        val u = b.width()
        val h = b.height()
        fun px(f: Float) = b.left + u * f
        fun py(f: Float) = b.top + h * f
        line.color = Cy.withAlpha(Cy.primary, 0x80)
        line.strokeWidth = Cy.px * 1.2f
        path.reset()
        // Head.
        path.moveTo(px(0.42f), py(0.0f)); path.lineTo(px(0.58f), py(0.0f)); path.lineTo(px(0.63f), py(0.06f))
        path.lineTo(px(0.6f), py(0.13f)); path.lineTo(px(0.4f), py(0.13f)); path.lineTo(px(0.37f), py(0.06f)); path.close()
        // Neck and shoulders down to the waist.
        path.moveTo(px(0.45f), py(0.13f)); path.lineTo(px(0.45f), py(0.17f))
        path.moveTo(px(0.55f), py(0.13f)); path.lineTo(px(0.55f), py(0.17f))
        path.moveTo(px(0.2f), py(0.2f)); path.lineTo(px(0.8f), py(0.2f)); path.lineTo(px(0.7f), py(0.52f))
        path.lineTo(px(0.3f), py(0.52f)); path.close()
        // Arms.
        path.moveTo(px(0.2f), py(0.2f)); path.lineTo(px(0.1f), py(0.38f)); path.lineTo(px(0.08f), py(0.56f))
        path.moveTo(px(0.8f), py(0.2f)); path.lineTo(px(0.9f), py(0.38f)); path.lineTo(px(0.92f), py(0.56f))
        // Legs.
        path.moveTo(px(0.32f), py(0.52f)); path.lineTo(px(0.36f), py(0.76f)); path.lineTo(px(0.36f), py(1f))
        path.moveTo(px(0.68f), py(0.52f)); path.lineTo(px(0.64f), py(0.76f)); path.lineTo(px(0.64f), py(1f))
        path.moveTo(px(0.47f), py(0.55f)); path.lineTo(px(0.5f), py(0.6f)); path.lineTo(px(0.53f), py(0.55f))
        c.drawPath(path, line)
        line.color = Cy.faint
        path.reset()
        path.moveTo(x, py(0.17f)); path.lineTo(x, py(0.55f))
        for (k in 0 until 4) {
            val y = py(0.26f + k * 0.05f)
            path.moveTo(x - u * 0.2f, y); path.lineTo(x + u * 0.2f, y)
        }
        c.drawPath(path, line)
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val w = wareAt(x, y, r) ?: return
        game.wareTap(w, host.screenRect(RectF(box(w, r))))
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        val w = wareAt(x, y, r) ?: return
        if (game.wareApp(w) != null) game.wareMenu(w, menuX(x), menuY(y)) else game.pickFor(Game.Pick.Implant(w))
    }
}
