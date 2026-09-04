package com.shahmat.game.ui.gameend

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Looping fireworks: rockets rise and explode into radial colored sparks
 * that fade and fall under gravity.
 *
 * The animation is driven by a single long-running [ValueAnimator]; per-frame
 * motion and spawn timing use elapsed (delta) time via [SystemClock], so it
 * stays correct regardless of device frame rate. Paints and particle lists are
 * reused across frames (no per-frame / per-explosion allocations for them).
 */
class FireworksView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Rock(val x: Float, val y: Float, val speed: Float, val hue: Float)
    private class Spark(
        val x: Float, val y: Float, val vx: Float, val vy: Float,
        val life: Float, val maxLife: Float, val color: Int, val size: Float
    )

    // Nominal frame duration used to scale physics so existing speeds and trail
    // sizes stay visually identical at 60 fps.
    private val FRAME_MS = 16.6667f

    private val rand = Random.Default

    // Reused paints (created once, never per explosion / per frame).
    private val rockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rocks = mutableListOf<Rock>()
    private val sparks = mutableListOf<Spark>()

    private var animator: ValueAnimator? = null
    private var running = false

    // Delta-time bookkeeping.
    private var lastFrameMs = 0L
    private var spawnAccumulatorMs = 0f

    private val SPAWN_INTERVAL_MS = 250f

    fun startAnimation() {
        if (running) return
        running = true
        spawnAccumulatorMs = 0f
        lastFrameMs = SystemClock.uptimeMillis()

        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 600
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { invalidate() }
        a.start()
        animator = a
    }

    fun stopAnimation() {
        running = false
        animator?.cancel()
        animator = null
        rocks.clear()
        sparks.clear()
        lastFrameMs = 0L
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        val now = SystemClock.uptimeMillis()
        if (lastFrameMs == 0L) lastFrameMs = now
        var dt = (now - lastFrameMs).toFloat()
        lastFrameMs = now
        // Clamp to avoid a physics jump after a long pause (e.g. window hidden).
        if (dt > 100f) dt = FRAME_MS
        if (dt < 0f) dt = FRAME_MS
        val dtS = dt / FRAME_MS

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) {
            invalidate() // keep waiting for a non-zero size while running
            return
        }

        // Spawn a rocket on elapsed time (delta), not frame counts.
        spawnAccumulatorMs += dt
        if (spawnAccumulatorMs >= SPAWN_INTERVAL_MS && rocks.size < 4) {
            spawnAccumulatorMs -= SPAWN_INTERVAL_MS
            rocks.add(
                Rock(
                    rand.nextFloat() * w * 0.8f + w * 0.1f,
                    h + 20f,
                    h * (0.30f + rand.nextFloat() * 0.22f),
                    rand.nextFloat() * 360f
                )
            )
        }

        // Advance rockets, exploding those that reached the burst height.
        // In-place filtering (write index) avoids allocating lists per frame.
        var rw = 0
        rockPaint.color = Color.argb(230, 255, 220, 140)
        for (i in 0 until rocks.size) {
            val r = rocks[i]
            val ny = r.y - 0.02f * r.speed * dtS
            if (ny < h * 0.12f) {
                explode(canvas, r.x, ny, r.hue)
                continue
            }
            rocks[rw] = Rock(r.x, ny, r.speed, r.hue)
            canvas.drawCircle(r.x, ny, 3.5f, rockPaint)
            rw++
        }
        trimTail(rocks, rw)

        // Advance sparks, removing dead ones in place (no allocation per frame).
        var sw = 0
        for (i in 0 until sparks.size) {
            val s = sparks[i]
            if (s.life <= 0f) continue
            val nx = s.x + s.vx * 0.02f * dtS
            val ny = s.y + s.vy * 0.02f * dtS + 8f * dtS // gravity
            val nvy = s.vy + 30f * dtS
            val nlife = s.life - dtS
            sparks[sw] = Spark(nx, ny, s.vx, nvy, nlife, s.maxLife, s.color, s.size)
            val alpha = ((nlife / s.maxLife) * 255f).toInt().coerceIn(0, 255)
            sparkPaint.color = (alpha shl 24) or (s.color and 0xffffff)
            sparkPaint.strokeWidth = s.size
            canvas.drawLine(s.x, s.y, nx, ny, sparkPaint)
            sw++
        }
        trimTail(sparks, sw)

        invalidate()
    }

    private fun <T> trimTail(list: MutableList<T>, validSize: Int) {
        while (list.size > validSize) list.removeAt(list.size - 1)
    }

    private fun explode(canvas: Canvas, x: Float, y: Float, baseHue: Float) {
        // Glow flash: reuse a single Paint, only its positioned shader is refreshed
        // (a Shader per explosion is fine; a Paint is never recreated).
        glowPaint.shader = RadialGradient(
            x, y, 34f,
            Color.argb(140, 255, 240, 180),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, 34f, glowPaint)

        val n = 48
        for (i in 0 until n) {
            val angle = (i.toFloat() / n) * 2f * Math.PI.toFloat()
            val speed = 70f + rand.nextFloat() * 130f
            val hue = baseHue + rand.nextFloat() * 70f
            val color = Color.HSVToColor(floatArrayOf(hue % 360f, 0.95f, 1f))
            val life = 40f + rand.nextFloat() * 28f
            sparks.add(
                Spark(
                    x, y,
                    cos(angle.toDouble()).toFloat() * speed,
                    sin(angle.toDouble()).toFloat() * speed,
                    life, life, color,
                    2.2f + rand.nextFloat() * 3f
                )
            )
        }
    }
}
