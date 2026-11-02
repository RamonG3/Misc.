package com.ramong.cyberlauncher.ui

import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.hypot

/**
 * How fast the finger was moving when it let go, in px per second.
 *
 * Built on the platform VelocityTracker, which forgets everything when two touch events arrive
 * more than 40 ms apart and then reports zero, as if the finger had stopped. Slow or emulated
 * devices space events out like that in the middle of a perfectly good swipe. So when the
 * tracker says "still", this falls back to the distance covered in the last [WINDOW_MS].
 * A finger that really did stop has no samples in that window and still reads as zero.
 */
class Swipe {
    private var tracker: VelocityTracker? = null
    private val times = LongArray(SIZE)
    private val xs = FloatArray(SIZE)
    private val ys = FloatArray(SIZE)
    private var count = 0

    var vx = 0f
        private set
    var vy = 0f
        private set

    fun down(e: MotionEvent) {
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
        count = 0
        vx = 0f
        vy = 0f
        move(e)
    }

    fun move(e: MotionEvent) {
        tracker?.addMovement(e)
        for (i in 0 until e.historySize) add(e.getHistoricalEventTime(i), e.getHistoricalX(i), e.getHistoricalY(i))
        add(e.eventTime, e.x, e.y)
    }

    /** On ACTION_UP: works out [vx] and [vy]. Speeds under [still] count as no reading. */
    fun up(e: MotionEvent, still: Float) {
        move(e)
        tracker?.let {
            it.computeCurrentVelocity(1000)
            vx = it.xVelocity
            vy = it.yVelocity
        }
        if (hypot(vx, vy) < still) fromWindow()
        clear()
    }

    fun clear() {
        tracker?.recycle()
        tracker = null
    }

    /** Records a sample without a MotionEvent; for tests. */
    fun add(time: Long, x: Float, y: Float) {
        val i = count % SIZE
        times[i] = time
        xs[i] = x
        ys[i] = y
        count++
    }

    /** Average speed from the oldest sample in the last [WINDOW_MS] to the newest one. */
    fun fromWindow() {
        if (count < 2) return
        val newest = (count - 1) % SIZE
        val now = times[newest]
        var oldest = newest
        val kept = minOf(count, SIZE)
        for (k in 1 until kept) {
            val i = (count - 1 - k) % SIZE
            if (now - times[i] > WINDOW_MS) break
            oldest = i
        }
        val dt = now - times[oldest]
        if (oldest == newest || dt < MIN_SPAN_MS) return
        vx = (xs[newest] - xs[oldest]) * 1000f / dt
        vy = (ys[newest] - ys[oldest]) * 1000f / dt
    }

    companion object {
        private const val SIZE = 64
        const val WINDOW_MS = 200L
        private const val MIN_SPAN_MS = 8L
    }
}
