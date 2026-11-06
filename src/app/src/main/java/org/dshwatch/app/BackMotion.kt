package org.dshwatch.app

import android.animation.ValueAnimator
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.sqrt

/** One navigation transaction shared by touch, header Back and dialog Back. */
class BackMotion(
    private val target: () -> View,
    private val preview: () -> Unit,
    private val cleanup: () -> Unit,
    private val commit: () -> Unit,
    private val settled: () -> Unit,
    private val motionEnabled: () -> Boolean = { true },
) {
    var busy = false
        private set
    var settling = false
        private set
    private var active: View? = null
    private var dragOrigin = 0f
    private var startOffset = 0f
    private var layerBeforeDrag: Int? = null
    private val easing = DecelerateInterpolator(1.5f)

    fun enabled(view: View) = motionEnabled() && ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    fun drag(distance: Float) {
        if (settling) return
        if (!busy) {
            val view = target()
            busy = true
            active = view
            dragOrigin = distance
            view.animate().withEndAction(null).cancel()
            startOffset = view.translationX
            if (view.isHardwareAccelerated && view.layerType == View.LAYER_TYPE_NONE && enabled(view)) {
                layerBeforeDrag = view.layerType
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            preview()
        }
        active?.let { view ->
            val width = view.width.toFloat()
            view.translationX = (startOffset + distance - dragOrigin).coerceIn(-width, width)
        }
    }

    fun finish(goBack: Boolean, direction: Float = 1f) {
        if (settling) return
        if (!busy) drag(0f)
        val view = active ?: return
        settling = true
        fun end() {
            if (!settling || active !== view) return
            active = null
            busy = false
            settling = false
            try {
                if (goBack) commit() else view.translationX = 0f
            } finally {
                restoreLayer(view)
                cleanup()
            }
            settled()
        }
        val width = view.width.toFloat()
        val destination = if (goBack) (if (direction < 0f) -width else width) else 0f
        val travel = abs(destination - view.translationX)
        if (!enabled(view) || travel < 1f || width <= 0f) {
            end()
            return
        }
        val fraction = (travel / width).coerceAtMost(1f)
        val duration = if (goBack) (220 * sqrt(fraction)).toLong().coerceAtLeast(100L)
            else (100 + 100 * sqrt(fraction)).toLong()
        view.animate().translationX(destination).setDuration(duration).setInterpolator(easing)
            .withEndAction { end() }.start()
    }

    fun back() {
        if (!busy) finish(true)
    }

    private fun restoreLayer(view: View) {
        layerBeforeDrag?.let { view.setLayerType(it, null) }
        layerBeforeDrag = null
    }

    fun cancel() {
        active?.let { view ->
            view.animate().withEndAction(null).cancel()
            view.translationX = 0f
            restoreLayer(view)
        }
        active = null
        busy = false
        settling = false
        cleanup()
    }
}
