package org.dshwatch.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/** Brief state cues on the bezel. Each burst settles automatically and leaves the dial static. */
internal class TideMotion(context: Context) : View(context) {
    enum class Cue(val duration: Long, val repeats: Int) {
        WELCOME(1400, 0), REPLY(960, 2), RECORD(800, 2), CONFIRM(1100, 1)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cue: Cue? = null
    private var animator: ValueAnimator? = null
    private var phase = 1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun play(next: Cue?) {
        if (cue == next) return
        cue = next
        animator?.cancel()
        animator = next?.let {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = it.duration
                repeatCount = it.repeats
                interpolator = LinearInterpolator()
                addUpdateListener { step ->
                    phase = step.animatedFraction
                    invalidate()
                }
                start()
            }
        }
        if (next == null) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = cue ?: return
        val unit = minOf(width, height) / 240f
        for (index in 0..2) {
            val travel = ((phase - index * .13f) / .74f).coerceIn(0f, 1f)
            val alpha = (230 * 4 * travel * (1f - travel)).toInt()
            if (alpha == 0) continue
            paint.color = when (active) {
                Cue.CONFIRM -> WhaleTheme.GOLD
                Cue.RECORD -> WhaleTheme.ALERT
                else -> WhaleTheme.SAPPHIRE
            }
            paint.alpha = alpha
            paint.strokeWidth = 1.25f * unit
            paint.style = Paint.Style.STROKE
            when (active) {
                Cue.WELCOME, Cue.REPLY -> {
                    val x = width * (.83f + index * .044f - .035f * travel)
                    val y = height * (.61f - .28f * travel + .014f * index)
                    canvas.drawCircle(x, y, (2f + index + travel * 2f) * unit, paint)
                }
                Cue.RECORD -> {
                    val radius = (13f + 12f * travel + index * 3f) * unit
                    val x = width * .63f
                    val y = height * .74f
                    canvas.drawArc(x - radius, y - radius, x + radius, y + radius, 227f, 75f, false, paint)
                }
                Cue.CONFIRM -> {
                    val x = width * (.38f + index * .12f)
                    val y = height * (.145f - .045f * travel)
                    val radius = (2f + 4f * travel) * unit
                    canvas.drawLine(x - radius, y, x + radius, y, paint)
                    canvas.drawLine(x, y - radius, x, y + radius, paint)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        play(null)
        super.onDetachedFromWindow()
    }
}
