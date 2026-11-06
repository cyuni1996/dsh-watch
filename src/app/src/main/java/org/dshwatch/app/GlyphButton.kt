package org.dshwatch.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Nautical log, satin bow and whale-fluke glyphs with a shared sapphire and gold finish. */
object WhaleGlyphs {
    fun forLabel(label:String):String?=when {
        label.startsWith("自动朗读") || label=="朗读" -> "speaker"
        label.startsWith("字体") -> "type"
        label=="外观与阅读" -> "palette"
        label.contains("连接") -> "link"
        label=="操作与手势" -> "gesture"
        label.contains("余量") -> "rice"
        label.contains("额度") -> "usage"
        label=="设置" -> "settings"
        label.contains("名称") || label=="重命名" -> "edit"
        label.contains("置顶") -> "pin"
        label.contains("归档") || label=="恢复对话" -> "archive"
        label=="在电脑打开" -> "desktop"
        label=="复制" -> "copy"
        label=="引用" || label.contains("输入框") -> "quote"
        label=="重新提问" || label.contains("重新读取") -> "refresh"
        label.startsWith("保存") || label.startsWith("允许") || label.startsWith("提交") -> "check"
        label=="取消" || label.startsWith("拒绝") -> "close"
        label.startsWith("加载") -> "history"
        else -> null
    }
    fun draw(c:Canvas,glyph:String,tint:Int,alpha:Int=255) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=tint;this.alpha=alpha;strokeWidth=1.65f;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
        fun line(x:Float,y:Float,xx:Float,yy:Float)=c.drawLine(x,y,xx,yy,p)
        fun path(vararg points:Float) { val s=Path();s.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)s.lineTo(points[i],points[i+1]);c.drawPath(s,p) }
        val jewel = Paint(p).apply { color = WhaleTheme.GOLD; this.alpha = alpha; style = Paint.Style.FILL }
        when(glyph) {
            "menu" -> {
                path(0f,-5f,-3f,-7f,-8f,-6f,-8f,5f,-3f,4f,0f,7f,3f,4f,8f,5f,8f,-6f,3f,-7f,0f,-5f,0f,7f)
                line(-5f,-2f,-3f,-2.5f); line(3f,-2.5f,5f,-2f)
                c.drawCircle(4f,.8f,1.1f,jewel)
            }
            "more" -> { p.style=Paint.Style.FILL;for(y in listOf(-5f,0f,5f))c.drawCircle(0f,y,1.4f,p) }
            "send" -> {
                val fluke = Path().apply {
                    moveTo(0f,-2f); cubicTo(-3f,-7f,-7f,-8f,-9f,-6f)
                    cubicTo(-8f,-1f,-5f,1f,-2f,1f); quadTo(-2f,5f,0f,7f)
                    quadTo(2f,5f,2f,1f); cubicTo(5f,1f,8f,-1f,9f,-6f)
                    cubicTo(7f,-8f,3f,-7f,0f,-2f); close()
                }
                p.style=Paint.Style.FILL; c.drawPath(fluke,p)
                c.drawCircle(0f,-7.5f,1.1f,jewel)
            }
            "mic" -> {
                c.drawRoundRect(-3f,-8f,3f,2f,3f,3f,p); c.drawArc(-6f,-4f,6f,6f,0f,180f,false,p)
                line(0f,6f,0f,9f); line(-3f,9f,3f,9f)
                c.drawCircle(0f,-4f,1.35f,jewel)
            }
            "stop" -> { p.style=Paint.Style.FILL;c.drawRoundRect(-5f,-5f,5f,5f,1.5f,1.5f,p) }
            "close" -> { line(-5f,-5f,5f,5f);line(5f,-5f,-5f,5f) }
            "new" -> { line(-6f,0f,6f,0f);line(0f,-6f,0f,6f);p.alpha=alpha/2;path(4f,-7f,7f,-7f,7f,-4f) }
            "back" -> path(4f,-6f,-3f,0f,4f,6f)
            "forward" -> path(-3f,-6f,4f,0f,-3f,6f)
            "down" -> path(-5f,-2f,0f,3f,5f,-2f)
            "check" -> path(-6f,0f,-2f,4f,6f,-5f)
            "speaker" -> { path(-7f,-3f,-3f,-3f,1f,-7f,1f,7f,-3f,3f,-7f,3f,-7f,-3f);c.drawArc(0f,-6f,10f,6f,-65f,130f,false,p) }
            "type" -> { path(-7f,6f,-3f,-6f,1f,6f);line(-5f,2f,-1f,2f);c.drawOval(3f,0f,8f,6f,p);line(8f,0f,8f,6f) }
            "link" -> { c.save();c.rotate(-35f);c.drawRoundRect(-7f,-3f,1f,3f,3f,3f,p);c.drawRoundRect(-1f,-3f,7f,3f,3f,3f,p);c.restore() }
            "gesture" -> { path(-6f,5f,-6f,0f,-3f,2f,-3f,-5f);c.drawArc(-3f,-7f,1f,-3f,180f,180f,false,p);path(1f,-5f,1f,-1f,6f,1f,6f,5f,3f,8f,-2f,8f,-6f,5f) }
            "rice" -> {
                val bowl = Path().apply {
                    moveTo(-8f, 0f); quadTo(-6.5f, 6.5f, 0f, 7f)
                    quadTo(6.5f, 6.5f, 8f, 0f); close()
                }
                val rice = Path().apply {
                    moveTo(-7.2f, -.8f); cubicTo(-8f, -3.4f, -5f, -4.2f, -4.1f, -4.1f)
                    cubicTo(-3.5f, -7.5f, .3f, -7.6f, 1.8f, -5.9f)
                    cubicTo(4.7f, -6.8f, 5.6f, -4.6f, 5.7f, -3.7f)
                    quadTo(8.5f, -3f, 7.2f, -.8f); close()
                }
                val fill = Paint(p).apply { style = Paint.Style.FILL; color = WhaleTheme.INK; this.alpha = alpha }
                c.drawPath(rice, fill)
                fill.color = 0xFF3979C4.toInt(); fill.alpha = alpha
                c.drawPath(bowl, fill)
                c.drawPath(bowl, p)
                c.drawLine(-2.7f, 8f, 2.7f, 8f, jewel.apply { strokeWidth = 1.3f })
            }
            "usage" -> { c.drawArc(-7f,-7f,7f,7f,-90f,290f,false,p);path(0f,-4f,0f,0f,4f,2f);line(4f,-8f,7f,-5f) }
            "settings" -> { for(i in 0..5) { val a=i*Math.PI/3;c.drawOval((cos(a)*4-3).toFloat(),(sin(a)*4-3).toFloat(),(cos(a)*4+3).toFloat(),(sin(a)*4+3).toFloat(),p) };c.drawCircle(0f,0f,2f,p) }
            "gear" -> {
                path(-2f,-2f,-7.5f,-5.5f,-8f,5f,-2f,2f); path(2f,-2f,7.5f,-5.5f,8f,5f,2f,2f)
                path(-2f,2f,-5f,8f,-1f,7f); path(2f,2f,5f,8f,1f,7f)
                c.drawOval(-2.4f,-3f,2.4f,3f,jewel)
                c.drawCircle(-.6f,-.9f,.85f,p)
            }
            "edit" -> { path(-6f,6f,-5f,1f,3f,-7f,7f,-3f,-1f,5f,-6f,6f);line(1f,-5f,5f,-1f) }
            "pin" -> { path(-4f,-7f,4f,-7f,3f,-1f,6f,2f,-6f,2f,-3f,-1f,-4f,-7f);line(0f,2f,0f,8f) }
            "archive" -> { c.drawRoundRect(-7f,-6f,7f,-2f,1f,1f,p);path(-6f,-2f,-6f,7f,6f,7f,6f,-2f);line(-2f,2f,2f,2f) }
            "desktop" -> { c.drawRoundRect(-8f,-6f,8f,4f,1.5f,1.5f,p);line(0f,4f,0f,8f);line(-4f,8f,4f,8f) }
            "copy" -> { c.drawRoundRect(-3f,-3f,7f,7f,1.5f,1.5f,p);path(-6f,3f,-7f,3f,-7f,-7f,3f,-7f,3f,-6f) }
            "quote" -> { c.drawRoundRect(-7f,-6f,7f,4f,2f,2f,p);path(-4f,4f,-4f,8f,0f,4f);line(-3f,-1f,3f,-1f) }
            "refresh", "history" -> { c.drawArc(-7f,-7f,7f,7f,35f,295f,false,p);path(3f,-8f,7f,-5f,3f,-3f);if(glyph=="history")path(0f,-4f,0f,0f,3f,2f) }
            "shield" -> { path(0f,-8f,7f,-5f,6f,3f,0f,8f,-6f,3f,-7f,-5f,0f,-8f);path(-3f,0f,-1f,2f,3f,-2f) }
            "folder" -> { path(-8f,6f,-8f,-5f,-2f,-5f,0f,-3f,8f,-3f,8f,6f,-8f,6f);line(-5f,0f,5f,0f) }
            "search" -> { c.drawCircle(-1.5f,-1.5f,5f,p);line(2f,2f,7f,7f) }
            "palette" -> { c.drawOval(-8f,-7f,8f,7f,p);p.style=Paint.Style.FILL;for((x,y) in listOf(-3f to -3f,2f to -3f,4f to 1f,-4f to 2f))c.drawCircle(x,y,1.3f,p) }
        }
    }
}

class GlyphDrawable(private val glyph:String,private val tint:Int):Drawable() {
    override fun draw(canvas:Canvas) { canvas.save();canvas.translate(bounds.exactCenterX(),bounds.exactCenterY());canvas.scale(bounds.width()/20f,bounds.height()/20f);WhaleGlyphs.draw(canvas,glyph,tint);canvas.restore() }
    override fun setAlpha(alpha:Int) {}
    override fun setColorFilter(colorFilter:ColorFilter?) {}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

class GlyphButton(context:Context,var glyph:String,var tint:Int=WhaleTheme.INK):View(context) {
    enum class Tone { SOFT,QUIET,ACCENT }
    var tone=Tone.SOFT
        set(value) { field=value;updateBackground() }
    init {
        isClickable=true;isFocusable=true
        updateBackground()
    }
    private fun updateBackground() {
        val density = resources.displayMetrics.density
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when (tone) {
                Tone.SOFT -> {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(0xE83864A5.toInt(), 0xED101F44.toInt())
                    setStroke(density.toInt().coerceAtLeast(1), WhaleTheme.STITCH)
                }
                Tone.QUIET -> setColor(Color.TRANSPARENT)
                Tone.ACCENT -> {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(0xFFC2ECFF.toInt(), 0xFF65AAEF.toInt())
                    setStroke(density.toInt().coerceAtLeast(1), WhaleTheme.GOLD)
                }
            }
        }
        background=RippleDrawable(ColorStateList.valueOf(WhaleTheme.RIPPLE),surface,GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(Color.WHITE) })
    }
    override fun onDraw(canvas:Canvas) { super.onDraw(canvas);canvas.save();canvas.translate(width/2f,height/2f);val s=resources.displayMetrics.density;canvas.scale(s,s);WhaleGlyphs.draw(canvas,glyph,tint,if(isEnabled)255 else 80);canvas.restore() }
}
