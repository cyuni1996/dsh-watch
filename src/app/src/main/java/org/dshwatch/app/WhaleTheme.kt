package org.dshwatch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Static, screen-sized art shared by all panels; never decoded on each poll. */
object WhaleTheme {
    const val INK = 0xFFF1F6FF.toInt()
    const val MUTED = 0xFFBED5F2.toInt()
    const val ACCENT = 0xFFA4DEFF.toInt()
    const val SURFACE = 0xE8162B55.toInt()
    const val RUNNING = 0xFF9FF0DB.toInt()
    const val WAITING = 0xFFFFD69E.toInt()
    const val UNREAD = 0xFFA4DEFF.toInt()
    const val GOLD = 0xFFF0BD72.toInt()
    const val GOLD_INK = 0xFF102546.toInt()
    const val SAPPHIRE = 0xFF8ACDFF.toInt()
    const val STITCH = 0x6EC69A59
    const val SELECTED = 0xEC254783.toInt()
    const val RIPPLE = 0x55A4DEFF
    const val ALERT = 0xFFFFA8A2.toInt()
    private val bitmaps = mutableMapOf<Int, Bitmap>()
    fun bitmap(context: Context, id: Int): Bitmap = bitmaps.getOrPut(id) {
        BitmapFactory.decodeResource(context.resources, id, BitmapFactory.Options().apply { inScaled = false })
    }
    fun backdrop(context: Context, panel: Boolean = false): Drawable {
        val prefs=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
        val image = if (prefs.getBoolean("ui.quietBackground", false)) null else
            bitmap(context, if (panel) R.drawable.whale_tide_panel else R.drawable.whale_tide_chat)
        return DialBackdrop(context.resources.configuration.isScreenRound, panel, prefs.getBoolean("ui.deepBackground", false), image)
    }
}

/** Full-bleed ocean illustration with a quiet center and a stitched bezel. */
internal class DialBackdrop(
    private val round: Boolean,
    private val panel: Boolean,
    private val deep: Boolean,
    private val image: Bitmap? = null,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var wash: Shader? = null
    private val minorTicks = FloatArray(48 * 4)
    private val majorTicks = FloatArray(8 * 4)
    private val quarterTicks = FloatArray(4 * 4)

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        if (bounds.isEmpty) return
        image?.let {
            val scale = maxOf(bounds.width().toFloat() / it.width, bounds.height().toFloat() / it.height)
            val halfWidth = scale * it.width / 2f
            val halfHeight = scale * it.height / 2f
            destination.set(bounds.exactCenterX() - halfWidth, bounds.exactCenterY() - halfHeight,
                bounds.exactCenterX() + halfWidth, bounds.exactCenterY() + halfHeight)
        }
        val colors = if (image == null) {
            intArrayOf(0xFF142C57.toInt(), 0xFF09162E.toInt(), 0xFF152D54.toInt())
        } else {
            intArrayOf(0xAB08152C.toInt(), when {
                deep -> 0x9908152C.toInt()
                panel -> 0x5908152C
                else -> 0x1408152C
            }, 0xC208152C.toInt())
        }
        wash = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            colors, floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        if (round) layoutTicks()
    }

    private fun layoutTicks() {
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        val rim = radius * .961f
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        var minor = 0
        var major = 0
        var quarter = 0
        for (tick in 0 until 60) {
            val long = tick % 5 == 0
            val (lines, index) = when {
                tick % 15 == 0 -> quarterTicks to quarter++
                long -> majorTicks to major++
                else -> minorTicks to minor++
            }
            val angle = tick * Math.PI / 30.0
            val x = kotlin.math.sin(angle).toFloat()
            val y = -kotlin.math.cos(angle).toFloat()
            val inner = rim - radius * (if (long) .039f else .018f)
            lines[index * 4] = centerX + x * rim
            lines[index * 4 + 1] = centerY + y * rim
            lines[index * 4 + 2] = centerX + x * inner
            lines[index * 4 + 3] = centerY + y * inner
        }
    }

    override fun draw(canvas: Canvas) {
        paint.color = 0xFF09152F.toInt()
        if (image == null || image.hasAlpha()) canvas.drawRect(bounds, paint)
        image?.let { canvas.drawBitmap(it, null, destination, paint) }
        paint.shader = wash
        canvas.drawRect(bounds, paint)
        paint.shader = null
        if (!round) return
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        paint.color = 0x4D6EC5FF
        paint.strokeWidth = radius * .005f
        canvas.drawLines(minorTicks, paint)
        paint.strokeWidth = radius * .009f
        paint.color = 0xA16EC5FF.toInt()
        canvas.drawLines(majorTicks, paint)
        paint.color = WhaleTheme.GOLD
        canvas.drawLines(quarterTicks, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}
