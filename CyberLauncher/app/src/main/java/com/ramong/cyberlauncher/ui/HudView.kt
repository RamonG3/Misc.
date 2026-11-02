package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.ramong.cyberlauncher.core.Banner
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.Rarity
import com.ramong.cyberlauncher.core.Tag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The home screen's top half: Night City behind a 2077-style HUD. */
@SuppressLint("ViewConstructor")
class HudView(context: Context, private val game: Game) : View(context) {
    var topInset = 0
        set(v) {
            field = v
            layoutHud()
            invalidate()
        }

    /** True while something opaque covers the view; stops the animation loop. */
    var covered = false
        set(v) {
            field = v
            if (!v) invalidate()
        }

    private val city = City()
    private var cityKey = ""
    private var seed = (System.currentTimeMillis() / 86_400_000L).toInt()
    private val start = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    private var frameQueued = false
    private val frame = Runnable {
        frameQueued = false
        invalidate()
    }

    private var glitchStart = 0L
    private var glitchLen = 0L
    private var glitchPower = 0f
    private var nextAmbientGlitch = SystemClock.uptimeMillis() + 9_000L

    private val banners = ArrayDeque<Banner>()
    private var bannerStart = 0L

    private class Drop(val text: String, val role: Int, val start: Long, val row: Int)
    private val drops = ArrayList<Drop>()

    // Layout.
    private val levelBox = RectF()
    private val healthBar = RectF()
    private val ramRow = RectF()
    private val credBar = RectF()
    private val statusHit = RectF()
    private val ramHit = RectF()
    private val credHit = RectF()
    private val clockBox = RectF()
    private val dateBox = RectF()
    private val objectiveBox = RectF()
    private var clockY = 0f
    private var dateY = 0f
    private var mapCx = 0f
    private var mapCy = 0f
    private var mapR = 0f
    private var map: Bitmap? = null
    private var mapKey = ""

    private val tiny = Cy.paint(8.5f, Cy.Face.MONO, spacing = 0.08f)
    private val small = Cy.paint(10f, Cy.Face.MONO, spacing = 0.04f)
    private val smallRight = Cy.paint(10f, Cy.Face.MONO, Paint.Align.RIGHT, 0.04f)
    private val levelNum = Cy.paint(21f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val pct = Cy.paint(13f, Cy.Face.BOLD, Paint.Align.RIGHT)
    private val clockPaint = Cy.paint(64f, Cy.Face.BOLD, spacing = 0.02f)
    private val ampmPaint = Cy.paint(18f, Cy.Face.BOLD)
    private val datePaint = Cy.paint(13f, Cy.Face.MONO, spacing = 0.12f)
    private val jobTitle = Cy.paint(14f, Cy.Face.BOLD, Paint.Align.RIGHT, 0.06f)
    private val jobNote = Cy.paint(12.5f, Cy.Face.BODY, Paint.Align.RIGHT)
    private val districtPaint = Cy.paint(8.5f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val northPaint = Cy.paint(9f, Cy.Face.BOLD, Paint.Align.CENTER)
    private val dropPaint = Cy.paint(13f, Cy.Face.BOLD, spacing = 0.06f)
    private val bannerTitle = Cy.paint(27f, Cy.Face.BOLD, Paint.Align.CENTER, 0.08f)
    private val bannerSub = Cy.paint(15f, Cy.Face.BOLD, Paint.Align.CENTER, 0.1f)
    private val bannerExtra = Cy.paint(9.5f, Cy.Face.MONO, Paint.Align.CENTER, 0.1f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scrim = Paint()
    private val vignette = Paint()
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepMatrix = Matrix()
    private val clip = Path()
    private val path = Path()
    private val tmp = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMM", Locale.getDefault())

    // ------------------------------------------------------------------ input

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (hypot(e.x - mapCx, e.y - mapCy) < mapR * 1.15f) {
                game.haptic()
                game.travel()
                return
            }
            game.sceneMenu(left + e.x, top + e.y)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val dy = e2.y - (e1?.y ?: downY)
            if (abs(vy) > abs(vx) && abs(dy) > Cy.dp(40f)) {
                swiped = true
                if (vy < 0) game.ui.openStash(null, null) else game.actions.expandNotifications()
                return true
            }
            return false
        }
    })

    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiped = false
            }
        }
        val handled = gestures.onTouchEvent(event)
        // A slow, deliberate drag counts as a swipe too, not just a quick fling.
        if (event.actionMasked == MotionEvent.ACTION_UP && !swiped) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (abs(dy) > Cy.dp(90f) && abs(dy) > abs(dx) * 1.5f) {
                swiped = true
                if (dy < 0) game.ui.openStash(null, null) else game.actions.expandNotifications()
            }
        }
        return handled
    }

    private fun tap(x: Float, y: Float) {
        val save = game.save
        when {
            save.hasCat && save.scene == 0 && city.catBox.contains(x, y) -> game.catTapped()
            hypot(x - mapCx, y - mapCy) < mapR * 1.1f -> {
                game.haptic()
                if (!game.actions.maps()) game.feed(Tag.ERR, "No map app installed. You're navigating by vibes.")
            }
            ramHit.contains(x, y) -> {
                game.haptic()
                game.ui.selectTab(Game.TAB_HACKS)
            }
            statusHit.contains(x, y) -> {
                game.haptic()
                game.actions.battery()
            }
            credHit.contains(x, y) -> {
                game.haptic()
                game.ui.selectTab(Game.TAB_CHARACTER)
            }
            objectiveBox.contains(x, y) -> {
                game.haptic()
                game.ui.selectTab(Game.TAB_JOURNAL)
            }
            clockBox.contains(x, y) -> {
                game.haptic()
                game.actions.alarms()
            }
            dateBox.contains(x, y) -> {
                game.haptic()
                game.actions.calendar()
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutHud()

    private fun layoutHud() {
        val w = width.toFloat()
        if (w <= 0f) return
        val top = topInset.toFloat()
        val x0 = Cy.dp(14f)
        val y0 = top + Cy.dp(12f)
        levelBox.set(x0, y0, x0 + Cy.dp(38f), y0 + Cy.dp(38f))
        val barX = levelBox.right + Cy.dp(8f)
        mapR = min(Cy.dp(56f), w * 0.15f)
        val barW = min(min(w * 0.42f, Cy.dp(180f)), w - 2 * mapR - Cy.dp(48f) - barX)
        healthBar.set(barX, y0 + Cy.dp(14f), barX + barW, y0 + Cy.dp(23f))
        ramRow.set(barX, healthBar.bottom + Cy.dp(6f), barX + barW, healthBar.bottom + Cy.dp(12f))
        credBar.set(x0, levelBox.bottom + Cy.dp(14f), healthBar.right, levelBox.bottom + Cy.dp(16.5f))
        statusHit.set(0f, top, healthBar.right + Cy.dp(6f), healthBar.bottom + Cy.dp(3f))
        ramHit.set(barX - Cy.dp(4f), healthBar.bottom + Cy.dp(3f), healthBar.right + Cy.dp(6f), ramRow.bottom + Cy.dp(6f))
        credHit.set(0f, levelBox.bottom, healthBar.right + Cy.dp(6f), credBar.bottom + Cy.dp(8f))
        mapCx = w - Cy.dp(16f) - mapR
        mapCy = top + Cy.dp(14f) + mapR
        clockY = credBar.bottom + Cy.dp(62f)
        dateY = clockY + Cy.dp(22f)
        clockBox.set(0f, credBar.bottom + Cy.dp(8f), w * 0.5f, clockY + Cy.dp(6f))
        dateBox.set(0f, clockY + Cy.dp(6f), w * 0.5f, dateY + Cy.dp(8f))
        val maxClockW = min(w * 0.5f, mapCx - mapR - x0) - Cy.dp(8f)
        clockPaint.textSize = Cy.dp(64f)
        val sample = if (game.save.clock24) "88:88" else "88:88PM"
        while (clockPaint.measureText(sample) > maxClockW && clockPaint.textSize > Cy.dp(36f)) clockPaint.textSize -= Cy.dp(2f)
        scrim.shader = LinearGradient(0f, 0f, 0f, dateY + Cy.dp(60f), 0xB0000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        map = null
    }

    private fun ensureCity() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val key = "${width}x$height:${Cy.palette.id}:$seed:${City.phaseOf(hour)}"
        if (key != cityKey && width > 0 && height > 0) {
            cityKey = key
            city.build(width, height, Cy.palette, seed, hour, Cy.density)
        }
        val mk = "${mapR.toInt()}:${Cy.palette.id}:$seed"
        if (map == null || mk != mapKey) {
            mapKey = mk
            map = buildMap()
        }
    }

    private fun buildMap(): Bitmap? {
        val n = (mapR * 2).toInt()
        if (n <= 0) return null
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = Random(seed * 31 + 7)
        c.drawColor(Cy.mix(Cy.bg, 0xFF000000.toInt(), 0.25f))
        fill.color = Cy.mix(Cy.bg, Cy.accent, 0.12f)
        path.reset()
        val side = r.nextInt(4)
        val k = n * (0.18f + r.nextFloat() * 0.2f)
        when (side) {
            0 -> { path.moveTo(0f, 0f); path.lineTo(k * 1.4f, 0f); path.lineTo(k, n.toFloat()); path.lineTo(0f, n.toFloat()) }
            1 -> { path.moveTo(n.toFloat(), 0f); path.lineTo(n - k, 0f); path.lineTo(n - k * 1.3f, n.toFloat()); path.lineTo(n.toFloat(), n.toFloat()) }
            2 -> { path.moveTo(0f, n.toFloat()); path.lineTo(0f, n - k); path.lineTo(n.toFloat(), n - k * 1.5f); path.lineTo(n.toFloat(), n.toFloat()) }
            else -> { path.moveTo(0f, 0f); path.lineTo(n.toFloat(), 0f); path.lineTo(n.toFloat(), k); path.lineTo(0f, k * 1.4f) }
        }
        path.close()
        c.drawPath(path, fill)
        c.save()
        c.rotate(-25f + r.nextFloat() * 50f, n / 2f, n / 2f)
        val block = Cy.dp(9f + r.nextInt(5))
        fill.color = Cy.withAlpha(Cy.mix(Cy.bg, Cy.primary, 0.16f), 0xFF)
        var gy = -n.toFloat()
        while (gy < n * 2f) {
            var gx = -n.toFloat()
            while (gx < n * 2f) {
                if (r.nextInt(5) != 0) c.drawRect(gx + Cy.dp(1.4f), gy + Cy.dp(1.4f), gx + block - Cy.dp(1.4f), gy + block - Cy.dp(1.4f), fill)
                gx += block
            }
            gy += block
        }
        stroke.color = Cy.withAlpha(Cy.primary, 0xC0)
        stroke.strokeWidth = Cy.dp(1.6f)
        var i = 0
        var g = -n.toFloat()
        while (g < n * 2f) {
            if (i % 3 == 1) {
                c.drawLine(g, -n.toFloat(), g, n * 2f, stroke)
                c.drawLine(-n.toFloat(), g, n * 2f, g, stroke)
            }
            g += block
            i++
        }
        c.restore()
        fill.color = Cy.accent
        repeat(3) {
            val x = n * (0.15f + r.nextFloat() * 0.7f)
            val y = n * (0.15f + r.nextFloat() * 0.7f)
            c.drawRect(x - Cy.dp(2.2f), y - Cy.dp(2.2f), x + Cy.dp(2.2f), y + Cy.dp(2.2f), fill)
        }
        fill.color = Cy.DANGER
        repeat(4) {
            c.drawCircle(n * (0.1f + r.nextFloat() * 0.8f), n * (0.1f + r.nextFloat() * 0.8f), Cy.dp(1.5f), fill)
        }
        Cy.diamond(c, n * (0.3f + r.nextFloat() * 0.4f), n * (0.18f + r.nextFloat() * 0.2f), Cy.dp(4f), Cy.hi)
        return b
    }

    // ------------------------------------------------------------------ effects

    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (game.save.glitch && now > nextAmbientGlitch) {
            nextAmbientGlitch = now + 12_000L + Random.nextLong(14_000L)
            glitch(0.25f, 140L)
        }
        invalidate()
    }

    fun glitch(strength: Float, length: Long = (180 + strength * 220).toLong()) {
        glitchStart = SystemClock.uptimeMillis()
        glitchLen = length
        glitchPower = strength
        invalidate()
    }

    fun drop(text: String, role: Int) {
        val now = SystemClock.uptimeMillis()
        drops.removeAll { now - it.start > DROP_MS }
        val row = drops.count { now - it.start < 450 }
        drops += Drop(text, role, now, row)
        invalidate()
    }

    fun banner(b: Banner) {
        if (banners.isEmpty()) bannerStart = SystemClock.uptimeMillis()
        if (banners.size < 6) banners.addLast(b)
        invalidate()
    }

    fun travel() {
        seed = Random.nextInt()
        glitch(0.9f)
    }

    fun themeChanged() {
        cityKey = ""
        map = null
        invalidate()
    }

    private fun glitchAmount(now: Long): Float {
        if (!game.save.glitch && glitchPower < 0.5f) return 0f
        val e = now - glitchStart
        return if (e in 0 until glitchLen) glitchPower * (1f - e.toFloat() / glitchLen) else 0f
    }

    private fun animating(): Boolean = game.resumed && !covered && !game.stats.powerSave && game.save.scene == 0

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        ensureCity()
        val g = glitchAmount(now)
        val w = width.toFloat()
        val h = height.toFloat()
        val save = game.save
        if (save.scene == 0) {
            city.draw(c, now, t, dt, save.rain, save.hasCat, g)
        } else {
            fill.shader = LinearGradient(0f, 0f, 0f, h, 0x66000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }
        c.drawRect(0f, 0f, w, dateY + Cy.dp(60f), scrim)
        drawStatus(c, now, t)
        drawMap(c, t)
        drawObjective(c)
        drawClock(c, g)
        drawDrops(c, now)
        drawBanner(c, now)
        if (g > 0f) drawGlitchBars(c, g)
        drawCritical(c, t)

        val busy = drops.isNotEmpty() || banners.isNotEmpty() || g > 0f
        if ((animating() || busy) && !frameQueued) {
            frameQueued = true
            postDelayed(frame, if (save.rain || busy) FRAME_MS else SLOW_FRAME_MS)
        }
    }

    private fun drawStatus(c: Canvas, now: Long, t: Float) {
        val s = game.stats
        Cy.panel(c, levelBox, Cy.dp(8f), Cy.panel, Cy.primary, Cy.BR, ticks = false)
        Cy.text(c, "LVL", levelBox.left + Cy.dp(5f), levelBox.top + Cy.dp(10f), tiny, Cy.dim)
        Cy.glow(c, "${game.level()}", levelBox.centerX(), levelBox.bottom - Cy.dp(7f), levelNum, Cy.WHITE, Cy.dp(5f))

        val frac = s.battery / 100f
        val critical = s.battery <= 15 && !s.charging
        val col = if (critical && (now / 400) % 2 == 0L) Cy.DANGER else Cy.healthColor(frac)
        val label = when {
            s.charging -> "REGEN"
            critical -> "CRITICAL"
            else -> "HEALTH"
        }
        Cy.text(c, label, healthBar.left, healthBar.top - Cy.dp(4f), tiny, if (s.charging) Cy.accent else if (critical) Cy.DANGER else Cy.dim)
        Cy.text(c, "${s.battery}", healthBar.right - pct.measureText("%") - Cy.dp(1f), healthBar.top - Cy.dp(3f), pct, col)
        Cy.text(c, "%", healthBar.right, healthBar.top - Cy.dp(3f), smallRight, Cy.dim)
        val segs = 20
        Cy.segments(c, healthBar, segs, frac * segs, col, Cy.withAlpha(col, 0x2A))
        if (s.charging) {
            val sweepX = healthBar.left + (t * 0.6f % 1f) * healthBar.width()
            fill.shader = LinearGradient(sweepX - Cy.dp(24f), 0f, sweepX, 0f, 0x00FFFFFF, Cy.withAlpha(Cy.accent, 0x90), Shader.TileMode.CLAMP)
            c.save()
            c.clipRect(healthBar.left, healthBar.top, healthBar.left + healthBar.width() * frac, healthBar.bottom)
            c.drawRect(sweepX - Cy.dp(24f), healthBar.top, sweepX, healthBar.bottom, fill)
            c.restore()
            fill.shader = null
        }

        val cells = game.ramCells()
        val free = game.ramFree()
        val cw = Cy.dp(11f)
        val gap = Cy.dp(3f)
        for (i in 0 until 8) {
            val l = ramRow.left + i * (cw + gap)
            tmp.set(l, ramRow.top, l + cw, ramRow.bottom)
            when {
                i < free.toInt() -> Cy.rect(c, tmp, Cy.accent)
                i < cells -> {
                    stroke.color = Cy.withAlpha(Cy.accent, 0xA0)
                    stroke.strokeWidth = Cy.px
                    c.drawRect(tmp, stroke)
                }
                else -> Cy.rect(c, tmp, Cy.withAlpha(Cy.accent, 0x1C))
            }
        }
        val ramX = ramRow.left + 8 * (cw + gap) + Cy.dp(2f)
        if (ramX + small.measureText("RAM 8/8") < ramRow.right + Cy.dp(36f)) {
            Cy.text(c, "RAM ${free.toInt()}/8", ramX, ramRow.bottom + Cy.dp(1f), small, Cy.accent)
        }

        Cy.text(c, "STREET CRED ${game.credLevel()}", credBar.left, credBar.top - Cy.dp(4f), tiny, Cy.hi)
        Cy.text(c, "€$ ${Game.number(game.save.eddies)}", credBar.right, credBar.top - Cy.dp(3.5f), smallRight, Cy.hi)
        Cy.bar(c, credBar, game.credProgress(), Cy.hi)
    }

    private fun drawMap(c: Canvas, t: Float) {
        val m = map ?: return
        val cx = mapCx
        val cy = mapCy
        val r = mapR
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        bmpPaint.alpha = 235
        c.drawBitmap(m, cx - r, cy - r, bmpPaint)
        if (sweep.shader == null || sweepTint != Cy.accent) {
            sweepTint = Cy.accent
            sweep.shader = SweepGradient(0f, 0f, intArrayOf(0x00000000, 0x00000000, Cy.withAlpha(Cy.accent, 0x50)), floatArrayOf(0f, 0.75f, 1f))
        }
        sweepMatrix.setRotate((t * 50f) % 360f)
        sweepMatrix.postTranslate(cx, cy)
        sweep.shader.setLocalMatrix(sweepMatrix)
        c.drawCircle(cx, cy, r, sweep)
        c.restore()
        stroke.color = Cy.withAlpha(Cy.primary, 0xD0)
        stroke.strokeWidth = Cy.dp(1.3f)
        c.drawCircle(cx, cy, r, stroke)
        stroke.color = Cy.faint
        stroke.strokeWidth = Cy.px
        c.drawCircle(cx, cy, r + Cy.dp(4f), stroke)
        for (i in 0 until 24) {
            val a = Math.toRadians(i * 15.0)
            val long = i % 6 == 0
            val r0 = r + Cy.dp(if (long) 1f else 2.5f)
            val r1 = r + Cy.dp(5.5f)
            stroke.color = if (long) Cy.dim else Cy.faint
            c.drawLine(cx + r0 * Math.sin(a).toFloat(), cy - r0 * Math.cos(a).toFloat(), cx + r1 * Math.sin(a).toFloat(), cy - r1 * Math.cos(a).toFloat(), stroke)
        }
        tmp.set(cx - Cy.dp(7f), cy - r - Cy.dp(7f), cx + Cy.dp(7f), cy - r + Cy.dp(5f))
        Cy.rect(c, tmp, Cy.bg)
        Cy.text(c, "N", cx, tmp.bottom - Cy.dp(2.5f), northPaint, Cy.hi)
        path.reset()
        val a = Cy.dp(7f)
        path.moveTo(cx, cy - a)
        path.lineTo(cx + a * 0.62f, cy + a * 0.7f)
        path.lineTo(cx, cy + a * 0.32f)
        path.lineTo(cx - a * 0.62f, cy + a * 0.7f)
        path.close()
        fill.color = Cy.WHITE
        fill.setShadowLayer(Cy.dp(4f), 0f, 0f, Cy.accent)
        c.drawPath(path, fill)
        fill.clearShadowLayer()

        var y = cy + r + Cy.dp(16f)
        if (game.heat > 0) {
            val n = 5
            val size = Cy.dp(11f)
            val gap = Cy.dp(2f)
            val total = n * size + (n - 1) * gap
            var x = cx - total / 2f + size / 2f
            val flash = (SystemClock.uptimeMillis() / 350) % 2 == 0L
            for (i in 0 until n) {
                val on = i < game.heat
                val col = if (!on) Cy.withAlpha(Cy.MUTED, 0x60) else if ((i % 2 == 0) == flash) Cy.DANGER else 0xFF3F7CFF.toInt()
                Glyphs.draw(c, Glyph.STAR, x, y - Cy.dp(3f), size, col, 1.2f)
                x += size + gap
            }
            y += Cy.dp(12f)
        }
        val d = game.district
        Cy.text(c, "${d.first} · ${d.second}", cx, y, districtPaint, Cy.dim)
        objectiveBox.set(width * 0.5f, y + Cy.dp(6f), width.toFloat(), y + Cy.dp(52f))
    }

    private var sweepTint = 0

    private fun drawObjective(c: Canvas) {
        val job = game.trackedJob() ?: return
        val right = width - Cy.dp(16f)
        val maxW = min(width * 0.46f, Cy.dp(220f))
        var y = objectiveBox.top + Cy.dp(12f)
        val title = Cy.ellipsize(job.title.uppercase(Locale.US), jobTitle, maxW - Cy.dp(14f))
        Cy.glow(c, title, right, y, jobTitle, Cy.hi, Cy.dp(4f))
        Cy.diamond(c, right - jobTitle.measureText(title) - Cy.dp(8f), y - Cy.dp(4.5f), Cy.dp(3.5f), Cy.hi)
        val note = if (job.main) job.note else "Gig. Tap the journal when it's done."
        for (line in Cy.wrap(note, jobNote, maxW).take(2)) {
            y += Cy.dp(15f)
            Cy.text(c, line, right, y, jobNote, Cy.TEXT)
        }
        objectiveBox.bottom = y + Cy.dp(6f)
    }

    private fun drawClock(c: Canvas, g: Float) {
        val cal = Calendar.getInstance()
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val x = Cy.dp(12f)
        val time: String
        var suffix = ""
        if (game.save.clock24) {
            time = String.format(Locale.US, "%02d:%02d", h24, min)
        } else {
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            time = String.format(Locale.US, "%d:%02d", h12, min)
            suffix = if (h24 < 12) "AM" else "PM"
        }
        val split = Cy.dp(1.4f) + g * Cy.dp(8f)
        Cy.split(c, time, x, clockY, clockPaint, Cy.WHITE, split, Cy.dp(10f))
        if (g > 0.15f) {
            c.save()
            val sliceY = clockY - clockPaint.textSize * (0.2f + Random.nextFloat() * 0.4f)
            c.clipRect(0f, sliceY, width * 0.55f, sliceY + Cy.dp(6f))
            Cy.text(c, time, x + g * Cy.dp(14f), clockY, clockPaint, Cy.primary)
            c.restore()
        }
        if (suffix.isNotEmpty()) Cy.text(c, suffix, x + clockPaint.measureText(time) + Cy.dp(4f), clockY, ampmPaint, Cy.primary)
        val year = cal.get(Calendar.YEAR) + if (game.save.realYear) 0 else 51
        val date = "${dayFormat.format(cal.time).uppercase(Locale.getDefault()).take(3)} · " +
            String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH)) + " " +
            monthFormat.format(cal.time).uppercase(Locale.getDefault()).take(3) + " $year"
        Cy.text(c, date, x + Cy.dp(3f), dateY, datePaint, Cy.primary)
    }

    private fun drawDrops(c: Canvas, now: Long) {
        drops.removeAll { now - it.start > DROP_MS }
        val baseY = height - Cy.dp(34f)
        for (d in drops) {
            val e = (now - d.start) / DROP_MS.toFloat()
            val alpha = if (e < 0.65f) 255 else ((1f - (e - 0.65f) / 0.35f) * 255).toInt()
            val y = baseY - d.row * Cy.dp(18f) - e * Cy.dp(30f)
            val col = when (d.role) {
                Game.ROLE_HI -> Cy.hi
                Game.ROLE_ACCENT -> Cy.accent
                else -> Cy.primary
            }
            dropPaint.alpha = alpha
            Cy.diamond(c, Cy.dp(18f), y - Cy.dp(4.5f), Cy.dp(3f), Cy.withAlpha(col, alpha))
            dropPaint.color = Cy.withAlpha(col, alpha)
            dropPaint.setShadowLayer(Cy.dp(4f), 0f, 0f, Cy.withAlpha(col, alpha / 2))
            c.drawText(d.text, Cy.dp(26f), y, dropPaint)
            dropPaint.clearShadowLayer()
        }
    }

    private fun drawBanner(c: Canvas, now: Long) {
        val b = banners.firstOrNull() ?: return
        val e = now - bannerStart
        if (e > BANNER_MS) {
            banners.removeFirst()
            bannerStart = now
            invalidate()
            return
        }
        val inT = (e / 200f).coerceIn(0f, 1f)
        val outT = ((BANNER_MS - e) / 260f).coerceIn(0f, 1f)
        val k = min(inT, outT)
        val w = min(width - Cy.dp(32f), Cy.dp(330f))
        val hgt = Cy.dp(74f)
        val cx = width / 2f
        // Below the clock, over the rooftops, so the time stays readable.
        val cy = max(dateY + hgt / 2f + Cy.dp(16f), height - hgt / 2f - Cy.dp(22f))
        tmp.set(cx - w / 2f * k, cy - hgt / 2f, cx + w / 2f * k, cy + hgt / 2f)
        val accent = when (b.kind) {
            Banner.Kind.ITEM -> Rarity.values().firstOrNull { it.title.equals(b.extra, true) }?.color ?: Cy.hi
            Banner.Kind.ATTR -> Cy.accent
            Banner.Kind.CALL -> Cy.accent
            else -> Cy.hi
        }
        Cy.panel(c, tmp, Cy.dp(14f), Cy.withAlpha(Cy.bg, (0xE6 * k).toInt()), Cy.withAlpha(accent, (0xC0 * k).toInt()), Cy.TR or Cy.BL)
        Cy.line(c, tmp.left - Cy.dp(18f) * k, tmp.top - Cy.dp(5f), tmp.left + w * 0.3f * k, tmp.top - Cy.dp(5f), Cy.withAlpha(accent, (0x90 * k).toInt()), Cy.px)
        Cy.line(c, tmp.right - w * 0.3f * k, tmp.bottom + Cy.dp(5f), tmp.right + Cy.dp(18f) * k, tmp.bottom + Cy.dp(5f), Cy.withAlpha(accent, (0x90 * k).toInt()), Cy.px)
        if (k < 0.99f) return
        val split = if (e < 420) Cy.dp(5f) * (1f - e / 420f) + Cy.dp(1f) else Cy.dp(1f)
        Cy.split(c, b.title, cx, cy - Cy.dp(8f), bannerTitle, accent, split, Cy.dp(8f))
        if (b.sub.isNotEmpty()) Cy.text(c, Cy.ellipsize(b.sub, bannerSub, w - Cy.dp(24f)), cx, cy + Cy.dp(13f), bannerSub, Cy.WHITE)
        if (b.extra.isNotEmpty()) {
            val col = if (b.kind == Banner.Kind.ITEM) accent else Cy.dim
            Cy.text(c, b.extra, cx, cy + Cy.dp(29f), bannerExtra, col)
        }
        if (b.kind == Banner.Kind.CALL && (now / 300) % 2 == 0L) {
            Glyphs.draw(c, Glyph.PHONE, tmp.left + Cy.dp(22f), cy, Cy.dp(22f), accent, glow = true)
        }
    }

    private fun drawGlitchBars(c: Canvas, g: Float) {
        val r = Random(SystemClock.uptimeMillis() / 45)
        repeat((g * 7).toInt() + 1) {
            val y = r.nextFloat() * height
            val bh = Cy.dp(1f + r.nextInt(5))
            val x = r.nextFloat() * width * 0.7f
            val bw = width * (0.1f + r.nextFloat() * 0.5f)
            val col = when (r.nextInt(3)) {
                0 -> Cy.SPLIT_CYAN
                1 -> Cy.SPLIT_RED
                else -> Cy.primary
            }
            Cy.rect(c, x, y, x + bw, y + bh, Cy.withAlpha(col, (0x70 * min(1f, g)).toInt()))
        }
    }

    private fun drawCritical(c: Canvas, t: Float) {
        val s = game.stats
        if (s.battery > 15 || s.charging) return
        val a = ((sin(t * 3f) * 0.5f + 0.5f) * 0x60).toInt() + 0x20
        vignette.shader = RadialGradient(width / 2f, height / 2f, max(width, height) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, Cy.withAlpha(Cy.DANGER, a)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        frameQueued = false
        super.onDetachedFromWindow()
    }

    companion object {
        const val FRAME_MS = 33L
        const val SLOW_FRAME_MS = 66L
        const val DROP_MS = 1600L
        const val BANNER_MS = 2800L
    }
}
