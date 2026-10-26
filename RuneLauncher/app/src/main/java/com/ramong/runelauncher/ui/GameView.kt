package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.Skill
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The "game screen": clock, minimap and orbs, the player, the bank chest and all the effects. */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val game: Game) : View(context) {
    var topInset = 0
        set(v) {
            field = v
            layoutElements()
            invalidate()
        }

    private val hoverPaint = Rs.paint(13f)
    private val clockPaint = Rs.paint(46f, bold = true)
    private val ampmPaint = Rs.paint(16f, bold = true)
    private val datePaint = Rs.paint(15f)
    private val orbPaint = Rs.paint(12f, bold = true, align = Paint.Align.CENTER)
    private val dropPaint = Rs.paint(14f, bold = true)
    private val overheadPaint = Rs.paint(15f, bold = true, align = Paint.Align.CENTER)
    private val compassPaint = Rs.paint(9f, bold = true, align = Paint.Align.CENTER)
    private val aa = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flat = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val scrim = Paint()
    private val clip = Path()
    private val tmp = RectF()
    private val path = Path()

    private var mmCx = 0f
    private var mmCy = 0f
    private var mmR = 0f
    private var orbR = 0f
    private val orbPos = Array(4) { PointF() }
    private val worldPos = PointF()
    private val compassPos = PointF()
    private val chest = RectF()
    private val playerBox = RectF()
    private val clockBox = RectF()
    private val dateBox = RectF()
    private var hoverY = 0f
    private var clockY = 0f
    private var dateY = 0f
    private var footX = 0f
    private var footY = 0f
    private var scale = 0f

    private var map: Bitmap? = null
    private var mapPixels = IntArray(0)
    private var mapSeed = (System.currentTimeMillis() / 86_400_000L).toInt()
    private val npcs = ArrayList<IntArray>()
    private val items = ArrayList<IntArray>()
    private var flag: PointF? = null
    private var flagUntil = 0L

    private class Cross(val x: Float, val y: Float, val red: Boolean, val start: Long)

    private var cross: Cross? = null

    private val avatar = Avatar()
    private var emote: Emote? = null
    private var emoteStart = 0L
    private var breathe = false
    private var overhead: Overhead? = null

    private class Drop(val skill: Skill, val amount: Int, val start: Long, val row: Int)

    private val drops = ArrayList<Drop>()

    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, val life: Float, val start: Long)

    private val sparks = ArrayList<Spark>()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            game.groundMenu(left + e.x, top + e.y)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val dy = e2.y - (e1?.y ?: e2.y)
            if (abs(velocityY) > abs(velocityX) && abs(dy) > Rs.dp(40f)) {
                if (velocityY < 0) game.ui.openBank(false, null) else game.actions.expandNotifications()
                return true
            }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutElements()
    }

    private fun layoutElements() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val top = topInset.toFloat()
        mmR = min(Rs.dp(64f), w * 0.16f)
        orbR = mmR * 0.24f
        mmCx = w - mmR - Rs.dp(10f)
        mmCy = top + mmR + Rs.dp(10f)
        val angles = floatArrayOf(196f, 166f, 136f, 106f)
        val dist = mmR + orbR * 1.15f
        for (i in 0..3) {
            val a = Math.toRadians(angles[i].toDouble())
            orbPos[i].set(mmCx + cos(a).toFloat() * dist, mmCy + sin(a).toFloat() * dist)
        }
        val ca = Math.toRadians(234.0)
        compassPos.set(mmCx + cos(ca).toFloat() * (mmR + orbR * 0.55f), mmCy + sin(ca).toFloat() * (mmR + orbR * 0.55f))
        val wa = Math.toRadians(52.0)
        worldPos.set(mmCx + cos(wa).toFloat() * (mmR + orbR * 0.5f), mmCy + sin(wa).toFloat() * (mmR + orbR * 0.5f))

        hoverY = top + Rs.dp(18f)
        clockY = top + Rs.dp(68f)
        dateY = clockY + Rs.dp(24f)
        clockBox.set(0f, hoverY + Rs.dp(4f), w * 0.5f, clockY + Rs.dp(6f))
        dateBox.set(0f, clockY + Rs.dp(6f), w * 0.5f, dateY + Rs.dp(8f))

        footY = h - Rs.dp(14f)
        val avail = footY - (dateY + Rs.dp(34f))
        scale = (min(Rs.dp(2.4f), avail / 40f)).coerceAtLeast(Rs.dp(1.1f))
        footX = w * 0.28f
        val chestSize = scale * 17f
        val cx = w * 0.53f
        chest.set(cx - chestSize / 2f, footY - chestSize * 0.78f, cx + chestSize / 2f, footY + chestSize * 0.02f)
        playerBox.set(footX - 8 * scale, footY - 34 * scale, footX + 8 * scale, footY)

        scrim.shader = LinearGradient(
            0f, 0f, 0f, dateY + Rs.dp(40f), 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP,
        )
        if (map == null) buildMap()
    }

    // ------------------------------------------------------------------ minimap

    private fun buildMap() {
        val m = MAP
        val r = Random(mapSeed)
        val px = IntArray(m * m)
        val grass = intArrayOf(0xFF3B6B2A.toInt(), 0xFF447A30.toInt(), 0xFF335E24.toInt())
        px.fill(grass[0])
        repeat(110) {
            val cx = r.nextInt(m)
            val cy = r.nextInt(m)
            val rad = 2 + r.nextInt(4)
            val col = grass[1 + r.nextInt(2)]
            for (y in cy - rad..cy + rad) for (x in cx - rad..cx + rad) {
                if (x in 0 until m && y in 0 until m && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) px[y * m + x] = col
            }
        }
        val water = 0xFF2B4A86.toInt()
        val shore = 0xFF3A5F9E.toInt()
        val rx0 = m * (0.12f + r.nextFloat() * 0.22f)
        val amp = 3f + r.nextFloat() * 5f
        val freq = 0.07f + r.nextFloat() * 0.08f
        for (y in 0 until m) {
            val cx = rx0 + sin(y * freq + mapSeed) * amp
            for (x in (cx - 3.5f).toInt()..(cx + 3.5f).toInt()) {
                if (x in 0 until m) px[y * m + x] = if (abs(x - cx) > 2.4f) shore else water
            }
        }
        val path = 0xFF6F6047.toInt()
        val bridge = 0xFF7A5A32.toInt()
        val py0 = m * (0.58f + r.nextFloat() * 0.18f)
        for (x in 0 until m) {
            val cy = py0 + sin(x * 0.11f + mapSeed * 0.7f) * 3f
            for (y in (cy - 1.5f).toInt()..(cy + 1.5f).toInt()) {
                if (y in 0 until m) px[y * m + x] = if (px[y * m + x] == water || px[y * m + x] == shore) bridge else path
            }
        }
        val bx = m / 2 + r.nextInt(m / 5)
        val by = m / 6 + r.nextInt(m / 6)
        val bw = 9 + r.nextInt(6)
        val bh = 7 + r.nextInt(5)
        for (y in by until by + bh) for (x in bx until bx + bw) {
            if (x !in 0 until m || y !in 0 until m) continue
            val edge = x == bx || y == by || x == bx + bw - 1 || y == by + bh - 1
            val door = y == by + bh - 1 && (x == bx + bw / 2 || x == bx + bw / 2 - 1)
            px[y * m + x] = when {
                edge && !door -> 0xFFFFFFFF.toInt()
                (x + y) % 2 == 0 -> 0xFF5E5E5E.toInt()
                else -> 0xFF676767.toInt()
            }
        }
        repeat(34) {
            val x = r.nextInt(m - 1)
            val y = r.nextInt(m - 1)
            if (grass.contains(px[y * m + x]) && grass.contains(px[(y + 1) * m + x + 1])) {
                px[y * m + x] = 0xFF2F6A20.toInt()
                px[y * m + x + 1] = 0xFF1E4515.toInt()
                px[(y + 1) * m + x] = 0xFF1E4515.toInt()
                px[(y + 1) * m + x + 1] = 0xFF163610.toInt()
            }
        }
        mapPixels = px
        map = Bitmap.createBitmap(px, m, m, Bitmap.Config.ARGB_8888)
        npcs.clear()
        items.clear()
        repeat(6) { walkable(r)?.let { npcs += it } }
        repeat(2) { walkable(r)?.let { items += it } }
    }

    private fun walkable(r: Random): IntArray? {
        repeat(40) {
            val x = 8 + r.nextInt(MAP - 16)
            val y = 8 + r.nextInt(MAP - 16)
            if (isWalkable(x, y)) return intArrayOf(x, y)
        }
        return null
    }

    private fun isWalkable(x: Int, y: Int): Boolean {
        if (x !in 0 until MAP || y !in 0 until MAP) return false
        val c = mapPixels[y * MAP + x]
        return c != 0xFF2B4A86.toInt() && c != 0xFF3A5F9E.toInt() && c != 0xFFFFFFFF.toInt()
    }

    fun worldHop() {
        mapSeed = Random.nextInt()
        buildMap()
        invalidate()
    }

    /** One game tick (600ms): NPCs wander, the player breathes, the clock updates. */
    fun tick() {
        val r = Random
        for (n in npcs) {
            if (r.nextInt(3) == 0) {
                val nx = n[0] + r.nextInt(3) - 1
                val ny = n[1] + r.nextInt(3) - 1
                val dx = nx - MAP / 2
                val dy = ny - MAP / 2
                if (isWalkable(nx, ny) && dx * dx + dy * dy < (MAP / 2 - 2) * (MAP / 2 - 2)) {
                    n[0] = nx
                    n[1] = ny
                }
            }
        }
        breathe = !breathe
        invalidate()
    }

    // ------------------------------------------------------------------ effects

    fun xpDrop(skill: Skill, amount: Int) {
        val now = SystemClock.uptimeMillis()
        val row = drops.count { now - it.start < 300 }
        drops += Drop(skill, amount, now, row)
        invalidate()
    }

    fun fireworks() {
        val now = SystemClock.uptimeMillis()
        val colors = intArrayOf(Rs.YELLOW, Rs.ORANGE, Rs.RED, Rs.CYAN, Rs.GREEN, Rs.WHITE, 0xFFFF00FF.toInt())
        val cx = footX
        val cy = footY - 18 * scale
        repeat(80) {
            val a = Random.nextDouble(0.0, Math.PI * 2)
            val v = Rs.dp(50f + Random.nextFloat() * 130f)
            sparks += Spark(cx, cy, cos(a).toFloat() * v, sin(a).toFloat() * v - Rs.dp(60f), colors.random(), 1.1f + Random.nextFloat() * 0.8f, now)
        }
        invalidate()
    }

    private fun castSparkles() {
        val now = SystemClock.uptimeMillis()
        repeat(24) {
            val x = footX + (Random.nextFloat() - 0.5f) * 20 * scale
            sparks += Spark(x, footY - 28 * scale, (Random.nextFloat() - 0.5f) * Rs.dp(40f), -Rs.dp(40f + Random.nextFloat() * 60f),
                if (Random.nextBoolean()) Rs.CYAN else Rs.WHITE, 0.7f + Random.nextFloat() * 0.4f, now)
        }
    }

    fun overhead(text: String) {
        overhead = Overhead(text, SystemClock.uptimeMillis())
        invalidate()
    }

    fun emote(e: Emote) {
        emote = e
        emoteStart = SystemClock.uptimeMillis()
        if (e == Emote.CAST) castSparkles()
        invalidate()
    }

    // ------------------------------------------------------------------ input

    private fun tap(x: Float, y: Float) {
        for (i in 0..3) {
            val p = orbPos[i]
            if (hypot(x - p.x, y - p.y) < orbR * 1.3f || (abs(y - p.y) < orbR && x < p.x && x > p.x - orbR * 2.8f)) {
                game.haptic()
                game.orbTapped(i)
                return
            }
        }
        if (hypot(x - worldPos.x, y - worldPos.y) < orbR * 1.2f) {
            game.haptic()
            game.actions.maps()
            return
        }
        if (hypot(x - compassPos.x, y - compassPos.y) < orbR * 1.2f) {
            game.haptic()
            game.worldHop()
            return
        }
        if (hypot(x - mmCx, y - mmCy) < mmR) {
            flag = PointF(x, y)
            flagUntil = SystemClock.uptimeMillis() + 2500
            invalidate()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (chest.contains(x, y)) {
            cross = Cross(x, y, true, now)
            game.haptic()
            game.ui.openBank(false, null)
            return
        }
        if (game.save.showPlayer && playerBox.contains(x, y)) {
            cross = Cross(x, y, true, now)
            game.playerClicked()
            return
        }
        if (clockBox.contains(x, y)) {
            cross = Cross(x, y, true, now)
            game.actions.alarms()
            return
        }
        if (dateBox.contains(x, y)) {
            cross = Cross(x, y, true, now)
            game.actions.calendar()
            return
        }
        cross = Cross(x, y, false, now)
        if (game.useSlot >= 0) {
            game.useSlot = -1
            game.ui.invalidateAll()
        }
        invalidate()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        c.drawRect(0f, 0f, width.toFloat(), dateY + Rs.dp(40f), scrim)
        drawClock(c)
        drawMinimap(c, now)
        drawOrbs(c)
        Sprites.CHEST.draw(c, chest.centerX(), chest.centerY(), chest.width())
        if (game.save.showPlayer) drawPlayer(c, now)
        drawDrops(c, now)
        drawSparks(c, now)
        drawCross(c, now)
        val animating = emote != null || drops.isNotEmpty() || sparks.isNotEmpty() || cross != null ||
            overhead != null || flag != null
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawClock(c: Canvas) {
        val x = Rs.dp(14f)
        Rs.drawRich(c, Rs.rich(game.hoverText(), Rs.WHITE), x, hoverY, hoverPaint)
        val now = Date()
        val time = DateFormat.format(if (game.save.clock24) "HH:mm" else "h:mm", now).toString()
        Rs.text(c, time, x, clockY, clockPaint, Rs.YELLOW)
        if (!game.save.clock24) {
            val ampm = DateFormat.format("a", now).toString().uppercase()
            Rs.text(c, ampm, x + clockPaint.measureText(time) + Rs.dp(6f), clockY, ampmPaint, Rs.YELLOW)
        }
        Rs.text(c, DateFormat.format("EEEE, d MMMM", now).toString(), x, dateY, datePaint, Rs.ORANGE)
    }

    private fun drawMinimap(c: Canvas, now: Long) {
        val bmp = map ?: return
        aa.color = Rs.BLACK
        c.drawCircle(mmCx, mmCy, mmR + Rs.dp(6f), aa)
        c.drawCircle(mmCx, mmCy, mmR + Rs.dp(5f), Rs.stoneLightPaint)
        aa.color = Rs.EDGE_LO
        c.drawCircle(mmCx, mmCy, mmR + Rs.px, aa)
        c.save()
        clip.reset()
        clip.addCircle(mmCx, mmCy, mmR, Path.Direction.CW)
        c.clipPath(clip)
        tmp.set(mmCx - mmR, mmCy - mmR, mmCx + mmR, mmCy + mmR)
        c.drawBitmap(bmp, null, tmp, Sprites.paint)
        val cell = mmR * 2f / MAP
        fun dot(mx: Int, my: Int, color: Int, size: Float) {
            val x = mmCx - mmR + mx * cell
            val y = mmCy - mmR + my * cell
            Rs.rect(c, x + Rs.px, y + Rs.px, x + size + Rs.px, y + size + Rs.px, Rs.BLACK)
            Rs.rect(c, x, y, x + size, y + size, color)
        }
        for (it in items) dot(it[0], it[1], Rs.RED, cell * 1.3f)
        for (n in npcs) dot(n[0], n[1], Rs.YELLOW, cell * 1.3f)
        val ps = cell * 1.6f
        Rs.rect(c, mmCx - ps / 2, mmCy - ps / 2, mmCx + ps / 2, mmCy + ps / 2, Rs.WHITE)
        val f = flag
        if (f != null) {
            if (now > flagUntil) {
                flag = null
            } else {
                Rs.rect(c, f.x, f.y - Rs.dp(12f), f.x + Rs.dp(1.5f), f.y, Rs.BLACK)
                Rs.rect(c, f.x + Rs.dp(1.5f), f.y - Rs.dp(12f), f.x + Rs.dp(8f), f.y - Rs.dp(7f), Rs.RED)
            }
        }
        c.restore()
        drawCompass(c)
        drawWorldOrb(c)
    }

    private fun drawCompass(c: Canvas) {
        val r = orbR * 0.85f
        val p = compassPos
        aa.color = Rs.BLACK
        c.drawCircle(p.x, p.y, r + Rs.px, aa)
        c.drawCircle(p.x, p.y, r, Rs.stoneLightPaint)
        aa.color = 0xFF2A261F.toInt()
        c.drawCircle(p.x, p.y, r * 0.78f, aa)
        path.reset()
        path.moveTo(p.x, p.y - r * 0.7f)
        path.lineTo(p.x + r * 0.22f, p.y)
        path.lineTo(p.x - r * 0.22f, p.y)
        path.close()
        aa.color = Rs.RED
        c.drawPath(path, aa)
        path.reset()
        path.moveTo(p.x, p.y + r * 0.7f)
        path.lineTo(p.x + r * 0.22f, p.y)
        path.lineTo(p.x - r * 0.22f, p.y)
        path.close()
        aa.color = 0xFFD0D0D0.toInt()
        c.drawPath(path, aa)
        Rs.text(c, "N", p.x, p.y - r * 1.15f, compassPaint, Rs.WHITE)
    }

    private fun drawWorldOrb(c: Canvas) {
        val r = orbR * 0.85f
        val p = worldPos
        aa.color = Rs.BLACK
        c.drawCircle(p.x, p.y, r + Rs.px, aa)
        c.drawCircle(p.x, p.y, r, Rs.stoneLightPaint)
        aa.color = 0xFF2B5DA8.toInt()
        c.drawCircle(p.x, p.y, r * 0.74f, aa)
        aa.color = 0xFF3C9A3C.toInt()
        c.drawCircle(p.x - r * 0.2f, p.y - r * 0.15f, r * 0.3f, aa)
        c.drawCircle(p.x + r * 0.28f, p.y + r * 0.25f, r * 0.2f, aa)
        aa.color = 0x40FFFFFF
        c.drawCircle(p.x - r * 0.25f, p.y - r * 0.3f, r * 0.25f, aa)
    }

    private fun drawOrbs(c: Canvas) {
        val s = game.stats
        val prayerOn = game.actions.torchOn
        drawOrb(c, orbPos[0], s.battery / 100f, 0xFFB0140F.toInt(), Sprites.HITPOINTS, "${s.battery}", false)
        drawOrb(c, orbPos[1], s.storageFreePct / 100f, 0xFF2F9BB5.toInt(), Sprites.PRAYER, "${s.storageFreePct}", prayerOn)
        drawOrb(c, orbPos[2], s.ramFreePct / 100f, 0xFFC9A227.toInt(), Sprites.BOOT, "${s.ramFreePct}", s.charging)
        drawOrb(c, orbPos[3], s.volumePct / 100f, 0xFF4AA7D4.toInt(), Sprites.ATTACK, "${s.volumePct}", false)
    }

    private fun drawOrb(c: Canvas, p: PointF, frac: Float, color: Int, sprite: Sprite, value: String, active: Boolean) {
        val r = orbR
        val pillH = r * 1.0f
        tmp.set(p.x - r * 2.75f, p.y - pillH / 2f, p.x - r * 0.3f, p.y + pillH / 2f)
        Rs.rect(c, tmp, 0xFF1C1813.toInt())
        Rs.outline(c, tmp, Rs.BLACK)
        Rs.text(c, value, (tmp.left + p.x - r) / 2f + Rs.px, Rs.baseline(orbPaint, tmp.top, tmp.bottom), orbPaint, Rs.pctColor(frac))
        aa.color = Rs.BLACK
        c.drawCircle(p.x, p.y, r + Rs.px, aa)
        c.drawCircle(p.x, p.y, r, Rs.stoneLightPaint)
        aa.color = 0xFF141414.toInt()
        c.drawCircle(p.x, p.y, r * 0.8f, aa)
        c.save()
        clip.reset()
        clip.addCircle(p.x, p.y, r * 0.74f, Path.Direction.CW)
        c.clipPath(clip)
        flat.color = Rs.shade(color, -90)
        c.drawRect(p.x - r, p.y - r, p.x + r, p.y + r, flat)
        flat.color = color
        val top = p.y + r * 0.74f - frac.coerceIn(0f, 1f) * r * 1.48f
        c.drawRect(p.x - r, top, p.x + r, p.y + r, flat)
        aa.color = 0x38FFFFFF
        c.drawCircle(p.x - r * 0.25f, p.y - r * 0.3f, r * 0.3f, aa)
        c.restore()
        if (active) {
            stroke.color = 0xCCFFF2A0.toInt()
            stroke.strokeWidth = Rs.dp(2f)
            c.drawCircle(p.x, p.y, r * 0.84f, stroke)
        }
        sprite.draw(c, p.x, p.y, r * 1.0f)
    }

    private fun drawPlayer(c: Canvas, now: Long) {
        aa.color = 0x55000000
        tmp.set(footX - 7 * scale, footY - 1.6f * scale, footX + 7 * scale, footY + 1.6f * scale)
        c.drawOval(tmp, aa)
        if (game.save.hasPet) {
            tmp.set(footX + 10 * scale, footY - 0.8f * scale, footX + 17 * scale, footY + 0.8f * scale)
            c.drawOval(tmp, aa)
            Sprites.PET.draw(c, footX + 13.5f * scale, footY - 4.2f * scale, 9 * scale)
        }
        val e = emote
        var t = 0f
        if (e != null) {
            t = (now - emoteStart) / 1000f
            if (t > e.seconds) emote = null
        }
        val pose = avatar.pose(emote, t, breathe)
        avatar.draw(c, footX, footY, scale, pose)
        val o = overhead
        if (o != null) {
            if (!o.alive(now)) {
                overhead = null
            } else {
                o.draw(c, footX, footY - (36 + pose.lift) * scale - Rs.dp(4f), overheadPaint, now, width * 0.9f)
            }
        }
    }

    private fun drawDrops(c: Canvas, now: Long) {
        val it = drops.iterator()
        val baseX = mmCx - mmR - orbR * 3.2f - Rs.dp(54f)
        val startY = mmCy + Rs.dp(8f)
        val endY = topInset + Rs.dp(26f)
        while (it.hasNext()) {
            val d = it.next()
            val u = (now - d.start) / 1500f
            if (u >= 1f) {
                it.remove()
                continue
            }
            val y = startY + (endY - startY) * u + d.row * Rs.dp(20f)
            val alpha = if (u > 0.75f) ((1f - u) / 0.25f * 255).toInt() else 255
            dropPaint.alpha = alpha
            val iconSize = Rs.dp(15f)
            Sprites.skill(d.skill).draw(c, baseX + iconSize / 2f, y - iconSize / 3f, iconSize)
            val text = Rs.number(d.amount.toLong())
            val o = Rs.shadowOf(dropPaint)
            dropPaint.color = Rs.withAlpha(Rs.BLACK, alpha)
            c.drawText(text, baseX + iconSize + Rs.dp(4f) + o, y + o, dropPaint)
            dropPaint.color = Rs.withAlpha(Rs.WHITE, alpha)
            c.drawText(text, baseX + iconSize + Rs.dp(4f), y, dropPaint)
        }
        dropPaint.alpha = 255
    }

    private fun drawSparks(c: Canvas, now: Long) {
        val it = sparks.iterator()
        val g = Rs.dp(160f)
        val size = max(Rs.px * 1.5f, Rs.dp(2.5f))
        while (it.hasNext()) {
            val s = it.next()
            val t = (now - s.start) / 1000f
            if (t > s.life) {
                it.remove()
                continue
            }
            val x = s.x + s.vx * t
            val y = s.y + s.vy * t + 0.5f * g * t * t
            flat.color = Rs.withAlpha(s.color, ((1f - t / s.life) * 255).toInt())
            c.drawRect(x, y, x + size, y + size, flat)
        }
    }

    private fun drawCross(c: Canvas, now: Long) {
        val cr = cross ?: return
        val u = (now - cr.start) / 400f
        if (u >= 1f) {
            cross = null
            return
        }
        val frame = (u * 4).toInt()
        val r = Rs.dp(8f) * (1f - frame * 0.2f)
        stroke.strokeWidth = Rs.dp(4f)
        stroke.color = Rs.BLACK
        c.drawLine(cr.x - r, cr.y - r, cr.x + r, cr.y + r, stroke)
        c.drawLine(cr.x - r, cr.y + r, cr.x + r, cr.y - r, stroke)
        stroke.strokeWidth = Rs.dp(2f)
        stroke.color = if (cr.red) Rs.RED else Rs.YELLOW
        c.drawLine(cr.x - r, cr.y - r, cr.x + r, cr.y + r, stroke)
        c.drawLine(cr.x - r, cr.y + r, cr.x + r, cr.y - r, stroke)
    }

    companion object {
        const val MAP = 64
    }
}
