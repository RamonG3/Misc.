package com.ramong.runelauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.ramong.runelauncher.core.AppEntry
import com.ramong.runelauncher.core.Folder
import com.ramong.runelauncher.core.Game
import kotlin.math.min

/** Draws apps and folders the way a launcher should: icon, label underneath, nothing in the way. */
object AppIcon {
    private val filtered = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val crisp = Paint()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letter = R3.paint(16f, R3.Face.TITLE_BOLD, Paint.Align.CENTER)
    private val label = R3.paint(12.5f, R3.Face.BOLD, Paint.Align.CENTER)
    private val dst = RectF()
    private val box = RectF()

    /** Re-reads fonts after R3.init (paints are created before fonts load in tests). */
    fun refreshFonts() {
        letter.typeface = R3.titleBold
        label.typeface = R3.bodyBold
    }

    fun icon(c: Canvas, game: Game, app: AppEntry, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val bmp: Bitmap? = game.icons.get(app)
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        if (bmp == null) {
            disc.shader = null
            disc.color = R3.withAlpha(R3.bg, alpha * 3 / 4)
            c.drawCircle(cx, cy, size / 2f, disc)
            ring.shader = null
            ring.strokeWidth = R3.px
            ring.color = R3.withAlpha(R3.frame, alpha / 2)
            c.drawCircle(cx, cy, size / 2f - R3.px, ring)
            letter.textSize = size * 0.42f
            R3.text(c, app.label.take(1).uppercase(), cx, cy + size * 0.15f, letter, R3.withAlpha(R3.hi, alpha))
            return
        }
        val p = if (game.icons.style == 2) crisp else filtered
        p.alpha = alpha
        c.drawBitmap(bmp, null, dst, p)
        p.alpha = 255
    }

    /** A folder: a dark glass disc in a metal rim with up to four of its apps inside. */
    fun folder(c: Canvas, game: Game, f: Folder, cx: Float, cy: Float, size: Float, alpha: Int = 255, open: Boolean = false) {
        val r = size / 2f
        disc.shader = null
        disc.color = R3.withAlpha(R3.mix(R3.bg, R3.frame, 0.12f), (0xE6 * alpha) / 255)
        disc.setShadowLayer(size * 0.06f, 0f, size * 0.03f, R3.withAlpha(R3.BLACK, 0x90 * alpha / 255))
        c.drawCircle(cx, cy, r * (if (open) 1.08f else 1f), disc)
        disc.clearShadowLayer()
        ring.shader = LinearGradient(0f, cy - r, 0f, cy + r, R3.frameLight, R3.frameDark, Shader.TileMode.CLAMP)
        ring.alpha = alpha
        ring.strokeWidth = size / 20f
        c.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        ring.shader = null
        val apps = game.folderApps(f).take(4)
        val mini = size * 0.33f
        val off = size * 0.19f
        apps.forEachIndexed { i, a ->
            val dx = if (i % 2 == 0) -off else off
            val dy = if (i < 2) -off else off
            // A single app sits in the middle, two side by side, three or four in a square.
            val x = when (apps.size) {
                1 -> cx
                else -> cx + dx
            }
            val y = when (apps.size) {
                1 -> cy
                2 -> cy
                else -> cy + dy
            }
            icon(c, game, a, x, y, if (apps.size == 1) size * 0.5f else mini, alpha)
        }
    }

    private fun labelFor(r: RectF) {
        label.textSize = R3.dp(if (r.width() < R3.dp(72f)) 11.5f else 12.5f)
    }

    /** An app in a grid cell [r]: icon centred in the upper part, label below, a glow when [pressed]. */
    fun cell(c: Canvas, game: Game, app: AppEntry, r: RectF, iconSize: Float, labels: Boolean, pressed: Boolean, alpha: Int = 255) {
        val labelH = if (labels) R3.dp(18f) else 0f
        val top = r.centerY() - (iconSize + labelH) / 2f
        val cy = top + iconSize / 2f
        if (pressed) pressGlow(c, r.centerX(), cy, iconSize)
        icon(c, game, app, r.centerX(), cy, iconSize, alpha)
        if (labels) drawLabel(c, app.label, r, top + iconSize + R3.dp(15f), alpha)
    }

    fun folderCell(c: Canvas, game: Game, f: Folder, r: RectF, iconSize: Float, labels: Boolean, pressed: Boolean, alpha: Int = 255) {
        val labelH = if (labels) R3.dp(18f) else 0f
        val top = r.centerY() - (iconSize + labelH) / 2f
        val cy = top + iconSize / 2f
        if (pressed) pressGlow(c, r.centerX(), cy, iconSize)
        folder(c, game, f, r.centerX(), cy, iconSize, alpha)
        if (labels) drawLabel(c, f.name, r, top + iconSize + R3.dp(15f), alpha)
    }

    /** Centre of the icon inside a cell, for hit tests and the folder-merge ring. */
    fun iconCenterY(r: RectF, iconSize: Float, labels: Boolean): Float {
        val labelH = if (labels) R3.dp(18f) else 0f
        return r.centerY() - (iconSize + labelH) / 2f + iconSize / 2f
    }

    private fun drawLabel(c: Canvas, text: String, r: RectF, y: Float, alpha: Int) {
        labelFor(r)
        label.alpha = alpha
        R3.text(c, R3.ellipsize(text, label, r.width() - R3.dp(6f)), r.centerX(), y, label, R3.withAlpha(R3.WHITE, alpha), shadow = true)
    }

    private fun pressGlow(c: Canvas, cx: Float, cy: Float, size: Float) {
        disc.shader = null
        disc.color = R3.withAlpha(R3.hi, 0x30)
        c.drawCircle(cx, cy, size * 0.62f, disc)
    }

    /** Faint outline of an empty cell, shown while something is being dragged. */
    fun slot(c: Canvas, r: RectF, iconSize: Float, labels: Boolean, hover: Boolean) {
        val cy = iconCenterY(r, iconSize, labels)
        ring.shader = null
        ring.strokeWidth = R3.px * 1.4f
        ring.color = if (hover) R3.hi else R3.withAlpha(R3.WHITE, 0x50)
        if (hover) {
            disc.shader = null
            disc.color = R3.withAlpha(R3.hi, 0x26)
            c.drawCircle(r.centerX(), cy, iconSize / 2f, disc)
        }
        c.drawCircle(r.centerX(), cy, iconSize / 2f, ring)
    }

    /** The gold ring that says "let go here to make (or join) a folder". */
    fun mergeRing(c: Canvas, cx: Float, cy: Float, size: Float) {
        disc.shader = null
        disc.color = R3.withAlpha(R3.hi, 0x38)
        c.drawCircle(cx, cy, size * 0.66f, disc)
        ring.shader = null
        ring.color = R3.hi
        ring.strokeWidth = R3.dp(2f)
        c.drawCircle(cx, cy, size * 0.66f, ring)
    }

    fun miniSize(cellW: Float) = min(R3.dp(42f), cellW * 0.6f)
}
