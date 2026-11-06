package org.dshwatch.app

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView

/** The last scrolling page reaches the display edge; only its content keeps a safe tail. */
class PanelLayout(context:Context,private val bottomInset:Int):LinearLayout(context) {
    private val tail=(24*resources.displayMetrics.density).toInt()
    init { orientation=VERTICAL;clipToPadding=false }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val last=if(childCount>0)getChildAt(childCount-1) as? ScrollView else null
        val reachesEdge=last!=null && (last.layoutParams as LayoutParams).weight>0
        setPadding(paddingLeft,paddingTop,paddingRight,if(reachesEdge)0 else bottomInset)
        for(i in 0 until childCount) {
            val scroll=getChildAt(i) as? ScrollView ?: continue
            scroll.setPadding(scroll.paddingLeft,scroll.paddingTop,scroll.paddingRight,tail+if(scroll===last && reachesEdge)bottomInset else 0)
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
