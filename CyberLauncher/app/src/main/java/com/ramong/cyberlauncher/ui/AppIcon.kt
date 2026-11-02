package com.ramong.cyberlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.ramong.cyberlauncher.core.AppEntry
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Rarity
import kotlin.math.min

/** Draws an app the way a launcher should: icon, label underneath, nothing in the way. */
object AppIcon {
    private val filtered = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letter = Cy.paint(16f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val label = Cy.paint(12.5f, Cy.Face.BODY, Paint.Align.CENTER).apply {
        setShadowLayer(Cy.dp(3f), 0f, Cy.dp(1f), 0xE0000000.toInt())
    }
    private val dst = RectF()
    private val path = Path()
    private val box = RectF()

    fun icon(c: Canvas, game: Game, app: AppEntry, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val bmp: Bitmap? = game.icons.get(app)
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        if (bmp == null) {
            Cy.cut(path, dst, size * 0.22f, Cy.TR or Cy.BL)
            placeholder.strokeWidth = Cy.px
            placeholder.color = Cy.withAlpha(Cy.primary, alpha / 2)
            c.drawPath(path, placeholder)
            letter.textSize = size * 0.45f
            Cy.text(c, app.label.take(1).uppercase(), cx, cy + size * 0.16f, letter, Cy.withAlpha(Cy.primary, alpha))
            return
        }
        filtered.alpha = alpha
        c.drawBitmap(bmp, null, dst, filtered)
        filtered.alpha = 255
    }

    /**
     * An app in a grid cell [r]: icon centred in the upper part, label below (if [labels]),
     * a short rarity bar under the label, corner brackets when [pressed].
     */
    fun cell(c: Canvas, game: Game, app: AppEntry, r: RectF, iconSize: Float, labels: Boolean, pressed: Boolean, alpha: Int = 255) {
        val labelH = if (labels) Cy.dp(18f) else 0f
        val top = r.centerY() - (iconSize + labelH) / 2f
        val cy = top + iconSize / 2f
        if (pressed) {
            val pad = Cy.dp(6f)
            box.set(r.centerX() - iconSize / 2f - pad, cy - iconSize / 2f - pad, r.centerX() + iconSize / 2f + pad, cy + iconSize / 2f + pad)
            Cy.rect(c, box, Cy.withAlpha(Cy.accent, 0x22))
            Cy.brackets(c, box, Cy.accent, Cy.dp(7f), Cy.px * 2f)
        }
        icon(c, game, app, r.centerX(), cy, iconSize, alpha)
        val rarity = game.rarity(app)
        val glow = game.save.rarityGlow && rarity.ordinal >= Rarity.UNCOMMON.ordinal
        if (labels) {
            label.textSize = Cy.dp(if (r.width() < Cy.dp(72f)) 11.5f else 12.5f)
            label.alpha = alpha
            val ly = top + iconSize + Cy.dp(15f)
            Cy.text(c, Cy.ellipsize(app.label, label, r.width() - Cy.dp(6f)), r.centerX(), ly, label, Cy.withAlpha(Cy.WHITE, alpha))
            if (glow) {
                val w = min(Cy.dp(16f), r.width() * 0.25f)
                Cy.rect(c, r.centerX() - w / 2f, ly + Cy.dp(4f), r.centerX() + w / 2f, ly + Cy.dp(6f), Cy.withAlpha(rarity.color, alpha))
            }
        } else if (glow) {
            val w = min(Cy.dp(14f), r.width() * 0.25f)
            val y = cy + iconSize / 2f + Cy.dp(5f)
            Cy.rect(c, r.centerX() - w / 2f, y, r.centerX() + w / 2f, y + Cy.dp(2f), Cy.withAlpha(rarity.color, alpha))
        }
    }

    /** Faint outline of an empty cell, shown while something is being dragged. */
    fun slot(c: Canvas, r: RectF, iconSize: Float, hover: Boolean) {
        box.set(r.centerX() - iconSize / 2f - Cy.dp(4f), r.centerY() - iconSize / 2f - Cy.dp(12f), r.centerX() + iconSize / 2f + Cy.dp(4f), r.centerY() + iconSize / 2f)
        if (hover) Cy.rect(c, box, Cy.withAlpha(Cy.accent, 0x30))
        Cy.brackets(c, box, if (hover) Cy.accent else Cy.withAlpha(Cy.WHITE, 0x50), Cy.dp(6f), Cy.px * 1.5f)
    }
}

/** "2H AGO", "3D AGO", or "ONLINE" for the last quarter of an hour. */
fun ago(t: Long): String {
    if (t <= 0L) return "NEVER"
    val m = (System.currentTimeMillis() - t) / 60_000L
    return when {
        m < 15 -> "ONLINE"
        m < 60 -> "${m}M AGO"
        m < 60 * 24 -> "${m / 60}H AGO"
        else -> "${m / (60 * 24)}D AGO"
    }
}
