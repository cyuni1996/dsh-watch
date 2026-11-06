package org.dshwatch.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Fixed controls fit inside the circular display; scrolling content can enter its curved edges. */
data class WatchLayout(val width:Int,val height:Int,val density:Float,val round:Boolean) {
    private val scale=(width/density/240f).coerceIn(.8f,1.3f)
    private fun size(dp:Float)=(dp*density*scale).roundToInt()
    val chatButton=maxOf(size(36f),(36*density).roundToInt())
    val top get()=chatTop
    val button get()=chatButton
    val pageSide=size(24f)
    val pageBottom=size(24f)
    val inputHeight=maxOf(size(42f),button)
    val contextHeight=maxOf(size(20f),(20*density).roundToInt())
    val composerGap=size(4f)
    val composerHeight=inputHeight+contextHeight+composerGap
    val bottom=size(22f)
    private val radius=minOf(width,height)/2f-4*density
    fun insetForBand(y1:Int,y2:Int):Int {
        if(!round)return size(20f)
        val dy=maxOf(abs(y1-height/2f),abs(y2-height/2f))
        return ceil(width/2f-sqrt((radius*radius-dy*dy).coerceAtLeast(0f))).toInt().coerceAtLeast(0)
    }
    val headerInset get()=chatHeaderInset
    private val inputBottom=height-bottom-contextHeight
    val composerWidth=minOf(size(176f),width-2*insetForBand(inputBottom-inputHeight,inputBottom))
    val contextWidth=minOf(size(140f),width-2*insetForBand(inputBottom,height-bottom))
    private val headerHalf=chatButton+size(48f)/2f
    val chatTop=if(round)maxOf(size(22f),ceil(height/2f-sqrt((radius*radius-headerHalf*headerHalf).coerceAtLeast(0f))).toInt()) else size(18f)
    // Circular controls need their painted circle inside the display, not imaginary square corners.
    private val circleInset=if(round)ceil(width/2f-sqrt(((radius-chatButton/2f)*(radius-chatButton/2f)-(chatTop+chatButton/2f-height/2f)*(chatTop+chatButton/2f-height/2f)).coerceAtLeast(0f))-chatButton/2f).toInt() else pageSide
    val chatHeaderInset=maxOf(circleInset,insetForBand(chatTop,chatTop+chatButton)-size(10f))
    val chatInputHeight=chatButton
    val chatBottom=size(7f)
    val chatComposerGap=size(1f)
    val modelTouchHeight=chatButton
    val modelVisualHeight=size(14f)
    val chatComposerHeight=chatComposerGap+chatInputHeight+chatComposerGap+modelTouchHeight
    private val modelCenterY=height-chatBottom-modelTouchHeight/2f
    val modelWidth=if(round)minOf(size(100f),(2*(sqrt(((radius-modelTouchHeight/2f)*(radius-modelTouchHeight/2f)-(modelCenterY-height/2f)*(modelCenterY-height/2f)).coerceAtLeast(0f))+modelTouchHeight/2f)).toInt()) else size(160f)
    private val chatInputBottom=height-chatBottom-modelTouchHeight-chatComposerGap
    val chatComposerWidth=minOf(size(176f),width-2*insetForBand(chatInputBottom-chatInputHeight,chatInputBottom))
}
