package org.dshwatch.app

import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[30])
class InteractionTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
    @Test fun settingsIsDirectAndDetailsDoNotExposeDuplicateModelActions() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup().visible();val a=c.get()
        fun call(name:String) { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(a) }
        fun set(name:String,value:Any) { MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(a,value) }
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
        try {
            call("closeOverlay")
            set("state",JSONObject().put("threadId","thread-a").put("title","完整的对话标题").put("connected",true))
            call("updateControls")
            all(a.window.decorView).first { it.contentDescription=="设置" }.performClick()
            assertEquals("settings",field("mode"));a.onBackPressed()
            all(a.window.decorView).first { it.contentDescription=="查看对话详情" }.performClick()
            val texts=all(field("overlay") as View).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.containsAll(listOf("完整的对话标题","重命名","置顶","归档对话","在电脑打开")))
            assertFalse(all(field("overlay") as View).any { it.contentDescription?.startsWith("模型与思考强度")==true })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun cancelledMotionNeverPopsAndPauseCancelsPendingCommit() {
        val context=RuntimeEnvironment.getApplication();Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        val view=View(context);view.layout(0,0,480,480)
        var popped=0;var cleaned=0
        val motion=BackMotion({view},{},{cleaned++},{popped++},{})
        motion.drag(-28f);assertEquals(0f,view.translationX)
        motion.drag(-108f);assertEquals(-80f,view.translationX);motion.finish(false)
        assertEquals(0,popped);assertEquals(0f,view.translationX)
        motion.drag(140f);motion.cancel();assertEquals(0,popped);assertFalse(motion.busy)
        motion.back();assertEquals(1,popped);assertTrue(cleaned>=3)
    }
    @Test fun freeScrollFeedbackNeverChangesMessageTypographyOrSemanticSelection() {
        val context=RuntimeEnvironment.getApplication()
        val scroll=WatchScrollView(context).apply { reading=true }
        val body=TextView(context).apply { textSize=13f;text="长消息\n".repeat(100) }
        scroll.addView(body);scroll.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));scroll.layout(0,0,240,240)
        scroll.scrollTo(0,150);assertEquals(1f,body.scaleX);assertFalse(body.isSelected)
        scroll.stopFeedback();assertFalse(scroll.interacting)
    }
    @Test fun longScrollCanReachBothEndsWithFlingAndSuccessiveCrownTicks() {
        val context=RuntimeEnvironment.getApplication()
        val scroll=WatchScrollView(context).apply { reading=true;overScrollMode=View.OVER_SCROLL_NEVER }
        scroll.addView(TextView(context).apply { minHeight=960;text="测试内容" })
        val size=View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY)
        scroll.measure(size,size);scroll.layout(0,0,240,240)
        assertTrue(scroll.range()>500)
        fun settle() {
            repeat(120) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                scroll.computeScroll()
            }
        }
        scroll.fling(5000);settle()
        assertEquals(scroll.range(),scroll.scrollY)
        scroll.fling(-5000);settle()
        assertEquals(0,scroll.scrollY)
        repeat(100) { scroll.rotary(-1f) }
        assertEquals(scroll.range(),scroll.scrollY)
        repeat(100) { scroll.rotary(1f) }
        assertEquals(0,scroll.scrollY)
    }
    @Test fun fractionalHardwareCrownTicksAccumulateAllTheWayToTheBoundary() {
        val context=RuntimeEnvironment.getApplication()
        val scroll=WatchScrollView(context)
        scroll.addView(TextView(context).apply { minHeight=500;text="测试内容" })
        val size=View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY)
        scroll.measure(size,size);scroll.layout(0,0,240,240)
        val step=0.0131f
        val end=scroll.range()
        assertTrue(end>1)
        scroll.scrollTo(0,end-1)
        repeat(8) { scroll.rotary(-step) }
        assertEquals(end,scroll.scrollY)
        repeat(8) { scroll.rotary(step) }
        assertTrue(scroll.scrollY<end)
        scroll.stopFeedback()
        scroll.scrollTo(0,1)
        repeat(8) { scroll.rotary(step) }
        assertEquals(0,scroll.scrollY)
    }
    @Test fun listEmphasisFollowsTheFirstAndLastActionsAtExactCrownBoundaries() {
        val context=RuntimeEnvironment.getApplication()
        val scroll=WatchScrollView(context)
        val list=android.widget.LinearLayout(context).apply { orientation=android.widget.LinearLayout.VERTICAL }
        val actions=(1..5).map {
            TextView(context).apply { text="设置 $it";isClickable=true }.also { list.addView(it,-1,64) }
        }
        list.addView(TextView(context).apply { text="DSH Watch" },-1,20)
        scroll.addView(list)
        val size=View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY)
        scroll.measure(size,size);scroll.layout(0,0,240,240)
        assertNotNull(actions.first().foreground)
        repeat(12) { scroll.rotary(-1f) }
        assertEquals(scroll.range(),scroll.scrollY)
        assertNotNull(actions.last().foreground)
        repeat(12) { scroll.rotary(1f) }
        assertEquals(0,scroll.scrollY)
        assertNotNull(actions.first().foreground)
        assertNull(actions.last().foreground)
    }
    @Test fun cancellingAnAnimatedReturnCannotCommitLaterOrPopTwice() {
        val context=RuntimeEnvironment.getApplication();Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
        val c=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val view=View(c.get());c.get().setContentView(view);view.layout(0,0,480,480)
        var popped=0;val motion=BackMotion({view},{},{},{popped++},{})
        try {
            motion.drag(140f);motion.finish(true);assertTrue(motion.settling)
            motion.back();motion.cancel()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertEquals(0,popped);assertEquals(0f,view.translationX);assertFalse(motion.busy)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun animatedCompletionIsOnceEvenWhenTheRemovedPageCancelsItsAnimator() {
        val context=RuntimeEnvironment.getApplication();Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
        val c=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible();val view=View(c.get());c.get().setContentView(view);view.layout(0,0,480,480)
        var popped=0
        val motion=BackMotion({view},{},{},{popped++;view.animate().cancel()},{})
        try {
            motion.back();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertEquals(1,popped);assertFalse(motion.busy)
        } finally { c.pause().stop().destroy() }
    }
    @Test @Config(qualifiers="w240dp-h240dp-round-mdpi")
    fun largeFontKeepsTheModelShortcutSeparateFromTheInputAndHeader() {
        val app=RuntimeEnvironment.getApplication();val metrics=app.resources.displayMetrics;val oldDensity=metrics.scaledDensity
        val oldScale=app.resources.configuration.fontScale
        app.resources.configuration.fontScale=1.3f;metrics.scaledDensity=metrics.density*1.3f
        Settings.Global.putFloat(app.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup().visible();val a=c.get()
        try {
            MainActivity::class.java.getDeclaredMethod("closeOverlay").apply { isAccessible=true }.invoke(a)
            val decor=a.window.decorView;decor.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));decor.layout(0,0,240,240)
            val model=all(decor).filterIsInstance<TextView>().first { it.contentDescription?.startsWith("模型与思考强度")==true }
            val input=all(decor).first { it.contentDescription=="消息输入框" }
            val mr=android.graphics.Rect();val ir=android.graphics.Rect();model.getGlobalVisibleRect(mr);input.getGlobalVisibleRect(ir)
            assertTrue(ir.bottom<=mr.top);assertTrue(model.height>=model.lineHeight);assertTrue(mr.height()>=36)
        } finally { c.pause().stop().destroy();metrics.scaledDensity=oldDensity;app.resources.configuration.fontScale=oldScale }
    }
}
