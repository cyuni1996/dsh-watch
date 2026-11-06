package org.dshwatch.app

import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class) @Config(sdk=[30])
class WorkflowTest {
    private val requests=CopyOnWriteArrayList<Pair<String,JSONObject>>()
    private var failModels=false;private var failSave=false
    private val snapshot=JSONObject().put("id","session-a").put("threadId","thread-a").put("title","当前聊天").put("connected",true).put("busy",true)
        .put("settings",JSONObject().put("model","old").put("thinking","low")).put("messages",JSONArray()).put("approvals",JSONArray())
    private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var a:MainActivity
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun set(name:String,value:Any) { MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(a,value) }
    private fun call(name:String) { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(a) }
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
    private fun texts()=all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(label:String) { all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()==label && it.isClickable }.performClick() }
    private fun await(check:()->Boolean) {
        val deadline=System.nanoTime()+3_000_000_000L
        while(System.nanoTime()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) }
        fail("Timed out: ${texts()}")
    }
    @Before fun start() {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("connection",0).edit().clear().commit()
        Settings.Global.putFloat(app.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible();a=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();call("closeOverlay")
        set("sessionId","session-a");set("state",JSONObject(snapshot.toString()))
        val factory=Call.Factory { request -> object:Call {
            override fun request()=request
            override fun timeout()=Timeout()
            override fun isExecuted()=true
            override fun isCanceled()=false
            override fun cancel() {}
            override fun clone():Call=error("No retry")
            override fun enqueue(callback:Callback)=error("Only synchronous test I/O")
            override fun execute():Response {
                val buffer=Buffer();request.body?.writeTo(buffer);val raw=buffer.readUtf8();val body=if(raw.isBlank())JSONObject() else JSONObject(raw)
                val path=request.url.encodedPath;requests.add(path to body)
                val failed=(path=="/api/models" && failModels)||(path=="/api/session/settings" && failSave)
                val result=when(path) {
                    "/api/models"->JSONObject().put("models",JSONArray().put(JSONObject().put("id","new").put("name","新模型").put("efforts",JSONArray().put("high"))))
                    "/api/session/settings"->{ if(!failed)snapshot.put("settings",JSONObject().put("model",body.getString("model")).put("thinking",body.getString("thinking")));snapshot }
                    "/api/conversations"->JSONObject().put("conversations",JSONArray().put(JSONObject().put("threadId","other-thread").put("title","其他聊天"))).put("nextCursor",JSONObject.NULL)
                    "/api/conversation/action"->JSONObject().put("ok",true)
                    else->snapshot
                }
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(failed)503 else 200).message("test")
                    .body((if(failed)"{\"error\":\"隔离服务暂不可用\"}" else result.toString()).toResponseBody()).build()
            }
        } }
        set("client",BridgeClient("https://fixture.invalid","fixture-token","0".repeat(64),factory));call("updateControls")
        (field("input") as EditText).setText("保留草稿")
    }
    @After fun stop() { controller.pause().stop().destroy() }
    @Test fun failedModelLoadIsRetryableAndBackNeverChangesSettingsOrDraft() {
        failModels=true;call("openModels");await { "重新读取模型" in texts() }
        failModels=false;click("重新读取模型");await { "新模型" in texts() }
        a.onBackPressed();assertEquals("chat",field("mode"));assertEquals("保留草稿",(field("input") as EditText).text.toString())
        assertTrue(requests.none { it.first=="/api/session/settings" })
    }
    @Test fun saveFailureKeepsSelectionAndRetryOnlyAffectsTheNextSend() {
        call("openModels");await { "新模型" in texts() };click("新模型");click("深入")
        failSave=true;click("保存");await { requests.any { it.first=="/api/session/settings" } && field("modelsSaving")==false }
        assertEquals("models",field("mode"));assertEquals("new",field("selectedModel"));assertEquals("high",field("selectedEffort"))
        failSave=false;click("保存");await { field("mode")=="chat" }
        val saved=requests.last { it.first=="/api/session/settings" }.second
        assertEquals("session-a",saved.getString("sessionId"));assertEquals("new",saved.getString("model"));assertEquals("high",saved.getString("thinking"))
        assertTrue((field("state") as JSONObject).optBoolean("busy"));assertTrue(requests.none { it.first in listOf("/api/message","/api/interrupt") })
    }
    @Test fun historyDetailsPinTheTargetWithoutChangingCurrentChatOrDraft() {
        call("openDrawer");await { "其他聊天" in texts() }
        all(a.window.decorView).first { it.contentDescription=="对话详情：其他聊天" }.performClick()
        click("置顶");await { "取消置顶" in texts() }
        assertEquals("other-thread",requests.first { it.first=="/api/conversation/action" }.second.getString("threadId"))
        assertEquals("session-a",field("sessionId"));assertEquals("保留草稿",(field("input") as EditText).text.toString())
        click("重命名")
        all(a.window.decorView).filterIsInstance<EditText>().first { it.contentDescription=="对话名称" }.setText("修改后的名称")
        click("保存名称");await { field("mode")=="context" && "修改后的名称" in texts() }
        assertEquals("other-thread",requests.last { it.first=="/api/conversation/action" }.second.getString("threadId"))
    }
    @Test fun archivingTheCurrentChatReturnsToHistoryWithoutCreatingANewChat() {
        call("conversationInfo");click("归档对话");assertEquals("confirm",field("mode"));click("归档")
        await { field("mode")=="drawer" }
        assertTrue(requests.none { it.first=="/api/session/new" });assertEquals("保留草稿",(field("input") as EditText).text.toString())
    }
    @Test fun draggingProtectedControlsNeverSendsAMessageOrOpensModels() {
        set("state",JSONObject(snapshot.toString()).put("busy",false));call("updateControls")
        val decor=a.window.decorView;decor.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));decor.layout(0,0,480,480)
        for(name in listOf("send","modelText")) {
            val box=android.graphics.Rect();(field(name) as View).getGlobalVisibleRect(box)
            val time=android.os.SystemClock.uptimeMillis();val delta=android.view.ViewConfiguration.get(a).scaledTouchSlop+2f
            for((i,action) in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP).withIndex()) {
                val e=android.view.MotionEvent.obtain(time,time+i*50,action,box.exactCenterX(),box.exactCenterY()+if(i>0)delta else 0f,0)
                a.dispatchTouchEvent(e);e.recycle()
            }
        }
        Thread.sleep(100);Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(requests.none { it.first in listOf("/api/message","/api/models","/api/interrupt") });assertEquals("chat",field("mode"))
    }
}
