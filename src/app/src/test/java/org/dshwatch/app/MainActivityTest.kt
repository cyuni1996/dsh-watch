package org.dshwatch.app

import android.os.Looper
import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class MainActivityTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private fun call(name: String) { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity) }
    private fun value(name: String): Any?=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
    private fun set(name: String,value: Any) { MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(activity,value) }

    @Before fun start() {
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        RuntimeEnvironment.getApplication().getSharedPreferences("connection",0).edit().clear().commit()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activity=controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        call("closeOverlay")
        layout()
    }
    @After fun stop() { controller.pause().stop().destroy() }

    @Test fun autoReadRequestsAudioOnceAfterTheReplyFinishes() {
        val requests = java.util.concurrent.LinkedBlockingQueue<String>()
        val http = okhttp3.OkHttpClient()
        val bridge = BridgeClient("https://watch.invalid","test","0".repeat(64),okhttp3.Call.Factory { request ->
            object : okhttp3.Call by http.newCall(request) {
                override fun execute(): okhttp3.Response {
                    assertEquals("/api/speak",request.url.encodedPath)
                    val payload = okio.Buffer()
                    request.body?.writeTo(payload)
                    requests.add(payload.readUtf8())
                    throw java.io.IOException("test completes after observing the request")
                }
            }
        })
        set("client",bridge)
        set("sessionId","test-session")
        activity.getSharedPreferences("connection",0).edit().putBoolean("autoRead",true).commit()
        val reply = JSONObject().put("id","17").put("role","assistant").put("text","你好呀主人")
        val snapshot = JSONObject().put("connected",true).put("busy",true).put("messages",JSONArray().put(reply))
        set("state",snapshot)
        call("updateControls")
        assertNull(requests.poll(150,TimeUnit.MILLISECONDS))
        snapshot.put("busy",false)
        call("updateControls")
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        val request = JSONObject(requireNotNull(requests.poll(2,TimeUnit.SECONDS)))
        assertEquals("test-session",request.getString("sessionId"))
        assertEquals("17",request.getString("messageId"))
        call("updateControls")
        assertNull(requests.poll(150,TimeUnit.MILLISECONDS))
    }

    private fun layout(size:Int=500) {
        val decor=activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY))
        decor.layout(0,0,size,size)
    }
    private fun swipe(left: Boolean, vertical: Boolean=false) {
        layout()
        val start=SystemClock.uptimeMillis()
        val x=if(left)380f else 100f
        val dx=if(left)-240f else 240f
        listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).forEachIndexed { i,action ->
            val event=MotionEvent.obtain(start,start+i*100L,action,x+dx*i/2,210f+if(vertical)i*150 else 0,0)
            activity.dispatchTouchEvent(event); event.recycle()
        }
    }

    @Test fun leftThenRightSwipeReturnOnePageAndRootSwipesStayInChat() {
        call("settingsPage"); val settings=value("overlay")
        call("inputHelp"); assertEquals("input-help",value("mode"))
        swipe(left=true)
        assertEquals("settings",value("mode")); assertSame(settings,value("overlay"))
        assertFalse(activity.isFinishing)
        swipe(left=false)
        assertEquals("chat",value("mode")); assertFalse(activity.isFinishing)
        for (left in listOf(false, true)) {
            swipe(left=left)
            assertEquals("chat",value("mode"))
            assertEquals(0f,(value("chatPage") as View).translationX)
            assertFalse(activity.isFinishing)
        }
    }

    @Test fun backKeyAndHeaderUseSameStackAndPageRefreshDoesNotDuplicateIt() {
        call("settingsPage"); call("settingsPage"); call("inputHelp")
        activity.onBackPressed()
        assertEquals("settings",value("mode"))
        val panel=value("overlay") as ViewGroup
        fun find(view: View): View? {
            if(view.contentDescription=="返回上一页")return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let { return it }
            return null
        }
        assertTrue(find(panel)!!.performClick())
        assertEquals("chat",value("mode")); assertFalse(activity.isFinishing)
        activity.onBackPressed(); assertTrue(activity.isFinishing)
    }

    @Test fun horizontalDragStartsAtSlopAndReusesTheAttachedPreviousPage() {
        call("settingsPage")
        val previous = value("overlay") as View
        call("inputHelp")
        val outgoing = value("overlay") as View
        layout()
        var attaches = 0
        var detaches = 0
        previous.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { attaches++ }
            override fun onViewDetachedFromWindow(view: View) { detaches++ }
        })
        val start = SystemClock.uptimeMillis()
        fun dispatch(action: Int, x: Float, offset: Long) {
            val event = MotionEvent.obtain(start, start + offset, action, x, 210f, 0)
            activity.dispatchTouchEvent(event)
            event.recycle()
        }

        dispatch(MotionEvent.ACTION_DOWN, 100f, 0)
        dispatch(MotionEvent.ACTION_MOVE, 155f, 60)
        assertEquals(0f, outgoing.translationX)
        assertNotNull(previous.parent)
        dispatch(MotionEvent.ACTION_MOVE, 215f, 110)
        assertEquals(60f, outgoing.translationX)
        dispatch(MotionEvent.ACTION_UP, 255f, 160)

        assertSame(previous, value("overlay"))
        assertEquals(1, attaches)
        assertEquals(0, detaches)
    }

    @Test fun mostlyVerticalDragDoesNotNavigate() {
        call("settingsPage"); call("inputHelp")
        swipe(left=true,vertical=true)
        assertEquals("input-help",value("mode")); assertFalse(activity.isFinishing)
    }

    @Test fun lateWorkerResultAfterPauseAndResumeCannotTouchUiOrWakeScreen() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val posted=CountDownLatch(1)
        var called=false
        val job: () -> Unit = {
            entered.countDown()
            while(release.count>0)try { release.await() } catch(_:InterruptedException) {}
            val callback: () -> Unit = { called=true; set("voiceBusy",true); call("updateControls") }
            MainActivity::class.java.getDeclaredMethod("postResult",kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible=true }.invoke(activity,callback)
            posted.countDown()
        }
        MainActivity::class.java.getDeclaredMethod("work",kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible=true }.invoke(activity,job)
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        controller.pause().stop().start().resume().visible()
        release.countDown(); assertTrue(posted.await(2,TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(called)
        assertEquals(0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test fun computerSwitchRestoresItsDraftAndRejectsTheOldComputersLateResult() {
        val store=ComputerProfiles(activity.getSharedPreferences("connection",0))
        val a=ComputerProfile("pc-a","A","https://a.invalid","token-a","a".repeat(64))
        val b=ComputerProfile("pc-b","B","https://b.invalid","token-b","b".repeat(64))
        store.save(b);store.putState("sessionId","same-id");store.putState("draft:same-id","B draft")
        store.save(a);store.putState("sessionId","same-id")
        set("sessionId","same-id")
        (value("input") as android.widget.EditText).setText("A draft")
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val posted=CountDownLatch(1)
        val oldJob: () -> Unit = {
            entered.countDown()
            while(release.count>0)try { release.await() } catch(_:InterruptedException) {}
            val callback: () -> Unit = { (value("input") as android.widget.EditText).setText("old computer result") }
            MainActivity::class.java.getDeclaredMethod("postResult",kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible=true }.invoke(activity,callback)
            posted.countDown()
        }
        MainActivity::class.java.getDeclaredMethod("work",kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible=true }.invoke(activity,oldJob)
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        // Prevent real I/O; resume the UI after the switch so generation isolation is tested.
        set("resumed",false)
        MainActivity::class.java.getDeclaredMethod("connectComputer",ComputerProfile::class.java)
            .apply { isAccessible=true }.invoke(activity,b)
        set("resumed",true);release.countDown();assertTrue(posted.await(2,TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("B draft",(value("input") as android.widget.EditText).text.toString())
        assertEquals("A draft",prefsState(store,a.id,"draft:same-id"))
        assertEquals("B draft",store.getState("draft:same-id"))
    }
    private fun prefsState(store: ComputerProfiles,id: String,key: String): String?=
        activity.getSharedPreferences("connection",0).getString(store.stateKey(key,id),null)

    @Test fun sendPositionTogglesToStopWithoutAnExtraControlRow() {
        set("client",BridgeClient("https://watch.invalid","test","0".repeat(64)))
        val input=value("input") as android.widget.EditText
        input.setText("next question")
        set("state",JSONObject().put("connected",true).put("busy",false))
        call("updateControls")
        val button=value("send") as GlyphButton
        assertEquals("send",button.glyph);assertTrue(button.isEnabled)
        set("state",JSONObject().put("connected",true).put("busy",true).put("canInterrupt",true))
        call("updateControls")
        assertEquals("stop",button.glyph);assertEquals("停止本次回复",button.contentDescription)
        assertTrue(button.isEnabled)
        fun hasSeparateStop(view: View): Boolean {
            if(view is android.widget.TextView && view.text.toString()=="停止回复")return true
            return view is ViewGroup && (0 until view.childCount).any { hasSeparateStop(view.getChildAt(it)) }
        }
        assertFalse(hasSeparateStop(activity.window.decorView))
        set("state",JSONObject().put("connected",true).put("busy",false));call("updateControls")
        assertEquals("send",button.glyph);assertEquals("next question",input.text.toString())
    }

    @Test fun headerPullDownOpensUsageAndChatBodyDragKeepsScrolling() {
        fun down(y: Float) {
            layout()
            val time=SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).forEachIndexed { i,action ->
                val event=MotionEvent.obtain(time,time+i*100L,action,250f,y+i*80f,0)
                activity.dispatchTouchEvent(event);event.recycle()
            }
        }
        down(40f);assertEquals("usage",value("mode"))
        activity.onBackPressed();assertEquals("chat",value("mode"))
        val header=android.graphics.Rect();(value("headerTitles") as View).getGlobalVisibleRect(header)
        down((header.bottom-4).toFloat());assertEquals("usage",value("mode"))
        activity.onBackPressed();assertEquals("chat",value("mode"))
        down(210f);assertEquals("chat",value("mode"));assertFalse(activity.isFinishing)
        layout()
        val scroll = value("chatScroll") as View
        val time = SystemClock.uptimeMillis()
        val y = scroll.top + 8f
        listOf(110f to y, 140f to y + 24f, 152f to y + 68f, 158f to y + 96f)
            .forEachIndexed { i, (x, yy) ->
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 3 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(time, time + i * 50L, action, x, yy, 0)
                activity.dispatchTouchEvent(event)
                event.recycle()
            }
        assertEquals("usage", value("mode"))
        assertEquals(0f, (value("chatPage") as View).translationY)
        activity.onBackPressed()
        assertTrue((value("statusText") as View).performClick())
        assertEquals("usage", value("mode"))
    }

    @Test fun pendingChoiceAutomaticallyOpensOneModalAndHeaderCanReopenIt() {
        set("client",BridgeClient("https://watch.invalid","test","0".repeat(64)))
        val item=JSONObject().put("token","a".repeat(64)).put("title","请选择回复")
            .put("questions",JSONArray()).put("actions",JSONArray())
        set("state",JSONObject().put("connected",true).put("approvals",JSONArray().put(item)))
        call("updateControls");shadowOf(Looper.getMainLooper()).idle()
        val dialog=value("approvalDialog") as android.app.Dialog
        assertTrue(dialog.isShowing);assertEquals("approval",value("mode"))
        dialog.onBackPressed();shadowOf(Looper.getMainLooper()).idle()
        assertNull(value("approvalDialog"));assertEquals("chat",value("mode"))
        call("updateControls");shadowOf(Looper.getMainLooper()).idle()
        assertNull(value("approvalDialog"))
        assertTrue((value("headerTitles") as View).performClick())
        assertEquals("context",value("mode"));assertNull(value("approvalDialog"))
        fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
        all(value("overlay") as View).filterIsInstance<android.widget.TextView>().first { it.text.toString()=="待确认操作" }.performClick()
        assertTrue((value("approvalDialog") as android.app.Dialog).isShowing)
    }
    @Test fun newChatFirstOpensProjectPickerWithoutSwitchingTheCurrentDraft() {
        set("sessionId","original-draft")
        (value("input") as android.widget.EditText).setText("keep my draft")
        call("newChat")
        assertEquals("projects",value("mode"))
        assertEquals("original-draft",value("sessionId"))
        assertEquals("keep my draft",(value("input") as android.widget.EditText).text.toString())
    }

    @Test fun projectPickerResumesAfterCancelledLoadWithoutDuplicatingNavigation() {
        call("settingsPage");call("newChat")
        val depth=(value("panelHistory") as java.util.ArrayDeque<*>).size
        controller.pause().stop().start().resume().visible()
        assertEquals("projects",value("mode"))
        assertEquals(depth,(value("panelHistory") as java.util.ArrayDeque<*>).size)
        assertTrue(value("projectPickerCreatesChat") as Boolean)
    }

    @Test fun nullThreadIdDraftKeepsItsProjectPickerAndCompactContextRow() {
        set("state",JSONObject().put("threadId",JSONObject.NULL).put("projectName","Sample Watch").put("connected",true))
        call("updateControls")
        val chip=value("projectText") as android.widget.TextView
        assertEquals("Sample Watch ⌄",chip.text.toString())
        assertNull(chip.parent);assertTrue((value("modelText") as View).isAttachedToWindow)
        (value("headerTitles") as View).performClick()
        assertEquals("context",value("mode"))
        (value("projectText") as View).performClick()
        assertEquals("projects",value("mode"))
    }

    @Test fun drawerGroupsProjectsDisplaysStatesAndExpandsEachProjectIndependently() {
        call("openDrawer")
        set("drawerLoading",false)
        set("drawerProjects",JSONArray().put(JSONObject().put("id","p").put("name","Sample Watch")))
        val items=JSONArray()
        listOf("running" to "运行中","waiting" to "等你回复","unread" to "有新回复","idle" to "空闲").forEachIndexed { i,pair ->
            items.put(JSONObject().put("threadId","t$i").put("title","chat$i").put("projectId","p").put("projectName","Sample Watch").put("status",pair.first).put("statusLabel",pair.second))
        }
        items.put(JSONObject().put("threadId","other").put("title","other chat").put("projectId",JSONObject.NULL).put("statusLabel","空闲"))
        set("drawerItems",items);call("drawDrawerRows")
        fun descendants(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
        fun views()=descendants(value("drawerRows") as View)
        fun texts()=views().filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(views().any { it.contentDescription=="项目分组：Sample Watch" })
        assertTrue(views().any { it.contentDescription=="项目分组：其他聊天" })
        assertTrue(texts().containsAll(listOf("运行中","等你回复","有新回复")))
        assertFalse(texts().contains("chat3"))
        views().filterIsInstance<android.widget.TextView>().first { it.text.toString().startsWith("展开 Sample Watch") }.performClick()
        assertTrue(texts().contains("chat3"))
        assertTrue(texts().contains("other chat"))
    }

    @Test fun openingDrawerRevealsAndCentersTheCurrentChatBelowCollapsedRows() {
        set("state",JSONObject().put("threadId","active"))
        set("resumed",false)
        call("openDrawer")
        set("resumed",true)
        set("drawerLoading",false)
        val projects=JSONArray()
        val items=JSONArray()
        repeat(6) { group ->
            projects.put(JSONObject().put("id","p$group").put("name","Project $group"))
            repeat(6) { item ->
                val id=if(group==4 && item==5)"active" else "t-$group-$item"
                items.put(JSONObject().put("threadId",id).put("title",id).put("projectId","p$group"))
            }
        }
        set("drawerProjects",projects);set("drawerItems",items);call("drawDrawerRows")
        val scroll=value("drawerScroll") as WatchScrollView
        val row=scroll.findViewWithTag<View>("drawer-current")
        assertTrue(row.isClickable);assertTrue(row.isSelected)
        assertEquals("当前会话",row.stateDescription)
        call("locateCurrentDrawerRow");layout();shadowOf(Looper.getMainLooper()).idle()
        assertTrue("row=${row.top}, viewport=${scroll.height}, range=${scroll.range()}",scroll.scrollY>0)
        assertTrue(row.top>=scroll.scrollY)
        assertTrue(row.bottom<=scroll.scrollY+scroll.height)
        val atOpen=scroll.scrollY
        scroll.rotary(1f)
        assertTrue(scroll.scrollY<atOpen)
        assertEquals("drawer",value("mode"))
        row.performClick()
        assertEquals("chat",value("mode"))
    }

    @Test @Config(qualifiers="w240dp-h240dp-round-mdpi")
    fun realHeaderBoundsAndPanelsUseTheFullDisplayWithSafeButtons() {
        fun measured() {
            val decor=activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));decor.layout(0,0,240,240)
        }
        fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList())
        fun check(label:String) {
            val view=all(activity.window.decorView).first { it.contentDescription==label }
            val pos=IntArray(2);view.getLocationOnScreen(pos)
            val cx=pos[0]+view.width/2f;val cy=pos[1]+view.height/2f
            assertTrue("$label circle",kotlin.math.sqrt((cx-120)*(cx-120)+(cy-120)*(cy-120))+view.width/2f<=117)
            assertTrue(view.width>=36);assertTrue(view.height>=36)
        }
        measured();check("打开历史对话");check("设置")
        call("openDrawer");measured()
        val overlay=value("overlay") as ViewGroup
        assertEquals(240,overlay.width);assertEquals(240,overlay.height)
        assertEquals(240,overlay.getChildAt(0).width)
        check("关闭历史对话");check("新建对话");check("搜索聊天")
        call("settingsPage");measured();check("返回上一页")
    }

    @Test fun appearanceChangesPersistWithoutChangingComputerSessionOrDraft() {
        val prefs=activity.getSharedPreferences("connection",0)
        prefs.edit().putString("activeComputer","keep-computer").commit()
        set("sessionId","keep-chat")
        val input=value("input") as android.widget.EditText;input.setText("keep this draft")
        call("appearancePage")
        fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList())
        val toggle=all(value("overlay") as View).filterIsInstance<android.widget.TextView>().first { it.text.toString().startsWith("角色插画：") }
        toggle.performClick()
        assertTrue(prefs.getBoolean("ui.quietBackground",false))
        assertEquals("keep-chat",value("sessionId"));assertEquals("keep this draft",input.text.toString())
        assertEquals("keep-computer",prefs.getString("activeComputer",null))
        call("settingsPage");call("appearancePage")
        assertTrue(all(value("overlay") as View).filterIsInstance<android.widget.TextView>().any { it.text.toString()=="角色插画：隐藏" })
    }

    @Test fun drawerSearchOnlyUsesExtraRowWhenExpandedAndClosingClearsFilter() {
        call("openDrawer")
        val field=value("search") as android.widget.EditText
        assertEquals(View.GONE,field.visibility)
        fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList())
        val button=all(value("overlay") as View).first { it.contentDescription=="搜索聊天" }
        button.performClick();assertEquals(View.VISIBLE,field.visibility)
        field.setText("test filter")
        button.performClick();assertEquals(View.GONE,field.visibility)
        assertEquals("",value("drawerQuery"))
    }

    @Test @Config(qualifiers="w240dp-h240dp-round-mdpi")
    fun compactChatGivesSpaceBackAndProjectModelRemainReachable() {
        val decor=activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));decor.layout(0,0,240,240)
        val messages=value("chatScroll") as View
        assertEquals(101,messages.height)
        assertTrue((value("modelText") as View).isAttachedToWindow)
        assertNull((value("projectText") as View).parent)
        val field=value("input") as android.widget.EditText;field.setText("keep my draft")
        (value("headerTitles") as View).performClick();assertEquals("context",value("mode"))
        assertTrue((value("modelText") as View).isAttachedToWindow)
        assertTrue((value("projectText") as View).isAttachedToWindow)
        (value("modelText") as View).performClick();assertEquals("models",value("mode"))
        activity.onBackPressed();assertEquals("context",value("mode"))
        activity.onBackPressed();assertEquals("chat",value("mode"))
        assertEquals("keep my draft",field.text.toString())
    }

    @Test fun chatHasNoDarkScrollFadeAndKeepsTheExpandedViewport() {
        assertFalse((value("chatScroll") as android.widget.ScrollView).isVerticalFadingEdgeEnabled)
    }

    @Test fun settingsAndNestedPagesNeverDrawBlackFadingEdges() {
        fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList())
        for(page in listOf("settingsPage","appearancePage","computersPage","inputHelp","openDrawer")) {
            call(page)
            val scrolls=all(value("overlay") as View).filterIsInstance<android.widget.ScrollView>()
            assertTrue("$page must exercise a scrolling panel",scrolls.isNotEmpty())
            for(scroll in scrolls) {
                assertFalse("$page bottom stripe",scroll.isVerticalFadingEdgeEnabled)
                assertFalse("$page side stripe",scroll.isHorizontalFadingEdgeEnabled)
                assertEquals(View.OVER_SCROLL_NEVER,scroll.overScrollMode)
            }
        }
    }

    @Test @Config(qualifiers="w240dp-h240dp-round-mdpi")
    fun panelViewportReachesTheScreenBottomButKeepsFixedActionsSeparate() {
        fun measure() {
            val decor=activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));decor.layout(0,0,240,240)
        }
        call("settingsPage");measure()
        var page=(value("overlay") as ViewGroup).getChildAt(0) as ViewGroup
        val scroll=page.getChildAt(page.childCount-1) as android.widget.ScrollView
        assertEquals("No rectangular cut above the circular screen edge",page.height,scroll.bottom)
        assertEquals(0,page.paddingBottom)
        assertTrue(scroll.paddingBottom>=48)
        call("drawModels");measure()
        page=(value("overlay") as ViewGroup).getChildAt(0) as ViewGroup
        val save=page.getChildAt(page.childCount-1)
        val modelScroll=page.getChildAt(page.childCount-2) as android.widget.ScrollView
        assertTrue("Scrolling models must not cover Save",modelScroll.bottom<=save.top)
        assertTrue(save.bottom<=page.height-page.paddingBottom)
    }

    @Test @Config(qualifiers="w240dp-h240dp-round-mdpi")
    fun settingsCanScrollFromOverTheChatInputToTheVersionFooter() {
        call("settingsPage")
        val decor=activity.window.decorView
        val size=View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY)
        decor.measure(size,size)
        decor.layout(0,0,240,240)
        val scroll=value("activeScroll") as WatchScrollView
        val inputBounds=Rect()
        assertTrue((value("input") as View).getGlobalVisibleRect(inputBounds))
        assertTrue(scroll.canScrollVertically(1))
        val start=SystemClock.uptimeMillis()
        val from=inputBounds.centerY().toFloat()
        val to=scroll.top+12f
        val actions=listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)
        actions.forEachIndexed { index, action ->
            val event=MotionEvent.obtain(start,start+index*100L,action,
                inputBounds.centerX().toFloat(),from+(to-from)*index/actions.lastIndex,0)
            activity.dispatchTouchEvent(event)
            event.recycle()
        }
        assertTrue("The hidden chat input must not cancel the settings drag",scroll.scrollY>0)
        scroll.scrollTo(0,scroll.range())
        assertFalse(scroll.canScrollVertically(1))
        val list=scroll.getChildAt(0) as ViewGroup
        assertEquals(6,list.childCount)
        val footer=list.getChildAt(list.childCount-1) as android.widget.TextView
        val version=activity.packageManager.getPackageInfo(activity.packageName,0).versionName.orEmpty()
        assertEquals("DSH Watch $version".trimEnd(),footer.text.toString())
        val bounds=Rect()
        assertTrue(footer.getGlobalVisibleRect(bounds))
        assertTrue(bounds.bottom<=240)
    }

    private fun panelLabels(view:View=value("overlay") as View):List<android.widget.TextView> =
        if(view is ViewGroup)(0 until view.childCount).flatMap { panelLabels(view.getChildAt(it)) }
        else listOfNotNull(view as? android.widget.TextView)

    @Test @Config(qualifiers="w240dp-h240dp-round-mdpi")
    fun settingsToggleKeepsTheBottomAndTheSameButton() {
        call("settingsPage");layout(240)
        val page=value("overlay")
        val scroll=value("activeScroll") as WatchScrollView
        scroll.scrollToEnd()
        val offset=scroll.scrollY
        assertTrue(offset>0)
        val toggle=panelLabels().first { it.text=="触感反馈：开启" }
        assertTrue(toggle.performClick());layout(240)
        assertSame(page,value("overlay"))
        assertSame(scroll,value("activeScroll"))
        assertEquals(offset,scroll.scrollY)
        assertEquals("触感反馈：关闭",toggle.text.toString())
        assertFalse(activity.getSharedPreferences("connection",0).getBoolean("ui.haptics",true))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,(value("chatPage") as View).importantForAccessibility)
        activity.onBackPressed()
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,(value("chatPage") as View).importantForAccessibility)
    }

    @Test fun appearanceToggleAndFontPickerPreserveTheParentPanel() {
        call("settingsPage");call("appearancePage")
        val page=value("overlay")
        val background=panelLabels().first { it.text=="角色插画：显示" }
        assertTrue(background.performClick())
        assertSame(page,value("overlay"))
        assertEquals("角色插画：隐藏",background.text.toString())
        val font=panelLabels().first { it.text=="字体：标准" }
        assertTrue(font.performClick())
        assertTrue(panelLabels().first { it.text=="较大" }.performClick())
        assertSame(page,value("overlay"))
        assertEquals("字体：较大",font.text.toString())
    }

    @Test fun switchingHistoryTabsAndModelOptionsKeepsEachViewport() {
        call("openDrawer")
        val drawer=value("overlay")
        val drawerViewport=value("activeScroll")
        val pinned=panelLabels().first { it.contentDescription=="会话分类：置顶" }
        assertTrue(pinned.performClick())
        assertTrue(pinned.isSelected)
        assertSame(drawer,value("overlay"))
        assertSame(drawerViewport,value("activeScroll"))
        call("closeOverlay")
        val available=JSONArray().put(JSONObject().put("id","a").put("name","模型 A")
            .put("efforts",JSONArray().put("medium")))
        set("models",available)
        call("drawModels")
        val modelsPanel=value("overlay")
        val modelsViewport=value("activeScroll")
        val save=panelLabels().first { it.text=="保存" }
        assertTrue(panelLabels().first { it.text=="模型 A" }.performClick())
        assertTrue(panelLabels().first { it.text=="均衡" }.performClick())
        assertTrue(panelLabels().first { it.text=="✓ 模型 A" }.performClick())
        assertEquals("medium",value("selectedEffort"))
        assertSame(modelsPanel,value("overlay"))
        assertSame(modelsViewport,value("activeScroll"))
        assertSame(save,panelLabels().first { it.text=="保存" })
    }

    @Test fun refreshingUsageAndProjectsKeepsTheirExistingPanels() {
        for(name in listOf("usagePage","projectPicker")) {
            val open=MainActivity::class.java.getDeclaredMethod(name,Boolean::class.javaPrimitiveType)
                .apply { isAccessible=true }
            open.invoke(activity,false)
            val page=value("overlay")
            val viewport=value("activeScroll")
            open.invoke(activity,true)
            assertSame(page,value("overlay"))
            assertSame(viewport,value("activeScroll"))
            call("closeOverlay")
        }
    }

}
