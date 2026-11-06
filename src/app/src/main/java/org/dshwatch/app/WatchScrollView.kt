package org.dshwatch.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.abs

/** Free scrolling with transient feedback. No snap, stretch, background timer or semantic selection. */
class WatchScrollView(context:Context):ScrollView(context) {
    var feedback:ScrollFeedback?=null
    var reading=false
    private var touching=false
    private var moving=false
    val interacting get()=touching || moving
    private var downY=0f
    private var lastY=0
    private var focus:View?=null
    private var pendingCenter:View?=null
    private var lastTick=-90L
    private var lastBoundary=-450L
    private var crownUntil=0L
    private var rotaryRemainder=0f
    private val density=resources.displayMetrics.density
    private val idle=Runnable { moving=false;feedback?.invalidate() }
    private fun haptic(boundary:Boolean) {
        val now=SystemClock.uptimeMillis()
        if(boundary) { if(now-lastBoundary<450)return;lastBoundary=now;lastTick=now }
        else { if(now-lastTick<90)return;lastTick=now }
        val prefs=context.getSharedPreferences("connection",0)
        if(!isHapticFeedbackEnabled || !prefs.getBoolean("ui.haptics",true) || Settings.System.getInt(context.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,1)==0)return
        val vibrator=context.getSystemService(Vibrator::class.java)
        if(vibrator?.hasVibrator()==true)vibrator.vibrate(VibrationEffect.createOneShot(if(boundary)20 else 8,if(vibrator.hasAmplitudeControl()) { if(boundary)96 else 42 } else VibrationEffect.DEFAULT_AMPLITUDE))
    }
    fun range()=(getChildAt(0)?.height ?: 0).let { (it+paddingTop+paddingBottom-height).coerceAtLeast(0) }
    fun scrollToEnd()=scrollTo(0,range())
    fun selectionFeedback()=haptic(false)
    fun centerOnLayout(row:View) { pendingCenter=row;requestLayout() }
    fun boundary(top:Boolean) { if(range()>0) { haptic(true);feedback?.boundary(top) } }
    fun rotary(axis:Float) {
        if(!axis.isFinite())return
        val movement=(-axis*19.2f*density).coerceIn(-54*density,54*density)
        if(movement==0f)return
        val end=range()
        if(end==0 || (movement<0 && scrollY<=0) || (movement>0 && scrollY>=end)) {
            rotaryRemainder=0f
            boundary(movement<0)
            return
        }
        // The hardware encoder can report less than one pixel on each event.
        val total=rotaryRemainder+movement
        val delta=total.toInt()
        rotaryRemainder=total-delta
        if(delta==0)return
        crownUntil=SystemClock.uptimeMillis()+500
        markMoving()
        val target=(scrollY+delta).coerceIn(0,end)
        scrollTo(0,target)
        if(target==0 || target==end)rotaryRemainder=0f
    }
    private fun markMoving() { moving=true;removeCallbacks(idle);postDelayed(idle,180);feedback?.moved(this) }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            downY=event.y
            touching=true
            rotaryRemainder=0f
        }
        val handled=super.dispatchTouchEvent(event)
        if(!handled || event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)touching=false
        return handled
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_MOVE) {
            val dy=event.y-downY
            if(abs(dy)>8*density && ((dy>0 && !canScrollVertically(-1)) || (dy<0 && !canScrollVertically(1))))boundary(dy>0)
        }
        return super.onTouchEvent(event)
    }
    override fun fling(velocityY:Int) {
        markMoving()
        super.fling(velocityY)
    }
    override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int) {
        super.onScrollChanged(l,t,oldl,oldt)
        if(interacting || SystemClock.uptimeMillis()<crownUntil)markMoving()
        if(reading) {
            if(SystemClock.uptimeMillis()<crownUntil && abs(t-lastY)>48*density) { haptic(false);lastY=t }
            return
        }
        updateListEmphasis(t)
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
        super.onLayout(changed,left,top,right,bottom)
        pendingCenter?.let { row ->
            pendingCenter=null
            if(row.parent===getChildAt(0) && !interacting)scrollTo(0,row.top-(height-row.height)/2)
        }
        if(!reading)updateListEmphasis(scrollY)
    }
    private fun updateListEmphasis(offset:Int) {
        val list=getChildAt(0) as? ViewGroup ?: return
        var first:View?=null
        var last:View?=null
        for(i in 0 until list.childCount) {
            val child=list.getChildAt(i)
            if(child.visibility==View.VISIBLE && child.isClickable) {
                if(first==null)first=child
                last=child
            }
        }
        val end=range()
        if(end==0 || first==null || last==null)return
        val firstCenter=first.top+first.height/2f
        val lastCenter=last.top+last.height/2f
        val focusLine=firstCenter+(lastCenter-firstCenter)*(offset.toFloat()/end).coerceIn(0f,1f)
        var nearest:View?=null;var best=Float.MAX_VALUE
        for(i in 0 until list.childCount) {
            val child=list.getChildAt(i)
            val distance=abs(child.top+child.height/2f-focusLine)
            if(child.visibility==View.VISIBLE && child.isClickable && distance<best) { nearest=child;best=distance }
            val amount=(distance/(height/2f).coerceAtLeast(1f)).coerceIn(0f,1f)
            val scale=if(resources.configuration.fontScale>1.2f)1f else 1f-.08f*amount
            child.scaleX=scale;child.scaleY=scale;child.alpha=1f-.2f*amount
        }
        if(nearest!==focus) {
            if(interacting)haptic(false)
            focus?.foreground=null
            nearest?.foreground=android.graphics.drawable.GradientDrawable().apply { setColor(0x12D5BEF7);cornerRadius=14*density }
        }
        focus=nearest
    }
    fun stopFeedback() { removeCallbacks(idle);touching=false;moving=false;crownUntil=0;rotaryRemainder=0f;feedback?.stop() }
    override fun onDetachedFromWindow() { pendingCenter=null;stopFeedback();super.onDetachedFromWindow() }
}

class ScrollFeedback(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=WhaleTheme.ACCENT;strokeWidth=2*resources.displayMetrics.density;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND }
    private var source:WatchScrollView?=null
    private var movingAt=-1000L
    private var boundaryAt=-1000L
    private var top=true
    fun moved(view:WatchScrollView) { source=view;movingAt=SystemClock.uptimeMillis();invalidate() }
    fun boundary(atTop:Boolean) { val now=SystemClock.uptimeMillis();if(now-boundaryAt<450)return;top=atTop;boundaryAt=now;invalidate() }
    fun stop() { source=null;movingAt=-1000;boundaryAt=-1000;invalidate() }
    override fun onDraw(canvas:Canvas) {
        val now=SystemClock.uptimeMillis();val inset=5*resources.displayMetrics.density
        val view=source;val age=now-movingAt
        if(view!=null && view.range()>0 && age<1000) {
            val a=if(age<800)1f else (1000-age)/200f
            val fraction=(view.height.toFloat()/(view.range()+view.height)).coerceIn(.12f,.7f)
            val position=(view.scrollY.toFloat()/view.range()).coerceIn(0f,1f)
            paint.alpha=(120*a).toInt();canvas.drawArc(inset,inset,width-inset,height-inset,-35f+70f*(1-fraction)*position,70f*fraction,false,paint)
        }
        val boundaryAge=now-boundaryAt
        if(boundaryAge<360) { paint.alpha=(230*(1-boundaryAge/360f)).toInt();canvas.drawArc(inset,inset,width-inset,height-inset,if(top)250f else 70f,40f,false,paint) }
        if(age<1000 || boundaryAge<360)postInvalidateOnAnimation()
    }
}
