package org.dshwatch.app

import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private var pool = Executors.newFixedThreadPool(3)
    @Volatile private var foregroundGeneration = 0
    private val jobGeneration = ThreadLocal<Int>()
    private val jobClient = ThreadLocal<BridgeClient>()
    private var pollFailures = 0
    private var pollIssue = ""
    private var lastPolledState: JSONObject? = null
    private var stateEtag: String? = null
    private var displayedMinute = Long.MIN_VALUE
    private var networkWatchRegistered = false
    private val connectivity by lazy { getSystemService(android.net.ConnectivityManager::class.java) }
    private val networkWatch = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            ui.post {
                if (resumed && pollFailures > 0 && pollIssue != "授权已失效") fetch()
            }
        }
    }
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private val computers by lazy { ComputerProfiles(prefs) }
    private var lanDiscovery:LanDiscovery?=null
    private var restartLanScan: (() -> Unit)? = null
    private var pauseLanScan: (() -> Unit)? = null
    private var scanProgressAnimator: ValueAnimator? = null
    private var pairingClient:BridgeClient?=null
    private var connectionEpoch=0
    private var pausedPairTarget:ConnectionCandidate?=null
    private val ink = WhaleTheme.INK
    private val muted = WhaleTheme.MUTED
    private val accent = WhaleTheme.ACCENT
    private val surface = WhaleTheme.SURFACE
    private val goldInk = WhaleTheme.GOLD_INK
    private var animatedStatus = ""
    private lateinit var tideMotion: TideMotion
    private val watchLayout by lazy { resources.displayMetrics.let { WatchLayout(it.widthPixels,it.heightPixels,it.density,resources.configuration.isScreenRound) } }
    private var client: BridgeClient? = null
    private var state = JSONObject()
    private var sessionId = ""
    @Volatile private var resumed = false
    private var fetching = false
    private var sending = false
    private var controlling = false
    private lateinit var headerTitles: LinearLayout
    private var approvalDialog: Dialog? = null
    private var approvalPopupQueued=false
    private val shownApprovals=mutableSetOf<String>()
    private var openApprovalToken = ""
    private var approvalStateLabel: TextView? = null
    private val approvalActions = mutableListOf<TextView>()
    private var editingDraft = false
    private var lastChat = ""
    private var lastSession = ""
    private var localError = ""
    private var recording: VoiceRecorder? = null
    private var voiceBusy = false
    private var player: MediaPlayer? = null
    private var preparingPlayback = false
    private var playbackFile: File? = null
    private var playbackGeneration = 0
    private var lastSpoken = ""
    private var mode = "chat"
    private lateinit var root: FrameLayout
    private lateinit var chatPage:View
    private var previewPage:View?=null
    private val pageEasing = DecelerateInterpolator(1.5f)
    private val backMotion by lazy {
        BackMotion(
            target = { overlay ?: chatPage },
            preview = {
                val previous = panelHistory.peekLast()?.view
                if (previous != null && previous.parent == null) {
                    val index = if (overlay?.parent === root) root.indexOfChild(overlay) else root.childCount
                    root.addView(previous, index, FrameLayout.LayoutParams(-1, -1))
                    previewPage = previous
                }
            },
            cleanup = {
                previewPage?.takeUnless { it === overlay }?.let { (it.parent as? ViewGroup)?.removeView(it) }
                previewPage = null
            },
            commit = {
                hideKeyboard()
                if (overlay != null) popPanel() else finishAndRemoveTask()
            },
            settled = { if (resumed) maybeShowApproval() },
            motionEnabled = { prefs.getBoolean("ui.motion", true) },
        )
    }
    private data class DetailTarget(val id:String,val data:JSONObject)
    private var detailTarget:DetailTarget?=null
    private var modelsSaving=false
    private var managing=false
    private var modelRequest=0
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var clock: TextView
    private lateinit var chatScroll: WatchScrollView
    private lateinit var chat: LinearLayout
    private lateinit var input: EditText
    private lateinit var mic: GlyphButton
    private lateinit var send: GlyphButton
    private lateinit var modelText: TextView
    private lateinit var projectText: TextView
    private var projectPickerCreatesChat = true
    private lateinit var banner: TextView
    private lateinit var latest: TextView
    private var overlay: View? = null
    private var activeScroll: ScrollView? = null
    private var gestureEligible=false
    private var gestureHandled=false
    private var protectedControl=false
    private var doubleTapTriggered=false
    private var shortcutLongPressed=false
    private var swipeStartX=0f
    private var swipeStartY=0f
    private var swipeEligible=false
    private var swipeClaimed=false
    private var usageSwipeEligible=false
    private var usageSwipeClaimed=false
    private data class PanelSnapshot(
        val view: View, val name: String, val scroll: ScrollView?,
        val drawerScroll: WatchScrollView?, val drawerRows: LinearLayout?, val search: EditText?,
        val approvalToken: String, val approvalLabel: TextView?, val approvalButtons: List<TextView>,val detail:DetailTarget?,
        val restartLanScan: (() -> Unit)?
    )
    private val panelHistory = java.util.ArrayDeque<PanelSnapshot>()
    private val touchGestures by lazy {
        GestureDetector(this,object:GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event:MotionEvent)=true
            override fun onDoubleTap(event:MotionEvent):Boolean {
                if(!gestureEligible || mode!="chat" || recording!=null)return false
                doubleTapTriggered=true
                chatScroll.scrollToEnd(); latest.visibility=View.GONE
                return true
            }
        }).apply { setIsLongpressEnabled(false) }
    }
    private var drawerScroll: WatchScrollView? = null
    private var drawerRows: LinearLayout? = null
    private var search: EditText? = null
    private var drawerItems = JSONArray()
    private var drawerTab = "all"
    private var drawerQuery = ""
    private var drawerSearchExpanded = false
    private var drawerCursor: String? = null
    private var drawerRequest = 0
    private var drawerLoading = false
    private var drawerError = ""
    private var usageRequest = 0
    private var projectRequest = 0
    private var chatLinkRequest = 0
    private var drawerProjects = JSONArray()
    private val expandedProjects = mutableSetOf<String>()
    private val drawerStatusLabels = mutableMapOf<String,TextView>()
    private val drawerStatusLater = Runnable { refreshDrawerStatuses() }
    private var models = JSONArray()
    private var selectedModel = ""
    private var selectedEffort = ""
    private val bodies = linkedMapOf<String, TextView>()
    private val poll = object : Runnable {
        override fun run() { if (resumed) fetch() }
    }
    private val searchLater = Runnable { if (mode == "drawer") loadDrawer(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DesktopSetupProvider.consumeWakeRequest()) setTurnScreenOn(true)
        window.setDecorFitsSystemWindows(false)
        computers.migrateLegacy()
        sessionId=computers.getState("sessionId")
        setupClient()
        createLayout()
        restoreDraft()
        updateMessages()
        updateControls()
        window.decorView.post { window.insetsController?.hide(WindowInsets.Type.statusBars()) }
        if (client == null) ui.post { computersPage() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (DesktopSetupProvider.consumeWakeRequest()) setTurnScreenOn(true)
    }

    private fun setupClient() {
        client=runCatching { computers.active()?.let { BridgeClient(it.url,it.token,it.pin) } }.getOrNull()
    }
    private fun connectComputer(profile: ComputerProfile) {
        val next=BridgeClient(profile.url,profile.token,profile.pin)
        saveDraft()
        computers.save(profile)
        foregroundGeneration++; ui.removeCallbacksAndMessages(null); approvalPopupQueued=false; shownApprovals.clear()
        client?.setForeground(false); pool.shutdownNow()
        recording?.stop(); recording=null; releasePlayback()
        fetching=false; sending=false; controlling=false; voiceBusy=false; pollFailures=0; pollIssue=""
        lastPolledState=null; stateEtag=null
        client=next; client?.setForeground(resumed)
        if(resumed)pool=Executors.newFixedThreadPool(3)
        sessionId=computers.getState("sessionId"); state=JSONObject(); localError=""
        lastChat=""; lastSession=""; lastSpoken=""; models=JSONArray()
        selectedModel=""; selectedEffort=""; drawerItems=JSONArray(); drawerCursor=null
        drawerQuery=""; drawerSearchExpanded=false; drawerTab="all"; drawerLoading=false; drawerProjects=JSONArray(); expandedProjects.clear(); drawerStatusLabels.clear()
        restoreDraft(); closeOverlay(); updateMessages(true); updateControls(); fetch()
    }
    override fun onResume() {
        super.onResume()
        foregroundGeneration++; resumed=true; approvalPopupQueued=false; shownApprovals.clear()
        if(pool.isShutdown)pool=Executors.newFixedThreadPool(3)
        client?.setForeground(true)
        networkWatchRegistered = runCatching { connectivity.registerDefaultNetworkCallback(networkWatch) }.isSuccess
        computers.takeDesktopSetup()?.let {
            connectComputer(it)
            return
        }
        updateControls()
        ui.removeCallbacks(poll); ui.post(poll)
        if(mode=="drawer")loadDrawer(true,keepPosition=true)
        else if(mode=="pair-device")pausedPairTarget?.let { pairingPaused(it) }
        else if(mode=="projects")projectPicker(projectPickerCreatesChat)
        else if(mode=="usage")usagePage()
        else if(mode=="models") { if(models.length()==0)openModels() else drawModels() }
    }
    override fun onPause() {
        setTurnScreenOn(false)
        backMotion.cancel();stopScrollFeedback(root)
        resetUsagePull()
        panelHistory.forEach { stopScrollFeedback(it.view) }
        overlay?.let { it.animate().cancel();it.alpha=1f;it.translationX=0f;it.translationY=0f }
        stopConnectionTasks()
        resumed=false; foregroundGeneration++; approvalPopupQueued=false
        if (networkWatchRegistered) { connectivity.unregisterNetworkCallback(networkWatch); networkWatchRegistered = false }
        ui.removeCallbacksAndMessages(null); saveDraft(); drawerRequest++
        client?.setForeground(false); pool.shutdownNow()
        fetching=false; sending=false; controlling=false; voiceBusy=false
        managing=false;modelsSaving=false
        recording?.stop(); recording=null
        releasePlayback()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tideMotion.play(null)
        super.onPause()
    }
    override fun onDestroy() {
        backMotion.cancel()
        stopConnectionTasks()
        ui.removeCallbacksAndMessages(null); client?.setForeground(false); pool.shutdownNow()
        releasePlayback()
        approvalDialog?.dismiss(); approvalDialog=null
        panelHistory.clear()
        super.onDestroy()
    }
    private fun schedulePoll() {
        ui.removeCallbacks(poll)
        if(resumed)ui.postDelayed(poll,if (pollIssue == "授权已失效") 30_000L else PollPolicy.delayMillis(
            sending || controlling || state.optBoolean("busy") || state.optBoolean("waitingOnApproval"),
            overlay!=null,pollFailures
        ))
    }
    private fun releasePlayback() {
        playbackGeneration++; player?.release(); player=null
        playbackFile?.delete(); playbackFile=null
        preparingPlayback=false
        syncScreenAwake()
    }
    private fun syncScreenAwake() {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (resumed && (recording != null || voiceBusy || preparingPlayback || player != null)) {
            window.addFlags(flag)
        } else {
            window.clearFlags(flag)
        }
    }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    private fun currentThreadId() = if(state.isNull("threadId"))"" else state.optString("threadId")
    private fun shape(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius=dp(radius).toFloat()
    }
    private fun pressable(color:Int,radius:Int=18)=android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(WhaleTheme.RIPPLE),shape(color,radius),shape(Color.WHITE,radius))
    private fun TextView.mark(glyph:String, color:Int=accent, size:Int=14) {
        setCompoundDrawables(GlyphDrawable(glyph,color).apply { setBounds(0,0,dp(size),dp(size)) },null,null,null)
        compoundDrawablePadding=dp(6)
    }
    private fun text(value: String, size: Float = 13f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text=value; textSize=size; setTextColor(color); includeFontPadding=false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun column() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun icon(glyph: String, name: String, action: () -> Unit) = GlyphButton(this,glyph,ink).apply { contentDescription=name; setOnClickListener { action() } }
    private fun action(label: String, primary: Boolean = false, run: () -> Unit) = text(label,12f,if(primary)goldInk else ink).apply {
        gravity=Gravity.CENTER_VERTICAL or if(primary)Gravity.CENTER_HORIZONTAL else Gravity.START
        minHeight=dp(36); setPadding(dp(14),dp(8),dp(12),dp(8))
        background=pressable(if(primary)WhaleTheme.GOLD else surface,14); isClickable=true; isFocusable=true
        WhaleGlyphs.forLabel(label)?.let { mark(it,if(primary)goldInk else accent) }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) }
        setOnClickListener { run() }
    }
    private fun navigation(label:String,run:()->Unit)=action(label,run=run).apply {
        setCompoundDrawables(compoundDrawables[0],null,GlyphDrawable("forward",muted).apply { setBounds(0,0,dp(12),dp(12)) },null)
    }
    private fun stopScrollFeedback(view:View) {
        if(view is WatchScrollView)view.stopFeedback()
        if(view is ViewGroup)for(i in 0 until view.childCount)stopScrollFeedback(view.getChildAt(i))
    }
    private fun scrollView() = WatchScrollView(this).apply {
        activeScroll=this
        val host=(overlay as? FrameLayout) ?: root
        feedback=(host.findViewWithTag<View>("scroll-feedback") as? ScrollFeedback) ?: ScrollFeedback(this@MainActivity).also { it.tag="scroll-feedback";host.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        isFillViewport=true; isVerticalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
        setPadding(0,0,0,dp(24));clipToPadding=false
        // Android fades transparent content toward black, leaving a stripe over the artwork.
        isVerticalFadingEdgeEnabled=false;isHorizontalFadingEdgeEnabled=false;setFadingEdgeLength(0)
        isFocusableInTouchMode=true
        setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) { rotary(event.getAxisValue(MotionEvent.AXIS_SCROLL)); true } else false
        }
    }
    override fun dispatchGenericMotionEvent(event:MotionEvent):Boolean {
        if(event.action==MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            if(window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true)return super.dispatchGenericMotionEvent(event)
            val target=if(overlay==null)chatScroll else activeScroll
            if(target!=null && target.isAttachedToWindow) {
                val amount=event.getAxisValue(MotionEvent.AXIS_SCROLL)
                if(target is WatchScrollView)target.rotary(amount)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }
    private fun onInteractiveControl(view:View,x:Int,y:Int,textOnly:Boolean=false):Boolean {
        val bounds=Rect()
        if(!view.getGlobalVisibleRect(bounds) || !bounds.contains(x,y))return false
        if(view is EditText || (!textOnly && (view is GlyphButton || (view is TextView && view.isClickable))))return true
        if(view is ViewGroup)for(i in view.childCount-1 downTo 0)if(onInteractiveControl(view.getChildAt(i),x,y,textOnly))return true
        return false
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(!::root.isInitialized || (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled)return super.dispatchTouchEvent(event)
        if(backMotion.settling)return true
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            gestureHandled=false
            resetUsagePull()
            swipeStartX=event.rawX; swipeStartY=event.rawY; swipeClaimed=false
            val imeOpen = root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            usageSwipeEligible = overlay == null && recording == null && !imeOpen &&
                event.y in 0f..(chatScroll.top + if (chatScroll.canScrollVertically(-1)) 0 else dp(12)).toFloat()
            val x=event.rawX.toInt(); val y=event.rawY.toInt()
            val protected=overlay==null && listOf(input,mic,send,modelText).any { onInteractiveControl(it,x,y) }
            protectedControl=protected
            swipeEligible=recording==null && !protected && !onInteractiveControl(overlay ?: root,x,y,textOnly=true)
            gestureEligible=overlay==null && recording==null && !onInteractiveControl(root,x,y) && event.y in chatScroll.top.toFloat()..chatScroll.bottom.toFloat()
        }
        if(event.pointerCount>1) { gestureEligible=false; swipeEligible=false; usageSwipeEligible=false }
        val dx=event.rawX-swipeStartX; val dy=event.rawY-swipeStartY
        if(event.actionMasked==MotionEvent.ACTION_MOVE && protectedControl && !gestureHandled && kotlin.math.hypot(dx,dy)>android.view.ViewConfiguration.get(this).scaledTouchSlop) {
            val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL };super.dispatchTouchEvent(cancel);cancel.recycle();gestureHandled=true
        }
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val release = event.actionMasked == MotionEvent.ACTION_UP
        if ((event.actionMasked == MotionEvent.ACTION_MOVE || release) && usageSwipeEligible && !usageSwipeClaimed) {
            if (dy < -touchSlop || (kotlin.math.abs(dx) > dp(22) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f)) {
                usageSwipeEligible = false
            } else if (dy > touchSlop && dy >= kotlin.math.abs(dx)) {
                usageSwipeClaimed=true; gestureEligible=false; swipeEligible=false
                val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel); touchGestures.onTouchEvent(cancel); cancel.recycle()
            }
        }
        if (usageSwipeClaimed) {
            if (release || event.actionMasked == MotionEvent.ACTION_CANCEL || !usageSwipeEligible) {
                val open = release && usageSwipeEligible && dy >= dp(30) && dy >= kotlin.math.abs(dx) * .9f
                resetUsagePull(animate = true)
                if (open) usagePage()
            } else if (canAnimate()) {
                chatPage.translationY = (dy * .18f).coerceIn(0f, dp(12).toFloat())
            }
            return true
        }
        if(event.actionMasked==MotionEvent.ACTION_MOVE && swipeEligible && !swipeClaimed && !usageSwipeEligible) {
            if(kotlin.math.abs(dy)>dp(18) && kotlin.math.abs(dy)>=kotlin.math.abs(dx))swipeEligible=false
            else if(kotlin.math.abs(dx)>dp(18) && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f) {
                swipeClaimed=true; gestureEligible=false
                val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel); touchGestures.onTouchEvent(cancel); cancel.recycle()
            }
        }
        if(swipeClaimed) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val distance = if (overlay == null) (dx * .16f).coerceIn(-dp(18).toFloat(), dp(18).toFloat()) else dx
                backMotion.drag(distance)
            }
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                val goBack=overlay!=null && event.actionMasked==MotionEvent.ACTION_UP && swipeEligible && kotlin.math.abs(dx)>=dp(64) && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f
                swipeClaimed=false; swipeEligible=false
                backMotion.finish(goBack, dx)
            }
            return true
        }
        if(gestureHandled) { if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)gestureHandled=false;return true }
        doubleTapTriggered=false
        if(gestureEligible)touchGestures.onTouchEvent(event)
        else if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
            touchGestures.onTouchEvent(cancel);cancel.recycle()
        }
        // Horizontal swipes share the same back stack as the back key and header.
        if(doubleTapTriggered && event.actionMasked==MotionEvent.ACTION_DOWN) {
            val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel);touchGestures.onTouchEvent(cancel);cancel.recycle();gestureHandled=true
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        return handled || (event.actionMasked == MotionEvent.ACTION_DOWN && usageSwipeEligible)
    }
    private fun resetUsagePull(animate: Boolean = false) {
        usageSwipeClaimed = false
        usageSwipeEligible = false
        chatPage.animate().cancel()
        if (animate && chatPage.translationY > 0f && canAnimate()) {
            chatPage.animate().translationY(0f).setDuration(145).setInterpolator(pageEasing).start()
        } else {
            chatPage.translationY = 0f
        }
    }

    // Wear's primary stem is reserved by the OS. Only assignable app keys are handled.
    private fun appShortcutKey(code:Int)=code in listOf(KeyEvent.KEYCODE_STEM_1,KeyEvent.KEYCODE_STEM_2,KeyEvent.KEYCODE_STEM_3)
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(appShortcutKey(keyCode)) { if(event.repeatCount==0) { shortcutLongPressed=false;event.startTracking() };return true }
        return super.onKeyDown(keyCode,event)
    }
    override fun onKeyLongPress(keyCode:Int,event:KeyEvent):Boolean {
        if(appShortcutKey(keyCode)) { shortcutLongPressed=true;hideKeyboard();if(overlay!=null)navigateBack() else { chatScroll.scrollToEnd();latest.visibility=View.GONE };return true }
        return super.onKeyLongPress(keyCode,event)
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {
        if(appShortcutKey(keyCode)) { if(!event.isCanceled && !shortcutLongPressed) { if(overlay!=null)closeOverlay();if(mic.isEnabled)toggleRecording() };return true }
        return super.onKeyUp(keyCode,event)
    }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show() }
    private fun requestClient(): BridgeClient {
        if(jobGeneration.get()!=foregroundGeneration || Thread.currentThread().isInterrupted)
            throw java.io.InterruptedIOException("应用已离开前台")
        return jobClient.get() ?: error("连接已结束")
    }
    private fun postResult(result: () -> Unit) {
        val generation=jobGeneration.get() ?: foregroundGeneration
        ui.post { if(resumed && generation==foregroundGeneration && !isFinishing && !isDestroyed)result() }
    }
    private fun work(job: () -> Unit) {
        if(!resumed || pool.isShutdown)return
        val generation=foregroundGeneration; val bridge=client
        pool.execute {
            jobGeneration.set(generation); jobClient.set(bridge)
            try { job() } catch (error: Exception) {
                postResult {
                    sending=false; controlling=false; voiceBusy=false
                    localError=error.message?.take(180) ?: "连接失败，请检查电脑"
                    if(mode=="drawer") { drawerLoading=false; drawDrawerRows() }
                    updateControls(); toast(localError)
                }
            } finally { jobGeneration.remove(); jobClient.remove() }
        }
    }
    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); input.clearFocus() }
    private fun saveDraft() { if (::input.isInitialized) computers.putState("draft:$sessionId",input.text.toString()) }
    private fun restoreDraft() {
        editingDraft=true; input.setText(computers.getState("draft:$sessionId")); input.setSelection(input.text.length); editingDraft=false
    }

    private fun createLayout() {
        root=FrameLayout(this).apply { background=WhaleTheme.backdrop(this@MainActivity); isFocusableInTouchMode=true }
        tideMotion = TideMotion(this)
        root.addView(tideMotion, FrameLayout.LayoutParams(-1, -1))
        val metrics=watchLayout
        val main=column().apply { setPadding(0,metrics.chatTop,0,metrics.chatBottom) }
        val toolbar=row()
        toolbar.addView(icon("menu","打开历史对话") { openDrawer() },LinearLayout.LayoutParams(metrics.chatButton,metrics.chatButton))
        headerTitles=column().apply {
            gravity=Gravity.CENTER_VERTICAL
            setOnClickListener { conversationInfo() }
        }
        titleText=text("新对话",12f,ink,true).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; gravity=Gravity.CENTER }
        statusText=text("连接中",8f,muted).apply {
            maxLines=1; ellipsize=TextUtils.TruncateAt.END; gravity=Gravity.CENTER
            setPadding(0,dp(2),0,0)
            setOnClickListener { openUsageShortcut() }
        }
        headerTitles.addView(titleText,LinearLayout.LayoutParams(-1,-2)); headerTitles.addView(statusText,LinearLayout.LayoutParams(-1,-2))
        toolbar.addView(headerTitles,LinearLayout.LayoutParams(0,-1,1f))
        toolbar.addView(icon("gear","设置") { settingsPage() },LinearLayout.LayoutParams(metrics.chatButton,metrics.chatButton))
        main.addView(toolbar,LinearLayout.LayoutParams(-1,metrics.chatButton).apply { leftMargin=metrics.chatHeaderInset; rightMargin=metrics.chatHeaderInset })
        chatScroll=scrollView().apply { reading=true;contentDescription="聊天消息"; isVerticalFadingEdgeEnabled=false; clipToPadding=true; setPadding(dp(20),0,dp(20),0) }
        chat=column().apply { setPadding(0,dp(2),0,dp(2)) }
        chatScroll.addView(chat,FrameLayout.LayoutParams(-1,-2))
        main.addView(chatScroll,LinearLayout.LayoutParams(-1,0,1f))
        banner=text("",9f,WhaleTheme.ALERT).apply { gravity=Gravity.CENTER; maxLines=2; ellipsize=TextUtils.TruncateAt.END; visibility=View.GONE; setPadding(dp(38),0,dp(38),dp(2)) }
        main.addView(banner,LinearLayout.LayoutParams(-1,-2))
        val composer=column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,metrics.chatComposerGap,0,0) }
        val box=row().apply { background=shape(WhaleTheme.SURFACE,21); setPadding(dp(6),0,dp(3),0) }
        input=EditText(this).apply {
            hint="发消息…"; textSize=13f; setTextColor(ink); setHintTextColor(muted); includeFontPadding=false
            background=null; setPadding(dp(5),dp(3),dp(2),dp(3)); minLines=1; maxLines=2; gravity=Gravity.CENTER_VERTICAL
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            contentDescription="消息输入框"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if(!editingDraft)saveDraft(); if(::send.isInitialized)updateControls() }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, action, event -> if(action==EditorInfo.IME_ACTION_SEND || (event?.keyCode==KeyEvent.KEYCODE_ENTER && event.action==KeyEvent.ACTION_DOWN && !event.isShiftPressed)) { if(!replyInProgress())sendMessage(); true } else false }
        }
        box.addView(input,LinearLayout.LayoutParams(0,-1,1f))
        mic=icon("mic","语音输入") { toggleRecording() }.apply { tone=GlyphButton.Tone.QUIET }
        box.addView(mic,LinearLayout.LayoutParams(metrics.chatButton,metrics.chatInputHeight))
        send=icon("send","发送消息") { if(replyInProgress())interruptReply() else sendMessage() }.apply { tone=GlyphButton.Tone.ACCENT;tint=goldInk }
        box.addView(send,LinearLayout.LayoutParams(metrics.chatButton,metrics.chatInputHeight))
        composer.addView(box,LinearLayout.LayoutParams(metrics.chatComposerWidth,metrics.chatInputHeight))
        createContextControls()
        composer.addView(modelText,LinearLayout.LayoutParams(metrics.modelWidth,metrics.modelTouchHeight).apply { topMargin=metrics.chatComposerGap })
        main.addView(composer,LinearLayout.LayoutParams(-1,metrics.chatComposerHeight))
        chatPage=main
        root.addView(main,FrameLayout.LayoutParams(-1,-1))
        root.findViewWithTag<View>("scroll-feedback")?.bringToFront()
        clock=text("",8f,muted).apply {
            gravity=Gravity.CENTER
            contentDescription="查看余量"
            setOnClickListener { openUsageShortcut() }
        }
        root.addView(clock,FrameLayout.LayoutParams(dp(60),dp(14),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(8) })
        latest=action("↓ 最新消息") { chatScroll.scrollToEnd(); latest.visibility=View.GONE }.apply { textSize=9f; minHeight=0; visibility=View.GONE }
        root.addView(latest,FrameLayout.LayoutParams(dp(82),dp(26),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=metrics.chatBottom+metrics.chatComposerHeight+dp(3) })
        chatScroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int -> if(atBottom())latest.visibility=View.GONE }
        setContentView(root)
        root.requestFocus()
        main.addOnLayoutChangeListener { _,_,_,_,bottom,_,_,_,oldBottom -> if(bottom!=oldBottom && atBottom())chatScroll.post { chatScroll.scrollToEnd() } }
    }

    private fun canAnimate() = resumed && backMotion.enabled(root)

    private fun mascot(resource: Int) = ImageView(this).apply {
        setImageBitmap(WhaleTheme.bitmap(this@MainActivity, resource))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        tag = "whale-mascot"
        visibility = if (prefs.getBoolean("ui.quietBackground", false)) View.GONE else View.VISIBLE
    }

    private fun mascotCard(resource: Int, heading: String, caption: String, size: Int) = row().apply {
        setPadding(dp(4), dp(3), dp(4), dp(5))
        addView(mascot(resource), LinearLayout.LayoutParams(dp(size), dp(size)).apply { rightMargin = dp(7) })
        addView(column().apply {
            addView(text(heading, 13f, ink, true))
            addView(text(caption, 10f, muted).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (canAnimate()) {
            val portrait = getChildAt(0)
            portrait.alpha = .55f
            portrait.translationY = dp(5).toFloat()
            portrait.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
    }

    private fun updateTideMotion(status: String) {
        val cue = when {
            !canAnimate() || mode != "chat" -> null
            status == "录音中" -> TideMotion.Cue.RECORD
            status == "待确认" -> TideMotion.Cue.CONFIRM
            status == "回复中" -> TideMotion.Cue.REPLY
            (state.optJSONArray("messages")?.length() ?: 0) == 0 -> TideMotion.Cue.WELCOME
            else -> null
        }
        tideMotion.play(cue)
    }

    private fun atBottom() = chat.height-chatScroll.scrollY-chatScroll.height < dp(8)
    private fun updateClock() {
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        if (minute == displayedMinute) return
        displayedMinute = minute
        clock.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(now))
    }

    private fun fetch() {
        val bridge = client ?: return
        if (!resumed || fetching) return
        ui.removeCallbacks(poll)
        val selected = sessionId
        val baseline = state
        val etag = stateEtag.takeIf { selected.isNotEmpty() && lastPolledState === baseline }
        fetching = true
        work {
            try {
                val snapshot = bridge.readState(selected, etag)
                val id = snapshot.value?.getString("id")
                postResult {
                    fetching = false
                    val recovering = pollIssue.isNotEmpty()
                    pollFailures = 0
                    pollIssue = ""
                    if (selected == sessionId && state === baseline && snapshot.value != null && id != null) {
                        state = snapshot.value
                        lastPolledState = state
                        stateEtag = snapshot.etag
                        sessionId = id
                        if (selected != id) computers.putState("sessionId", id)
                        updateMessages()
                        updateControls()
                    } else if (recovering) {
                        updateControls()
                    } else {
                        updateClock()
                    }
                    schedulePoll()
                }
            } catch (error: Exception) {
                postResult {
                    fetching = false
                    if (selected == sessionId) {
                        pollFailures = (pollFailures + 1).coerceAtMost(6)
                        val issue = when {
                            (error as? BridgeHttpError)?.status == 401 -> "授权已失效"
                            error is javax.net.ssl.SSLPeerUnverifiedException ||
                                error is javax.net.ssl.SSLHandshakeException -> "电脑证书校验失败"
                            error is BridgeHttpError -> "电脑暂未同步"
                            else -> "电脑连接暂不可用"
                        }
                        if (pollFailures >= 2 || !state.optBoolean("connected") ||
                            issue == "授权已失效" || issue == "电脑证书校验失败") {
                            pollIssue = issue
                            updateControls()
                        }
                    }
                    schedulePoll()
                }
            }
        }
    }

    private fun updateControls() {
        if(!::send.isInitialized)return
        val connected = client != null && state.optBoolean("connected") && pollFailures < 2 && pollIssue != "授权已失效" && pollIssue != "电脑证书校验失败"
        val busy=state.optBoolean("busy")
        titleText.text=(if(state.isNull("title"))"新对话" else state.optString("title","新对话")).removePrefix("手表 · ").ifEmpty { "新对话" }
        updateClock()
        statusText.text=when { recording!=null -> "录音中"; voiceBusy -> "识别中"; client == null -> "未连接"; pollIssue == "授权已失效" -> "需重新配对"; pollIssue == "电脑证书校验失败" -> "证书异常"; pollIssue == "电脑暂未同步" -> "同步重试"; pollIssue.isNotEmpty() -> "重连中"; !connected -> "连接中"; controlling -> "同步中"; state.optBoolean("waitingOnApproval") -> "待确认"; sending -> "发送中"; busy -> "回复中"; else -> "在线" }
        val approvals=state.optJSONArray("approvals") ?: JSONArray()
        headerTitles.isClickable=true
        headerTitles.contentDescription="查看对话详情"
        if(mode=="approval" && openApprovalToken.isNotEmpty()) {
            val pending=(0 until approvals.length()).any { approvals.getJSONObject(it).optString("token")==openApprovalToken }
            approvalStateLabel?.text=if(!pending)"已处理或已结束" else if(controlling)"正在提交，请稍候…" else "选择后会同步到电脑"
            approvalActions.forEach { it.isEnabled=pending && !controlling; it.alpha=if(it.isEnabled)1f else .4f }
        }
        val status = statusText.text.toString()
        statusText.contentDescription = "$status，查看余量"
        statusText.setTextColor(when (status) {
            "录音中" -> WhaleTheme.ALERT
            "待确认" -> WhaleTheme.WAITING
            "在线" -> WhaleTheme.RUNNING
            else -> muted
        })
        updateTideMotion(status)
        val pose = if (prefs.getBoolean("ui.quietBackground", false)) 0 else when (status) {
            "录音中", "识别中" -> R.drawable.whale_listen
            "待确认" -> R.drawable.whale_confirm
            "回复中", "发送中" -> R.drawable.whale_log
            else -> 0
        }
        if (statusText.tag != pose) {
            statusText.tag = pose
            val badge = if (pose == 0) GlyphDrawable("rice", accent)
                .apply { setBounds(0, 0, dp(11), dp(11)) }
            else android.graphics.drawable.BitmapDrawable(resources, WhaleTheme.bitmap(this, pose))
                .apply { setBounds(0, 0, dp(14), dp(14)) }
            statusText.setCompoundDrawables(badge, null, null, null)
            statusText.compoundDrawablePadding = dp(3)
        }
        if (animatedStatus != status) {
            animatedStatus = status
            statusText.animate().cancel()
            statusText.alpha = 1f
            if (canAnimate() && mode == "chat") {
                statusText.alpha = .45f
                statusText.animate().alpha(1f).setDuration(200).start()
            }
        }
        val error = localError.ifEmpty { if (pollIssue.isNotEmpty()) pollIssue else state.optString("error") }
        banner.text=error; banner.visibility=if(error.isEmpty())View.GONE else View.VISIBLE
        mic.glyph=if(recording!=null)"stop" else "mic"; mic.tint=if(recording!=null)WhaleTheme.ALERT else ink
        mic.contentDescription=if(recording!=null)"结束录音" else "语音输入"
        mic.isEnabled=connected && !voiceBusy && !sending; mic.invalidate()
        input.isEnabled=recording==null && !voiceBusy
        input.hint=if(recording!=null)"正在聆听…" else if(voiceBusy)"识别中…" else "发消息…"
        val stopping=replyInProgress()
        send.glyph=if(stopping)"stop" else "send"
        send.isEnabled=if(stopping)connected && state.optBoolean("canInterrupt") && !controlling && !sending
            else connected && input.text.toString().isNotBlank() && !sending && recording==null && !voiceBusy && !state.optBoolean("archived")
        send.contentDescription=if(stopping)"停止本次回复" else "发送消息"
        send.alpha=if(send.isEnabled)1f else .42f
        send.invalidate()
        val settings=state.optJSONObject("settings") ?: JSONObject()
        val modelName=settings.optString("modelName").ifBlank { "跟随电脑" }
        val thinking=settings.optString("thinking").let { if(it.isBlank())"默认" else effortName(it) }
        modelText.text=modelName.removePrefix("gpt-")+" · $thinking ⌄"
        modelText.contentDescription="模型与思考强度：$modelName · $thinking"
        modelText.isEnabled=connected;modelText.alpha=if(connected)1f else .5f
        val projectName=if(state.isNull("projectName"))"" else state.optString("projectName")
        projectText.text=if(projectName.isNotBlank())projectName+(if(currentThreadId().isBlank())" ⌄" else "") else if(currentThreadId().isBlank())"不选项目 ⌄" else "其他聊天"
        projectText.contentDescription="当前项目："+(projectName.ifBlank { "不选项目" })
        projectText.isEnabled=client!=null && !sending
        syncScreenAwake()
        if(resumed && connected && mode=="chat" && !replyInProgress() && recording==null && !voiceBusy && prefs.getBoolean("autoRead",false)) {
            val messages=state.optJSONArray("messages") ?: JSONArray()
            val reply=(messages.length()-1 downTo 0).map { messages.getJSONObject(it) }.firstOrNull { it.optString("role")=="assistant" && it.optString("phase")!="commentary" }
            val id=reply?.optString("id") ?: ""
            if(id.isNotEmpty() && id!=lastSpoken) { lastSpoken=id; speak(id) }
        }
        maybeShowApproval()
    }

    private fun replyInProgress()=state.optBoolean("busy") || state.optBoolean("canInterrupt")
    private fun maybeShowApproval() {
        val items=state.optJSONArray("approvals") ?: return
        val tokens=(0 until items.length()).map { items.getJSONObject(it).optString("token") }.toSet()
        shownApprovals.retainAll(tokens)
        if(!resumed || overlay!=null || backMotion.busy || approvalPopupQueued || recording!=null || voiceBusy || sending || controlling)return
        val candidate=(0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.optString("token").isNotEmpty() && it.optString("token") !in shownApprovals } ?: return
        val selected=sessionId; val token=candidate.getString("token")
        approvalPopupQueued=true
        postResult {
            approvalPopupQueued=false
            val pending=state.optJSONArray("approvals") ?: JSONArray()
            val item=(0 until pending.length()).map { pending.getJSONObject(it) }.firstOrNull { it.optString("token")==token }
            if(selected==sessionId && overlay==null && item!=null && recording==null && !voiceBusy) {
                shownApprovals.add(token); approvalPage(item)
            }
        }
    }

    private fun updateMessages(force: Boolean=false, older: Boolean=false) {
        val messages=state.optJSONArray("messages") ?: JSONArray()
        val signature=messages.toString()+state.optBoolean("hasOlder")
        val changedSession=lastSession!=sessionId
        if(!force && !changedSession && signature==lastChat)return
        val interacting=chatScroll.interacting || backMotion.busy
        val follow=changedSession || (atBottom() && !interacting)
        val oldY=chatScroll.scrollY; val oldHeight=chat.height
        val ids=(0 until messages.length()).map { messages.getJSONObject(it).getString("id") }
        val existing=bodies.keys.toList()
        val appendOnly=!force && !changedSession && existing.size<=ids.size && existing==ids.take(existing.size) && existing.isNotEmpty()
        if(!appendOnly) {
            chat.removeAllViews(); bodies.clear()
            if(state.optBoolean("hasOlder"))chat.addView(action("加载更早的消息") { loadOlder() })
        }
        if(messages.length()==0) {
            val welcome = column().apply {
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = shape(0xB5132C57.toInt(), 13)
                addView(text("你好，欢迎回来", 14f, ink, true).apply { setPadding(0, dp(3), 0, dp(3)) })
                addView(text("从手表继续 DSH 对话", 11f, goldInk, true))
                addView(text("点麦克风来聊天", 10f, muted).apply { setPadding(0, dp(3), 0, 0) })
            }
            chat.addView(welcome, LinearLayout.LayoutParams(dp(114), -2).apply {
                leftMargin = dp(8)
                topMargin = dp(17)
            })
        }
        for(i in 0 until messages.length()) {
            val message=messages.getJSONObject(i); val id=message.getString("id")
            if(bodies[id]==null) {
                val user=message.getString("role")=="user"
                val card=column().apply {
                    setPadding(dp(10),dp(8),dp(10),dp(8))
                    background=shape(if(user)WhaleTheme.SELECTED else surface,16)
                    layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6); if(user)leftMargin=dp(26) }
                    setOnLongClickListener { messageMenu(id); true }
                }
                val byline = row().apply { setPadding(0, 0, 0, dp(4)) }
                if (!user) byline.addView(mascot(R.drawable.whale_log), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(5) })
                byline.addView(text(if (user) "你" else "DSH 助手", 9f, if (user) muted else accent, true))
                card.addView(byline)
                val body=text("",prefs.getInt("fontSize",13).toFloat()).apply {
                    setLineSpacing(dp(2).toFloat(),1f)
                    setOnLongClickListener { messageMenu(id); true }
                }
                card.addView(body,LinearLayout.LayoutParams(-1,-2)); bodies[id]=body; chat.addView(card)
            }
            val raw=message.optString("text")
            val body=bodies[id]!!
            if(body.tag!=raw) { body.text=ChatText.format(raw); body.tag=raw }
        }
        lastChat=signature; lastSession=sessionId
        val renderedSession=sessionId
        chatScroll.post {
            if(renderedSession!=sessionId || signature!=lastChat)return@post
            when {
                changedSession -> chatScroll.scrollToEnd()
                older -> chatScroll.scrollTo(0,oldY+(chat.height-oldHeight))
                follow && !chatScroll.interacting && !backMotion.busy -> chatScroll.scrollToEnd()
                !appendOnly && !chatScroll.interacting -> chatScroll.scrollTo(0,oldY)
            }
            if(!force && !atBottom())latest.visibility=View.VISIBLE
        }
        val lastAssistant=(messages.length()-1 downTo 0).map { messages.getJSONObject(it) }.firstOrNull { it.optString("role")=="assistant" }
        val voiceId=lastAssistant?.optString("id") ?: ""
        if(changedSession)lastSpoken=voiceId
    }

    private fun loadOlder() {
        val selected=sessionId
        work { val value=requestClient().json("/api/history/older",JSONObject().put("sessionId",selected)); postResult { if(selected==sessionId) { state=value; updateMessages(true,true) } } }
    }
    private fun setSession(value: JSONObject) {
        saveDraft(); recording?.stop(); recording=null; releasePlayback()
        sessionId=value.getString("id"); state=value; localError=""; lastSession=""; lastSpoken=""; shownApprovals.clear()
        computers.putState("sessionId",sessionId); restoreDraft(); closeOverlay(); updateMessages(true); updateControls(); fetch()
    }
    private fun newChat() {
        projectPicker(true)
    }
    private fun projectPicker(create: Boolean) {
        projectPickerCreatesChat=create
        val selected=sessionId
        val page=contentPage("projects","选择项目")
        val picker=overlay
        val generation=++projectRequest
        val list=contentScroll(page).getChildAt(0) as LinearLayout
        val refresh=list.findViewWithTag<TextView>("projects-refresh")
        if(refresh==null) {
            list.removeAllViews()
            list.addView(text("正在读取电脑项目…",11f,muted))
        } else {
            refresh.text="正在刷新…"
            for(i in 0 until list.childCount)list.getChildAt(i).isEnabled=false
        }
        fun current()=overlay===picker && generation==projectRequest
        work {
            val value=try { requestClient().json("/api/projects") } catch(error:Exception) {
                postResult {
                    if(current() && refresh!=null) {
                        refresh.text="重试刷新"
                        for(i in 0 until list.childCount)list.getChildAt(i).isEnabled=true
                        toast(error.message ?: "项目读取失败")
                    } else if(current()) {
                        list.removeAllViews()
                        list.addView(text(error.message ?: "项目读取失败",11f,muted))
                        list.addView(action("重新读取项目") { projectPicker(create) })
                    }
                }
                return@work
            }
            postResult {
                if(!current())return@postResult
                list.removeAllViews()
                list.addView(text("发送第一条消息后创建电脑会话",10f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(6),0,dp(5)) })
                var choosing=false
                fun option(id:String?,name:String) {
                    val selectedProject=!create && (if(state.isNull("projectId"))null else state.optString("projectId"))==id
                    val button=action((if(selectedProject)"✓ " else "")+name) {
                        if(!choosing && overlay===picker && selected==sessionId) {
                            choosing=true
                            work {
                                try {
                                    val body=JSONObject().put("projectId",id ?: JSONObject.NULL)
                                    if(!create)body.put("sessionId",selected)
                                    val result=requestClient().json(if(create)"/api/session/new" else "/api/session/project",body)
                                    postResult {
                                        choosing=false
                                        if(overlay===picker && selected==sessionId) {
                                            if(create)setSession(result.getJSONObject("state"))
                                            else { state=result; closeOverlay(); updateControls() }
                                        }
                                    }
                                } catch(error:Exception) { postResult { choosing=false; if(overlay===picker)toast(error.message ?: "选择失败，请重试") } }
                            }
                        }
                    }
                    button.apply {
                        gravity=Gravity.CENTER_VERTICAL or Gravity.START
                        minHeight=dp(42);setPadding(dp(12),dp(8),dp(12),dp(8))
                        mark(if(id==null)"quote" else "folder",accent,17)
                        isSelected=selectedProject
                        if(selectedProject)background=pressable(WhaleTheme.SELECTED)
                    }
                    list.addView(button)
                }
                val projects=value.getJSONArray("projects")
                for(i in 0 until projects.length()) { val item=projects.getJSONObject(i); option(item.getString("id"),item.getString("name")) }
                option(null,"不选项目")
                list.addView(action("刷新项目") { if(!choosing)projectPicker(create) }.apply { tag="projects-refresh" })
            }
        }
    }
    private fun linkChat(threadId: String) {
        hideKeyboard()
        val source=overlay
        val generation=++chatLinkRequest
        work {
            val value=requestClient().json("/api/session/link",JSONObject().put("threadId",threadId))
            postResult { if(overlay===source && generation==chatLinkRequest)setSession(value) }
        }
    }

    private fun sendMessage() {
        val bridge=client ?: return
        val value=input.text.toString().trim()
        if(value.isEmpty() || sending || recording!=null || voiceBusy || replyInProgress())return
        if(value.length>8000) { toast("消息太长，请分段发送"); return }
        val selected=sessionId
        val key=if(computers.getState("pendingText:$selected")==value)computers.getState("pendingId:$selected").ifEmpty { UUID.randomUUID().toString() } else UUID.randomUUID().toString()
        prefs.edit().putString(computers.stateKey("pendingText:$selected"),value).putString(computers.stateKey("pendingId:$selected"),key).apply()
        sending=true; localError=""; updateControls(); hideKeyboard()
        work {
            val result=bridge.json("/api/message",JSONObject().put("sessionId",selected).put("text",value).put("requestId",key).put("allowFollowup",true).put("timeZone",java.time.ZoneId.systemDefault().id))
            postResult {
                prefs.edit().remove(computers.stateKey("pendingText:$selected")).remove(computers.stateKey("pendingId:$selected")).apply(); sending=false
                if(selected==sessionId) {
                    if(input.text.toString().trim()==value)input.setText("")
                    state=result; localError=""; updateMessages(); updateControls(); chatScroll.post { chatScroll.scrollToEnd() }
                }
                schedulePoll()
            }
        }
    }

    private fun detachPanel() {
        if(backMotion.busy)backMotion.cancel()
        stopConnectionTasks()
        restartLanScan=null
        pauseLanScan=null
        ui.removeCallbacks(searchLater); ui.removeCallbacks(drawerStatusLater); drawerRequest++
        approvalDialog?.dismiss(); approvalDialog=null
        overlay?.let { it.animate().cancel();it.alpha=1f;it.translationX=0f;it.translationY=0f; (it.parent as? ViewGroup)?.removeView(it) }
        overlay=null; drawerRows=null; drawerScroll=null; search=null; mode="chat"
        exposeChatAccessibility(true)
        openApprovalToken=""; approvalStateLabel=null; approvalActions.clear()
        activeScroll=if(::chatScroll.isInitialized)chatScroll else null
    }
    private fun closeOverlay() { detachPanel(); panelHistory.clear(); updateTideMotion(statusText.text.toString()) }
    private fun popPanel() {
        detachPanel()
        val previous=panelHistory.pollLast() ?: run { updateTideMotion(statusText.text.toString()); return }
        overlay=previous.view; mode=previous.name; activeScroll=previous.scroll
        drawerScroll=previous.drawerScroll; drawerRows=previous.drawerRows; search=previous.search
        openApprovalToken=previous.approvalToken; approvalStateLabel=previous.approvalLabel
        approvalActions.addAll(previous.approvalButtons)
        detailTarget=previous.detail
        restartLanScan=previous.restartLanScan
        if (previous.view.parent == null) root.addView(previous.view,FrameLayout.LayoutParams(-1,-1))
        exposeChatAccessibility(false)
        if(mode=="approval")showApprovalDialog()
        if(mode=="drawer") { if(drawerLoading)loadDrawer(true) else { drawDrawerRows(); ui.post(drawerStatusLater) } }
        else if(mode=="pair-device")pausedPairTarget?.let { pairingPaused(it) }
        updateControls()
    }
    private fun navigateBack() {
        hideKeyboard();backMotion.back()
    }
    private fun panel(name: String): LinearLayout {
        // A chooser returning to its parent refreshes that page without duplicating it.
        if(name!=mode && panelHistory.peekLast()?.name==name)popPanel()
        val current=overlay
        if(current!=null && mode!=name)panelHistory.addLast(PanelSnapshot(
            current,mode,activeScroll,drawerScroll,drawerRows,search,
            openApprovalToken,approvalStateLabel,approvalActions.toList(),detailTarget,restartLanScan
        ))
        detachPanel(); hideKeyboard(); mode=name; activeScroll=null
        tideMotion.play(null)
        val shade=FrameLayout(this).apply { background=WhaleTheme.backdrop(this@MainActivity,true) }
        val page=PanelLayout(this,watchLayout.pageBottom).apply {
            setPadding(watchLayout.pageSide,watchLayout.top,watchLayout.pageSide,watchLayout.pageBottom); isClickable=true
        }
        shade.addView(page,FrameLayout.LayoutParams(-1,-1))
        root.addView(shade,FrameLayout.LayoutParams(-1,-1)); overlay=shade
        exposeChatAccessibility(false)
        if(canAnimate()) {
            if (name == "usage") shade.translationY = -dp(16).toFloat()
            else shade.translationX = dp(12).toFloat()
            shade.animate().translationX(0f).translationY(0f).setDuration(185).setInterpolator(pageEasing).withLayer().start()
        }

        return page
    }
    private fun exposeChatAccessibility(visible:Boolean) {
        val mode=if(visible)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if(::chatPage.isInitialized)chatPage.importantForAccessibility=mode
        if(::latest.isInitialized)latest.importantForAccessibility=mode
        if(::clock.isInitialized)clock.importantForAccessibility=mode
    }
    private fun headerRow(page:LinearLayout,label:String,left:View,right:View?=null) {
        val top=row()
        top.setPadding(watchLayout.headerInset-watchLayout.pageSide,0,watchLayout.headerInset-watchLayout.pageSide,0)
        top.addView(left,LinearLayout.LayoutParams(watchLayout.button,watchLayout.button))
        top.addView(text(label,13f,ink,true).apply {
            gravity=Gravity.CENTER;maxLines=1;ellipsize=TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(0,-1,1f))
        top.addView(right ?: View(this),LinearLayout.LayoutParams(watchLayout.button,watchLayout.button))
        page.addView(top,LinearLayout.LayoutParams(-1,watchLayout.button))
    }
    private fun pageHeader(page: LinearLayout, label: String) {
        headerRow(page,label,icon("back","返回上一页") { navigateBack() })
    }
    private fun contentPage(name:String,title:String):LinearLayout =
        (if(mode==name)(overlay as? FrameLayout)?.getChildAt(0) as? LinearLayout else null)
            ?: panel(name).also { pageHeader(it,title) }

    private fun contentScroll(page:LinearLayout):WatchScrollView =
        page.getChildAt(1) as? WatchScrollView ?: scrollView().also { scroll ->
            scroll.addView(column())
            page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        }

    private fun openDrawer() {
        val page=panel("drawer")
        headerRow(page,"航海日志",icon("back","关闭历史对话") { closeOverlay() },icon("new","新建对话") { newChat() })
        search=EditText(this).apply {
            setText(drawerQuery); hint="搜索聊天"; textSize=12f; setTextColor(ink); setHintTextColor(muted)
            background=shape(surface,16); setPadding(dp(10),0,dp(8),0); isSingleLine=true; contentDescription="搜索历史对话"
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { drawerQuery=s.toString(); ui.removeCallbacks(searchLater); ui.postDelayed(searchLater,400) }
                override fun afterTextChanged(s:Editable?){}
            })
        }
        val field=search!!
        field.visibility=if(drawerSearchExpanded || drawerQuery.isNotBlank())View.VISIBLE else View.GONE
        val tabs=row().apply { background=shape(surface,14);setPadding(dp(2),dp(2),dp(2),dp(2)) }
        fun updateTab(tab:TextView,selected:Boolean) {
            tab.isSelected=selected
            tab.setTextColor(if(selected)accent else muted)
            tab.background=shape(if(selected)WhaleTheme.SELECTED else Color.TRANSPARENT,12)
        }
        for((key,label) in listOf("all" to "全部","pinned" to "置顶","archived" to "归档")) {
            tabs.addView(text(label,10f,if(drawerTab==key)accent else muted).apply {
                gravity=Gravity.CENTER; contentDescription="会话分类：$label"; isClickable=true
                updateTab(this,drawerTab==key)
                setOnClickListener {
                    if(drawerTab!=key) {
                        drawerTab=key
                        for(i in 0 until tabs.childCount)updateTab(tabs.getChildAt(i) as TextView,tabs.getChildAt(i)===this)
                        loadDrawer(true)
                    }
                }
            },LinearLayout.LayoutParams(0,dp(28),1f))
        }
        val tools=row()
        tools.addView(tabs,LinearLayout.LayoutParams(0,dp(32),1f))
        val searchButton=icon(if(field.visibility==View.VISIBLE)"close" else "search",if(field.visibility==View.VISIBLE)"清除并关闭搜索" else "搜索聊天") {}
        searchButton.setOnClickListener {
            drawerSearchExpanded=field.visibility!=View.VISIBLE
            field.visibility=if(drawerSearchExpanded)View.VISIBLE else View.GONE
            searchButton.glyph=if(drawerSearchExpanded)"close" else "search"
            searchButton.contentDescription=if(drawerSearchExpanded)"清除并关闭搜索" else "搜索聊天";searchButton.invalidate()
            if(drawerSearchExpanded) {
                field.requestFocus();field.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT) }
            } else { field.setText("");hideKeyboard() }
        }
        tools.addView(searchButton,LinearLayout.LayoutParams(watchLayout.button,watchLayout.button).apply { leftMargin=dp(4) })
        page.addView(tools,LinearLayout.LayoutParams(-1,watchLayout.button).apply { topMargin=dp(4) })
        page.addView(field,LinearLayout.LayoutParams(-1,dp(30)).apply { bottomMargin=dp(4) })
        drawerScroll=scrollView(); drawerRows=column(); drawerScroll!!.addView(drawerRows)
        page.addView(drawerScroll,LinearLayout.LayoutParams(-1,0,1f))
        drawerItems=JSONArray(); drawerCursor=null; loadDrawer(true,locateCurrent=true)
    }
    private fun loadDrawer(reset:Boolean,keepPosition:Boolean=false,locateCurrent:Boolean=false) {
        val query=drawerQuery; val tab=drawerTab; val generation=++drawerRequest
        ui.removeCallbacks(drawerStatusLater)
        if(reset && !keepPosition) {
            drawerItems=JSONArray();drawerCursor=null
            drawerScroll?.scrollTo(0,0)
        }
        drawerError=""
        drawerLoading=true
        if(!keepPosition || drawerItems.length()==0)drawDrawerRows()
        val cursor=if(reset)null else drawerCursor
        val anchor=if(locateCurrent && query.isBlank() && sessionId.isNotBlank()) "&currentSessionId="+URLEncoder.encode(sessionId,"UTF-8") else ""
        work {
            try {
                val path="/api/conversations?tab=$tab&query="+URLEncoder.encode(query,"UTF-8")+(if(cursor==null)"" else "&cursor="+URLEncoder.encode(cursor,"UTF-8"))+anchor
                val value=requestClient().json(path)
                val items=value.getJSONArray("conversations")
                postResult {
                    if(mode!="drawer" || generation!=drawerRequest)return@postResult
                    if(reset)drawerItems=JSONArray()
                    drawerProjects=value.optJSONArray("projects") ?: JSONArray()
                    val seen=(0 until drawerItems.length()).mapTo(mutableSetOf()) { drawerItems.getJSONObject(it).getString("threadId") }
                    for(i in 0 until items.length()) {
                        val item=items.getJSONObject(i)
                        if(seen.add(item.getString("threadId")))drawerItems.put(item)
                    }
                    drawerCursor=if(value.isNull("nextCursor"))null else value.optString("nextCursor").ifEmpty { null }
                    drawerLoading=false; drawDrawerRows()
                    if(locateCurrent && query.isBlank())locateCurrentDrawerRow()
                    ui.postDelayed(drawerStatusLater,5000)
                }
            } catch(error:Exception) {
                postResult {
                    if(mode!="drawer" || generation!=drawerRequest)return@postResult
                    drawerLoading=false
                    drawerError=error.message?.take(100) ?: "无法读取聊天"
                    drawDrawerRows()
                }
            }
        }
    }
    private fun statusColor(item:JSONObject)=when(item.optString("status")) {
        "running"->WhaleTheme.RUNNING; "waiting"->WhaleTheme.WAITING; "unread"->WhaleTheme.UNREAD; else->muted
    }
    private fun setStatusLabel(label:TextView,item:JSONObject) {
        label.text=item.optString("statusLabel","状态未知")+(if(item.optBoolean("unread") && item.optString("status") in listOf("running","waiting"))" · 有新回复" else "")
        label.setTextColor(statusColor(item));label.background=shape((statusColor(item) and 0x00FFFFFF) or 0x20000000,8);label.contentDescription="会话状态：${label.text}"
    }
    private fun refreshDrawerStatuses() {
        if(!resumed || mode!="drawer" || drawerLoading)return
        val generation=drawerRequest; val drawer=overlay
        val ids=JSONArray()
        for(i in 0 until minOf(drawerItems.length(),1000))ids.put(drawerItems.getJSONObject(i).getString("threadId"))
        if(ids.length()==0)return
        work {
            try {
                val result=requestClient().json("/api/conversations/status",JSONObject().put("threadIds",ids)).getJSONObject("states")
                postResult {
                    if(mode!="drawer" || overlay!==drawer || generation!=drawerRequest)return@postResult
                    for(i in 0 until drawerItems.length()) {
                        val item=drawerItems.getJSONObject(i);val id=item.getString("threadId");val update=result.optJSONObject(id) ?: continue
                        for(key in listOf("status","statusLabel","busy","unread"))item.put(key,update.opt(key))
                        drawerStatusLabels[id]?.let { setStatusLabel(it,item) }
                    }
                    ui.postDelayed(drawerStatusLater,5000)
                }
            } catch(error:Exception) {
                postResult {
                    if(mode=="drawer" && overlay===drawer && generation==drawerRequest) {
                        drawerStatusLabels.values.forEach { it.text="状态暂不可刷新"; it.setTextColor(muted) }
                        ui.postDelayed(drawerStatusLater,10000)
                    }
                }
            }
        }
    }
    private fun locateCurrentDrawerRow() {
        val scroll=drawerScroll ?: return
        val current=drawerRows?.findViewWithTag<View>("drawer-current") ?: return
        scroll.centerOnLayout(current)
    }
    private fun drawDrawerRows() {
        val list=drawerRows ?: return
        val compact=prefs.getBoolean("ui.compactList",false)
        list.removeAllViews();drawerStatusLabels.clear()
        val groups=linkedMapOf<String,MutableList<JSONObject>>()
        val names=mutableMapOf<String,String>()
        for(i in 0 until drawerProjects.length()) {
            val project=drawerProjects.getJSONObject(i);val id=project.getString("id")
            groups[id]=mutableListOf();names[id]=project.getString("name")
        }
        for(i in 0 until drawerItems.length()) {
            val item=drawerItems.getJSONObject(i)
            val key=if(item.isNull("projectId"))"" else item.optString("projectId")
            groups.getOrPut(key) { mutableListOf() }.add(item)
            names.putIfAbsent(key,if(key.isEmpty())"其他聊天" else item.optString("projectName","未识别项目"))
        }
        val currentId=currentThreadId()
        for((key,items) in groups) {
            if(items.isEmpty())continue
            val name=names[key] ?: "其他聊天"
            list.addView(text("$name · ${items.size}",12f,accent,true).apply { mark("folder",accent,13);setPadding(dp(4),dp(10),0,dp(6));contentDescription="项目分组：$name" })
            val expanded=expandedProjects.contains(key) || drawerQuery.isNotEmpty()
            val collapsed=items.filterIndexed { index,item -> index<3 || item.getString("threadId")==currentId }
            for(item in if(expanded)items else collapsed) {
                val id=item.getString("threadId");val title=item.getString("title")
                val current=id==currentId
                val itemRow=row().apply {
                    background=pressable(if(current)WhaleTheme.SELECTED else surface,12)
                    setPadding(dp(7),dp(if(compact)3 else 5),0,dp(if(compact)3 else 5))
                    contentDescription="打开对话：$title"; isSelected=current; isFocusable=true
                    if(current) { tag="drawer-current";stateDescription="当前会话" }
                    setOnClickListener {
                        drawerScroll?.selectionFeedback()
                        if(current)closeOverlay() else linkChat(id)
                    }
                    setOnLongClickListener { showConversationDetail(DetailTarget(id,JSONObject(item.toString()))); true }
                }
                val content=column().apply {
                    minimumHeight=dp(if(compact)34 else 44); gravity=Gravity.CENTER_VERTICAL
                }
                content.addView(text((if(item.optBoolean("pinned"))"★ " else "")+title,12f).apply { maxLines=if(compact)1 else 2;ellipsize=TextUtils.TruncateAt.END })
                val status=text("",9f,muted).apply { setPadding(dp(6),dp(2),dp(6),dp(2)) }
                setStatusLabel(status,item);drawerStatusLabels[id]=status;content.addView(status,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(4) })
                itemRow.addView(content,LinearLayout.LayoutParams(0,-2,1f))
                itemRow.addView(icon("more","对话详情：$title") { showConversationDetail(DetailTarget(id,JSONObject(item.toString()))) },LinearLayout.LayoutParams(dp(36),dp(42)))
                list.addView(itemRow,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(3) })
            }
            val hiddenCount=items.size-collapsed.size
            if(hiddenCount>0 && drawerQuery.isEmpty())list.addView(action(if(expanded)"收起 $name" else "展开 $name（$hiddenCount）") {
                if(!expandedProjects.add(key))expandedProjects.remove(key)
                drawDrawerRows()
            })
        }
        if(drawerLoading)list.addView(text("正在读取…",11f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(14),0,0) })
        else if(drawerError.isNotEmpty()) {
            list.addView(text(drawerError,11f,muted))
            list.addView(action("重新读取聊天") { loadDrawer(true,keepPosition=true) })
        } else if(drawerItems.length()==0)list.addView(mascotCard(R.drawable.whale_log, "没有找到聊天", "可以换个关键词，或新建对话。", 47))
        if(!drawerLoading && drawerError.isEmpty() && drawerCursor!=null)list.addView(action("加载更多") { loadDrawer(false) })
    }

    private fun createContextControls() {
        projectText=action("") { if(currentThreadId().isBlank())projectPicker(false) else toast("当前项目："+state.optString("projectName","其他聊天")) }.apply {
            mark("folder");maxLines=2;ellipsize=TextUtils.TruncateAt.END
        }
        modelText=text("跟随电脑 · 默认 ⌄",10f,muted).apply {
            gravity=Gravity.CENTER;maxLines=1;ellipsize=TextUtils.TruncateAt.END;contentDescription="模型与思考强度"
            isClickable=true;isFocusable=true;setOnClickListener { openModels() }
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(WhaleTheme.RIPPLE),null,shape(Color.WHITE,18))
        }
    }
    private fun conversationInfo() {
        showConversationDetail(DetailTarget(currentThreadId(),JSONObject(state.toString())))
    }
    private fun showConversationDetail(target:DetailTarget) {
        val page=panel("context");detailTarget=target;pageHeader(page,"对话详情")
        val scroll=scrollView();val list=column()
        val data=target.data;val current=target.id==currentThreadId()
        list.addView(text(data.optString("title","新对话"),14f,ink,true).apply { setPadding(dp(4),dp(8),dp(4),dp(8)) })
        list.addView(text((computers.active()?.name ?: "未连接电脑")+" · "+if(current)statusText.text else data.optString("statusLabel","历史对话"),10f,muted).apply { setPadding(dp(4),0,dp(4),dp(6)) })
        if(current && (state.optJSONArray("approvals")?.length() ?: 0)>0)list.addView(action("待确认操作",true) { approvalList() })
        if(current && target.id.isEmpty()) {
            (projectText.parent as? ViewGroup)?.removeView(projectText);list.addView(projectText)
        } else list.addView(text("项目："+data.optString("projectName").ifBlank { "其他聊天" },11f,muted).apply { setPadding(dp(4),dp(4),dp(4),dp(8)) })
        if(target.id.isNotEmpty()) {
            list.addView(action("重命名") { renamePage(target.id,data.optString("title","")) })
            list.addView(action(if(data.optBoolean("pinned"))"取消置顶" else "置顶") { manage(target.id,if(data.optBoolean("pinned"))"unpin" else "pin") })
            list.addView(action(if(data.optBoolean("archived"))"恢复对话" else "归档对话") {
                if(data.optBoolean("archived"))manage(target.id,"restore") else {
                    val confirm=panel("confirm");pageHeader(confirm,"归档对话")
                    confirm.addView(text("归档“${data.optString("title") }”？聊天记录和草稿会保留。",12f,muted))
                    confirm.addView(action("归档",true) { manage(target.id,"archive") })
                    confirm.addView(action("取消") { navigateBack() })
                }
            })
            list.addView(action("在电脑打开") { work { requestClient().json("/api/conversation/open",JSONObject().put("threadId",target.id));postResult { toast("已在电脑打开") } } })
        }
        val error = localError.ifEmpty { if (pollIssue.isNotEmpty()) pollIssue else state.optString("error") }
        if(error.isNotEmpty())list.addView(text(error,11f,WhaleTheme.WAITING))
        scroll.addView(list);page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));updateControls()
    }
    private fun choices(title: String, labels: List<String>, choose: (Int) -> Unit) {
        val page=panel("menu"); pageHeader(page,title)
        val scroll=scrollView(); val list=column()
        val chooser=overlay
        labels.forEachIndexed { index,label -> list.addView(action(label) {
            choose(index)
            if(overlay===chooser)popPanel()
        }) }
        scroll.addView(list); page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun renamePage(id: String, name: String) {
        val page=panel("rename"); pageHeader(page,"重命名")
        val scroll=scrollView(); val list=column()
        val field=EditText(this).apply {
            setText(name); textSize=12f; setTextColor(ink); setHintTextColor(muted); hint="对话名称"; maxLines=3
            setSelectAllOnFocus(true); contentDescription="对话名称"; background=shape(surface,12); setPadding(dp(10),dp(8),dp(10),dp(8))
            imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        list.addView(field,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        list.addView(action("保存名称",true) {
            val title=field.text.toString().trim()
            if(title.isEmpty())toast("请输入名称") else { hideKeyboard();manage(id,"rename",title) }
        })
        scroll.addView(list); page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun manage(id: String, operation: String, title: String?=null) {
        if(!resumed || managing)return
        val source=overlay
        managing=true
        work {
            try { requestClient().json("/api/conversation/action",JSONObject().put("threadId",id).put("action",operation).put("title",title)) }
            catch(error:Exception) { postResult { managing=false;toast(error.message ?: "操作失败，请重试") };return@work }
            postResult {
                managing=false
                toast(when(operation) { "rename"->"名称已同步到电脑"; "pin"->"已置顶"; "unpin"->"已取消置顶"; "archive"->"已归档"; else->"已恢复" })
                detailTarget?.takeIf { it.id==id }?.let { target ->
                    when(operation) { "rename"->target.data.put("title",title);"pin","unpin"->target.data.put("pinned",operation=="pin");"archive","restore"->target.data.put("archived",operation=="archive") }
                    if(overlay===source) {
                        if(operation=="archive" && id==currentThreadId()) { closeOverlay();openDrawer() }
                        else { if(mode!="context")popPanel();showConversationDetail(target) }
                    }
                }
                fetch();if(mode=="drawer")loadDrawer(true,keepPosition=true)
            }
        }
    }
    private fun messageMenu(id: String) {
        val messages=state.optJSONArray("messages") ?: return
        val message=(0 until messages.length()).map { messages.getJSONObject(it) }.firstOrNull { it.optString("id")==id } ?: return
        val assistant=message.optString("role")=="assistant"
        val choices=if(assistant)arrayOf("复制","朗读回复","引用到输入框","重新提问") else arrayOf("复制","放入输入框")
        choices("消息操作",choices.toList()) { option ->
            when {
                option==0 -> { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("聊天消息",message.getString("text"))); toast("已复制") }
                assistant && option==1 -> speak(id)
                assistant && option==3 -> {
                    val user=(messages.length()-1 downTo 0).map { messages.getJSONObject(it) }.firstOrNull { it.optString("role")=="user" }
                    if(user!=null)input.setText(user.getString("text"))
                }
                else -> { input.setText(if(assistant)"引用："+message.getString("text")+"\n\n" else message.getString("text")); input.setSelection(input.text.length) }
            }
        }
    }

    private fun openModels() {
        modelsSaving=false
        val generation=++modelRequest
        val settings=state.optJSONObject("settings") ?: JSONObject()
        selectedModel=settings.optString("model"); selectedEffort=settings.optString("thinking")
        val page=contentPage("models","模型与思考")
        val list=contentScroll(page).getChildAt(0) as LinearLayout
        list.removeAllViews()
        list.addView(text("正在读取可用模型…",12f,muted))
        page.getChildAt(2)?.visibility=View.GONE
        work {
            try {
                val result=requestClient().json("/api/models")
                postResult { if(mode=="models" && generation==modelRequest) { models=result.getJSONArray("models");drawModels() } }
            } catch(error:Exception) {
                postResult { if(mode=="models" && generation==modelRequest) {
                    list.removeAllViews()
                    list.addView(text(error.message ?: "模型读取失败",12f,muted))
                    list.addView(action("重新读取模型") { openModels() })
                } }
            }
        }
    }
    private fun effortName(value:String) = when(value) { "low"->"轻量"; "medium"->"均衡"; "high"->"深入"; "xhigh"->"更深入"; "max"->"最高"; "ultra"->"极致"; else->value }
    private fun drawModels() {
        val page=contentPage("models","模型与思考")
        val list=contentScroll(page).getChildAt(0) as LinearLayout
        list.removeAllViews()
        fun selectModel(id:String) {
            if(selectedModel==id)return
            selectedModel=id
            selectedEffort=""
            drawModels()
        }
        list.addView(text("选择后，下次发送生效",10f,muted).apply { gravity=Gravity.CENTER })
        if(models.length()==0)list.addView(text("电脑暂未提供可选模型，仍可跟随电脑。",11f,muted))
        list.addView(action((if(selectedModel.isEmpty())"✓ " else "")+"跟随电脑") { selectModel("") })
        for(i in 0 until models.length()) {
            val model=models.getJSONObject(i); val id=model.getString("id")
            list.addView(action((if(selectedModel==id)"✓ " else "")+model.getString("name")) { selectModel(id) })
        }
        val chosen=(0 until models.length()).map { models.getJSONObject(it) }.firstOrNull { it.getString("id")==selectedModel }
        if(selectedModel.isNotBlank() && chosen==null)list.addView(text("当前模型暂不可选，请选择其他模型或跟随电脑。",11f,muted))
        if(chosen!=null) {
            list.addView(text("思考强度",12f,accent,true).apply { setPadding(0,dp(14),0,dp(3)) })
            list.addView(action((if(selectedEffort.isEmpty())"✓ " else "")+"模型默认") { selectedEffort=""; drawModels() })
            val efforts=chosen.getJSONArray("efforts")
            for(i in 0 until efforts.length()) { val value=efforts.getString(i); list.addView(action((if(selectedEffort==value)"✓ " else "")+effortName(value)) { selectedEffort=value; drawModels() }) }
        }
        for(i in 0 until list.childCount)list.getChildAt(i).isEnabled=!modelsSaving
        val save=page.getChildAt(2) as? TextView ?: action("保存",true) {
            if(modelsSaving)return@action
            val selected=sessionId
            val source=overlay
            val model=selectedModel;val effort=selectedEffort
            modelsSaving=true;drawModels()
            work {
                try {
                    val value=requestClient().json("/api/session/settings",JSONObject().put("sessionId",selected).put("model",model).put("thinking",effort))
                    postResult { modelsSaving=false;if(selected==sessionId) { state=value;if(overlay===source)popPanel();updateControls();toast("已保存，下次发送生效") } }
                } catch(error:Exception) { postResult { modelsSaving=false;if(overlay===source && selected==sessionId) { drawModels();toast(error.message ?: "保存失败，请重试") } } }
            }
        }.also { page.addView(it) }
        save.visibility=View.VISIBLE
        save.text=if(modelsSaving)"正在保存…" else "保存"
        save.isEnabled=!modelsSaving
    }

    private fun settingsPage() {
        val page=panel("settings"); pageHeader(page,"设置")
        val scroll=scrollView(); val list=column()
        list.addView(preferenceToggle("autoRead","自动朗读"))
        list.addView(navigation("外观与阅读") { appearancePage() })
        list.addView(navigation("电脑连接") { computersPage() })
        list.addView(navigation("操作与手势") { inputHelp() })
        list.addView(preferenceToggle("ui.haptics","触感反馈",default=true))
        val version=packageManager.getPackageInfo(packageName,0).versionName.orEmpty()
        val name=applicationInfo.loadLabel(packageManager)
        list.addView(text("$name $version".trimEnd(),10f,muted).apply {
            gravity=Gravity.CENTER
            setPadding(0,dp(10),0,0)
        })
        scroll.addView(list); page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun preferenceToggle(
        key:String,label:String,on:String="开启",off:String="关闭",default:Boolean=false,onChange:()->Unit={}
    ):TextView {
        fun title(value:Boolean)="$label：${if(value)on else off}"
        return action(title(prefs.getBoolean(key,default))) {}.apply {
            setOnClickListener {
                val value=!prefs.getBoolean(key,default)
                prefs.edit().putBoolean(key,value).apply()
                text=title(value)
                onChange()
            }
        }
    }
    private fun refreshAppearance() {
        root.background=WhaleTheme.backdrop(this)
        overlay?.background=WhaleTheme.backdrop(this,true)
        panelHistory.forEach { it.view.background=WhaleTheme.backdrop(this,true) }
        val visibility = if (prefs.getBoolean("ui.quietBackground", false)) View.GONE else View.VISIBLE
        fun updateArt(view: View) {
            if (view.tag == "whale-mascot") view.visibility = visibility
            if (view is ViewGroup) for (index in 0 until view.childCount) updateArt(view.getChildAt(index))
        }
        updateArt(root)
        panelHistory.forEach { updateArt(it.view) }
        updateControls()
    }
    private fun appearancePage() {
        val page=panel("appearance");pageHeader(page,"外观与阅读")
        val scroll=scrollView();val list=column()
        list.addView(preferenceToggle("ui.quietBackground","角色插画","隐藏","显示",onChange=::refreshAppearance))
        list.addView(preferenceToggle("ui.deepBackground","表盘色调","深海","潮蓝",onChange=::refreshAppearance))
        list.addView(preferenceToggle("ui.compactList","历史列表","紧凑","舒适"))
        list.addView(preferenceToggle("ui.motion", "界面动效", default = true) { updateTideMotion(statusText.text.toString()) })
        val sizes=listOf(12,13,15)
        val labels=listOf("较小","标准","较大")
        val font=action("字体："+labels[sizes.indexOf(prefs.getInt("fontSize",13)).takeIf { it>=0 } ?: 1]) {}
        font.setOnClickListener {
            choices("聊天字号",labels) { i ->
                prefs.edit().putInt("fontSize",sizes[i]).apply()
                font.text="字体："+labels[i]
                updateMessages(true)
            }
        }
        list.addView(font)
        list.addView(text("设置会保留。关闭界面动效可减少状态切换和页面进入时的动画。",10f,muted).apply { setPadding(dp(4),dp(10),dp(4),dp(8)) })
        scroll.addView(list);page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun inputHelp() {
        val page=panel("input-help");pageHeader(page,"操作与手势")
        val scroll=scrollView();val list=column()
        for((title,description) in listOf(
            "旋转表冠" to "滚动当前聊天、历史列表或面板，无需先点列表。",
            "历史对话" to "只点击左上角菜单打开，横向滑动不会展开。",
            "查看余量" to "从聊天页标题区下拉，或轻点标题下方的饭碗。消息区上下滑动仍然只滚动聊天。",
            "停止与确认" to "回复中发送位变成停止方块，结束后恢复发送。需要选择时自动弹窗，关闭后可从对话详情重新打开。",
            "滑动返回" to "子页面左滑或右滑返回上一页，也可以点左上角返回。聊天首页横滑轻微回弹。",
            "双击聊天区" to "回到最新消息。输入框和操作按钮不触发此手势。",
            "长按消息" to "打开复制、朗读、引用等操作。",
            "表冠按下" to "由手表系统处理，保留返回或应用列表功能。"
        )) { list.addView(text(title,12f,accent,true).apply { setPadding(0,dp(12),0,dp(4)) });list.addView(text(description,11f,muted)) }
        scroll.addView(list);page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun openUsageShortcut() {
        if (recording == null && overlay == null) usagePage()
    }

    private fun usagePage(force: Boolean=false) {
        val page=contentPage("usage","余量")
        val panelView=overlay
        val generation=++usageRequest
        val list=contentScroll(page).getChildAt(0) as LinearLayout
        val refresh=list.findViewWithTag<TextView>("usage-refresh")
        fun addRiceBowl() {
            val card = row().apply {
                background = shape(surface, 14)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            card.addView(View(this).apply {
                background = GlyphDrawable("rice", accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(7) })
            card.addView(column().apply {
                addView(text("账号余量", 13f, ink, true))
                addView(text("DeepSeek 账号", 9f, muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        val loading=text("正在读取余量…",11f,muted).apply { gravity=Gravity.CENTER; setPadding(dp(5),dp(4),dp(5),dp(12)) }
        if(refresh==null) {
            list.removeAllViews()
            addRiceBowl()
            list.addView(loading)
        } else {
            refresh.text="正在刷新…"
            refresh.isEnabled=false
        }
        if (client == null) { loading.text = "连接电脑后查看余量"; return }
        fun percent(value:Double)=if(value%1.0==0.0)value.toInt().toString() else String.format(Locale.CHINA,"%.1f",value)
        fun windowName(minutes:Int)=when { minutes==10080->"每周余量"; minutes==300->"5 小时余量"; minutes>0 && minutes%1440==0->"${minutes/1440} 天余量"; minutes>0 && minutes%60==0->"${minutes/60} 小时余量"; minutes>0->"${minutes} 分钟余量"; else->"用量余量" }
        fun date(value:Long)=SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(Date(value*1000))
        work {
            try {
                val value=requestClient().json("/api/usage"+if(force)"?refresh=1" else "")
                postResult {
                    if(mode!="usage" || overlay!==panelView || generation!=usageRequest)return@postResult
                    list.removeAllViews()
                    addRiceBowl()
                    val error = value.optString("error")
                    if (error.isNotBlank()) {
                        list.addView(text(error,11f,muted).apply { gravity=Gravity.CENTER; setPadding(dp(6),dp(4),dp(6),dp(12)) })
                    }
                    val wallets = value.optJSONArray("wallets") ?: JSONArray()
                    for (i in 0 until wallets.length()) {
                        val wallet = wallets.optJSONObject(i) ?: continue
                        val amount = wallet.optString("amount").toBigDecimalOrNull() ?: continue
                        val currency = runCatching { java.util.Currency.getInstance(wallet.optString("currency")) }.getOrNull() ?: continue
                        val display = java.text.NumberFormat.getCurrencyInstance(Locale.CHINA).apply {
                            this.currency = currency
                            maximumFractionDigits = 6
                        }.format(amount)
                        val card = column().apply { background = shape(surface,14); setPadding(dp(12),dp(8),dp(12),dp(8)) }
                        card.addView(text(if (wallet.optString("kind") == "bonus") "赠送金" else "充值余额",10f,muted))
                        card.addView(text(display,18f,accent,true).apply { setPadding(0,dp(3),0,0) })
                        list.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
                    }
                    val groups=value.optJSONArray("groups") ?: JSONArray()
                    if (error.isBlank() && wallets.length()==0 && groups.length()==0) {
                        list.addView(text("电脑暂未提供余量",11f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(8),0,dp(9)) })
                    }
                    for(i in 0 until groups.length()) {
                        val group=groups.getJSONObject(i)
                        val plan=when(group.optString("planType")) { "prolite"->"Pro Lite"; "pro"->"Pro"; "plus"->"Plus"; "team"->"Team"; "business"->"Business"; "free"->"Free"; else->"" }
                        list.addView(text(group.optString("name","DSH")+(if(plan.isEmpty())"" else " · $plan"),12f,ink,true).apply { setPadding(0,dp(8),0,dp(6)) })
                        val windows=group.optJSONArray("windows") ?: JSONArray()
                        if(windows.length()==0)list.addView(text("暂无余量数据",11f,muted))
                        for(j in 0 until windows.length()) {
                            val window=windows.getJSONObject(j)
                            val card=column().apply { background=shape(surface,14); setPadding(dp(10),dp(10),dp(10),dp(10)) }
                            card.addView(text(windowName(window.optInt("windowDurationMins",0)),10f,muted))
                            card.addView(text(if(window.isNull("remainingPercent"))"余量未知" else "剩余 ${percent(window.getDouble("remainingPercent"))}%",19f,accent,true).apply { setPadding(0,dp(5),0,dp(6)) })
                            if(!window.isNull("usedPercent"))card.addView(text("已用 ${percent(window.getDouble("usedPercent"))}%",10f,muted))
                            if(!window.isNull("resetsAt"))card.addView(text("重置："+date(window.getLong("resetsAt")),10f,ink).apply { setPadding(0,dp(5),0,0) })
                            list.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                        }
                        if(group.optBoolean("limitReached"))list.addView(text("当前额度已用完",11f,WhaleTheme.ALERT))
                        val credits=group.optJSONObject("credits")
                        if(credits!=null)list.addView(text(if(credits.optBoolean("unlimited"))"额外点数：不限量" else if(credits.isNull("balance"))"额外点数：暂无信息" else "额外点数："+credits.optString("balance"),10f,muted).apply { setPadding(0,dp(3),0,dp(4)) })
                    }
                    if(!value.isNull("availableResetCount"))list.addView(text("可用重置次数：${value.optInt("availableResetCount")} 次",10f,muted).apply { setPadding(0,dp(6),0,dp(4)) })
                    val time=runCatching { java.time.Instant.parse(value.optString("updatedAt")).epochSecond }.getOrNull()
                    if(time!=null)list.addView(text("更新："+date(time),9f,muted))
                    list.addView(text("余量与电脑端 DeepSeek 账号同步。",10f,muted).apply { setPadding(0,dp(9),0,dp(4)) })
                    list.addView(action("刷新余量",true) { usagePage(true) }.apply { tag="usage-refresh" })
                }
            } catch(error:Exception) {
                postResult {
                    if(mode!="usage" || overlay!==panelView || generation!=usageRequest)return@postResult
                    val message=error.message ?: "读取余量失败"
                    if(refresh==null) {
                        loading.text=message
                        list.addView(action("重新读取",true) { usagePage(true) })
                    } else {
                        refresh.text="重试刷新"
                        refresh.isEnabled=true
                        toast(message)
                    }
                }
            }
        }
    }
    private fun interruptReply() {
        if(controlling || !state.optBoolean("canInterrupt"))return
        val selected=sessionId; val turn=state.optString("turnId")
        if(turn.isEmpty() || turn=="null") { fetch(); return }
        controlling=true; localError=""; updateControls()
        work {
            val result=requestClient().json("/api/interrupt",JSONObject().put("sessionId",selected).put("turnId",turn))
            postResult { controlling=false; if(selected==sessionId) { state=result.getJSONObject("state"); localError=""; updateMessages(); updateControls(); toast(if(result.optJSONObject("result")?.optBoolean("alreadyFinished")==true)"这次回复已经结束" else "已停止本次回复") } }
        }
    }
    private fun approvalList() {
        val items=state.optJSONArray("approvals") ?: JSONArray()
        if(items.length()==0) { toast("当前没有待确认操作"); fetch(); return }
        if(items.length()==1) { approvalPage(items.getJSONObject(0)); return }
        choices("待确认操作",(0 until items.length()).map { items.getJSONObject(it).optString("title") }) { approvalPage(items.getJSONObject(it)) }
    }
    private fun approvalPage(item: JSONObject) {
        val selected=sessionId; val token=item.getString("token")
        val page=panel("approval"); pageHeader(page,item.optString("title","待确认"))
        openApprovalToken=token
        val scroll=scrollView(); val list=column(); val answers=JSONObject()
        list.addView(mascotCard(R.drawable.whale_confirm, "等你确认", "请查看详情，再选择如何处理。", 47))
        val details=item.optString("details")
        if(details.isNotEmpty())list.addView(text(details,11f).apply { setPadding(0,dp(8),0,dp(12)); setTextIsSelectable(true) })
        val questions=item.optJSONArray("questions") ?: JSONArray()
        for(i in 0 until questions.length()) {
            val question=questions.getJSONObject(i); val id=question.getString("id")
            list.addView(text(question.optString("question",question.optString("header")),12f,ink,true).apply { setPadding(0,dp(10),0,dp(5)) })
            val options=question.optJSONArray("options") ?: JSONArray(); val optionViews=mutableListOf<TextView>()
            var field: EditText?=null
            for(j in 0 until options.length()) {
                val option=options.getJSONObject(j); val label=option.getString("label"); val description=option.optString("description")
                val button=action(label+(if(description.isEmpty())"" else "\n"+description)) {
                    answers.put(id,label); field?.setText("")
                    optionViews.forEachIndexed { index,view -> view.setTextColor(if(index==j)accent else ink); view.background=shape(if(index==j)WhaleTheme.SELECTED else surface,18) }
                }
                optionViews.add(button);list.addView(button)
            }
            if(options.length()==0 || question.optBoolean("isOther")) {
                field=EditText(this).apply {
                    textSize=12f; setTextColor(ink); setHintTextColor(muted); hint=if(options.length()>0)"或填写其他回答" else "填写回答"; contentDescription="回答：$id"
                    background=shape(surface,12); setPadding(dp(9),dp(8),dp(9),dp(8)); minLines=1; maxLines=4
                    imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    if(question.optBoolean("isSecret"))inputType=129
                    else if(question.optString("valueType") in listOf("number","integer"))inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    addTextChangedListener(object:TextWatcher {
                        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { if(!s.isNullOrEmpty()) { answers.put(id,s.toString()); optionViews.forEach { it.setTextColor(ink); it.background=shape(surface,18) } } else if((0 until options.length()).none { options.getJSONObject(it).optString("label")==answers.optString(id) })answers.remove(id) }
                        override fun afterTextChanged(s:Editable?){}
                    })
                }
                list.addView(field,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
            }
        }
        approvalStateLabel=text("选择后会同步到电脑",9f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(10),0,dp(5)) }
        list.addView(approvalStateLabel)
        val actions=item.optJSONArray("actions") ?: JSONArray()
        for(i in 0 until actions.length()) {
            val value=actions.getJSONObject(i); val actionId=value.getString("id")
            val button=action(value.getString("label"),actionId=="accept" || actionId=="answer") {
                if(controlling || selected!=sessionId)return@action
                if(actionId=="answer") {
                    val missing=(0 until questions.length()).map { questions.getJSONObject(it) }.any { q -> q.optBoolean("required",true) && answers.optString(q.getString("id")).isBlank() }
                    if(missing) { toast("请完成所有必填问题"); return@action }
                }
                controlling=true; localError=""; hideKeyboard(); updateControls()
                work {
                    try {
                        val response=requestClient().json("/api/approval",JSONObject().put("sessionId",selected).put("token",token).put("action",actionId).put("answers",if(actionId=="answer")answers else JSONObject.NULL))
                        postResult {
                            controlling=false
                            if(selected==sessionId) { state=response.getJSONObject("state"); localError=""; closeOverlay(); updateMessages(); updateControls(); toast("已同步确认结果") }
                        }
                    } catch(error:Exception) {
                        postResult { controlling=false; localError=error.message?.take(180) ?: "提交失败，请刷新"; updateControls(); toast(localError); fetch() }
                    }
                }
            }
            approvalActions.add(button);list.addView(button)
        }
        scroll.addView(list);page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));updateControls();showApprovalDialog()
    }
    private fun showApprovalDialog() {
        val shade=overlay ?: return
        (shade.parent as? ViewGroup)?.removeView(shade)
        val dialog=object:Dialog(this,R.style.DshWatchTheme) {
            private var startX=0f; private var startY=0f
            private var eligible=false; private var claimed=false
            @Deprecated("Dialog back delegates to the page stack")
            override fun onBackPressed() { navigateBack() }
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                if(window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true)
                    return super.dispatchGenericMotionEvent(event)
                if(event.action==MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER))
                    return this@MainActivity.dispatchGenericMotionEvent(event)
                return super.dispatchGenericMotionEvent(event)
            }
            override fun dispatchTouchEvent(event:MotionEvent):Boolean {
                if((getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled)return super.dispatchTouchEvent(event)
                if(backMotion.settling)return true
                if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                    startX=event.rawX; startY=event.rawY; claimed=false
                    eligible=!onInteractiveControl(shade,event.rawX.toInt(),event.rawY.toInt(),true)
                }
                if(event.pointerCount>1)eligible=false
                val dx=event.rawX-startX; val dy=event.rawY-startY
                if(event.actionMasked==MotionEvent.ACTION_MOVE && eligible && !claimed) {
                    if(kotlin.math.abs(dy)>dp(18) && kotlin.math.abs(dy)>=kotlin.math.abs(dx))eligible=false
                    else if(kotlin.math.abs(dx)>dp(18) && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f) {
                        claimed=true
                        val cancel=MotionEvent.obtain(event).apply { action=MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                    }
                }
                if(claimed) {
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val distance = if (overlay == null) (dx * .16f).coerceIn(-dp(18).toFloat(), dp(18).toFloat()) else dx
                backMotion.drag(distance)
            }
                    if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                        val back=event.actionMasked==MotionEvent.ACTION_UP && eligible && kotlin.math.abs(dx)>=dp(64) && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f
                        claimed=false;backMotion.finish(back, dx)
                    }
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }
        approvalDialog=dialog
        dialog.setContentView(shade); dialog.setCanceledOnTouchOutside(false); dialog.show()
        if (canAnimate() && shade is FrameLayout) {
            val glow = shade.findViewWithTag<TideMotion>("confirmation-glow") ?: TideMotion(this).also {
                it.tag = "confirmation-glow"
                shade.addView(it, FrameLayout.LayoutParams(-1, -1))
            }
            glow.play(TideMotion.Cue.CONFIRM)
        }
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDecorFitsSystemWindows(false);setBackgroundDrawableResource(android.R.color.transparent);setLayout(-1,-1)
            insetsController?.hide(WindowInsets.Type.statusBars())
        }
    }
    private fun computersPage() {
        val page=panel("computers"); pageHeader(page,"电脑连接")
        val scroll=scrollView(); val list=column()
        list.addView(mascotCard(R.drawable.whale_listen, "连接电脑", "找到运行 DSH 的电脑。", 50))
        for(profile in computers.all()) {
            val item=row()
            val host=when (profile.mode) {
                "tunnel" -> "穿透 · "+java.net.URI(profile.url).rawAuthority
                else -> "局域网"
            }
            item.addView(action((if(profile.id==computers.activeId())"✓ " else "")+profile.name+"\n"+host) {
                runCatching { connectComputer(profile) }.onFailure { toast(it.message ?: "连接信息不正确") }
            },LinearLayout.LayoutParams(0,-2,1f))
            item.addView(icon("more","编辑电脑：${profile.name}") { connectionSettings(profile) },LinearLayout.LayoutParams(dp(30),dp(40)))
            list.addView(item)
        }
        list.addView(action("局域网扫描",true) { lanScanPage() })
        list.addView(action("手动连接") { manualConnectionSettings() })
        list.addView(action("外网连接") { internetConnectionPage() })
        list.addView(text("已保存的电脑可以直接连接。首次添加电脑或授权失效时才需要配对码。",10f,muted).apply { setPadding(0,dp(10),0,0) })
        scroll.addView(list); page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun connectionSettings(profile: ComputerProfile) {
        val page=panel("connection-options");pageHeader(page,profile.name)
        val scroll=scrollView();val list=column()
        list.addView(action("局域网模式") {
            runCatching { connectComputer(profile.using("lan")) }.onFailure { lanScanPage(profile.id) }
        })
        if (profile.address("tunnel").isNotEmpty()) {
            list.addView(action("外网模式") {
                runCatching { connectComputer(profile.using("tunnel")) }.onFailure { toast(it.message ?: "连接失败") }
            })
        }
        list.addView(action("添加或更改外网地址") { internetConnectionPage(profile) })
        list.addView(action("重新扫描局域网") { lanScanPage(profile.id) })
        list.addView(action("手动连接") { manualConnectionSettings(profile) })
        scroll.addView(list);page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun connectionField(hint: String, initial: String, type: Int): EditText = EditText(this).apply {
        this.hint=hint; setText(initial); textSize=11f; setTextColor(ink); setHintTextColor(muted)
        inputType=type; isSingleLine=true
        background=shape(surface,12);setPadding(dp(10),dp(8),dp(10),dp(8))
    }
    private fun manualConnectionSettings(profile: ComputerProfile?=null) {
        val page=panel("connection"); pageHeader(page,"手动连接")
        val scroll=scrollView(); val list=column()
        val previous=(profile ?: computers.active())?.address("lan")?.let { runCatching { java.net.URI(it) }.getOrNull() }
        val ip=connectionField("电脑 IP",previous?.host.orEmpty(),android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val port=connectionField("端口",previous?.port?.takeIf { it>0 }?.toString() ?: "8766",android.text.InputType.TYPE_CLASS_NUMBER)
        list.addView(ip); list.addView(port)
        val connect=action("连接",true) {}
        connect.setOnClickListener {
            runCatching {
                val url=ConnectionAddress.manualLanUrl(ip.text.toString(),port.text.toString())
                hideKeyboard()
                val generation=connectionEpoch
                val saved=computers.all()
                connect.isEnabled=false
                work {
                    val result=runCatching {
                        val pin=BridgeClient.probeLanCertificate(url)
                        val info=BridgeClient(url,"",pin).json("/api/connect/info")
                        val id=info.getString("computerId")
                        val existing=saved.firstOrNull { it.id==id }
                        val expected=profile ?: saved.firstOrNull { it.address("lan")==url }
                        require(expected==null || expected.id==id && expected.pin==pin) { "此地址的电脑身份已变化，请核对连接地址" }
                        require(existing==null || existing.pin==pin) { "电脑证书已变化，请核对原连接" }
                        val candidate=ConnectionCandidate(id,info.getString("computerName"),url,pin)
                        val authorized=existing?.takeIf {
                            try {
                                BridgeClient(url,it.token,pin).json("/api/connect/check").getString("computerId")==id
                            } catch (error: BridgeHttpError) {
                                // Older desktop plugins verify the token on the next normal state request.
                                when(error.status) { 401 -> false; 404 -> true; else -> throw error }
                            }
                        }
                        candidate to authorized
                    }
                    postResult {
                        connect.isEnabled=true
                        if (mode!="connection" || generation!=connectionEpoch)return@postResult
                        result.onSuccess { (candidate,authorized) ->
                            if(authorized!=null)connectComputer(authorized.withAddress("lan",url))
                            else confirmLanPairing(candidate)
                        }.onFailure { toast(it.message?.take(100) ?: "无法连接电脑，请核对地址") }
                    }
                }
            }.onFailure { toast(it.message ?: "连接信息不正确") }
        }
        list.addView(connect)
        scroll.addView(list); page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }

    private fun internetConnectionPage(profile: ComputerProfile?=null, target: ConnectionCandidate?=null) {
        val page = panel("internet-connect")
        pageHeader(page,if(target==null)"外网连接" else "局域网配对")
        val scroll = scrollView()
        val list = column()
        val address = connectionField("HTTPS 地址",target?.url ?: profile?.address("tunnel").orEmpty(),
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val codeField = connectionField(if(target==null)"外网配对码" else "局域网配对码","",
            android.text.InputType.TYPE_CLASS_NUMBER).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
        }
        val message = text("在电脑的 DSH 设置 → 手表连接生成 10 位数字配对码。",10f,muted)
        val connect = action("配对并连接",true) {}
        list.addView(address); list.addView(codeField); list.addView(connect); list.addView(message)
        scroll.addView(list); page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        connect.setOnClickListener {
            val (url,code) = runCatching {
                ConnectionAddress.internetUrl(address.text.toString()) to ConnectionAddress.internetCode(codeField.text.toString())
            }.getOrElse {
                message.text = it.message ?: "请检查地址和配对码"
                return@setOnClickListener
            }
            hideKeyboard()
            codeField.text.clear()
            connect.isEnabled = false
            message.text = "正在验证电脑…"
            val generation = connectionEpoch
            fun current() = resumed && mode == "internet-connect" && generation == connectionEpoch
            fun showError(error: Throwable) {
                connect.isEnabled = true
                if (mode == "internet-connect") {
                    message.text = if (current()) error.message?.take(100) ?: "外网连接失败，请重试。"
                        else "连接已暂停，请重新输入配对码。"
                }
            }
            pool.execute {
                val probe = runCatching { BridgeClient.probeLanCertificate(url) }
                ui.post {
                    if (!current()) {
                        showError(IllegalStateException("连接已暂停"))
                        return@post
                    }
                    val pin = probe.getOrElse { showError(it); return@post }
                    if (target?.pin?.isNotEmpty() == true && target.pin != pin) {
                        showError(IllegalStateException("扫描结果与电脑证书不一致"))
                        return@post
                    }
                    if (profile != null && profile.pin != pin) {
                        showError(IllegalStateException("此地址的电脑证书与已配对电脑不同"))
                        return@post
                    }
                    val transport = BridgeClient(url,"",pin)
                    pairingClient = transport
                    pool.execute {
                        val result = runCatching {
                            val protocol = ConnectionAddress.PAIRING_PROTOCOL
                            require(transport.json("/api/connect/info").optInt("internetPairingProtocol") == protocol) {
                                "请先更新电脑上的 DSH Watch 插件"
                            }
                            val nonce = UUID.randomUUID().toString().replace("-","")
                            val key = ConnectionAddress.pairingKey(code,pin,nonce)
                            val response = transport.json("/api/connect/internet",JSONObject().put("nonce",nonce)
                                .put("protocol",protocol).put("proof",ConnectionAddress.pairingProof(key,protocol,"watch",pin,nonce)))
                            val id = response.getString("computerId")
                            val token = response.getString("token")
                            require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && token.length >= 32) { "电脑授权无效" }
                            val proof = ConnectionAddress.pairingProof(key,protocol,"computer",pin,nonce,id,token)
                            require(java.security.MessageDigest.isEqual(proof.toByteArray(),response.getString("proof").toByteArray())) {
                                "电脑未能验证数字配对码，请核对连接地址"
                            }
                            require((profile == null || profile.id == id) && (target?.id.isNullOrEmpty() || target?.id == id)) {
                                "此地址对应另一台电脑"
                            }
                            ComputerProfile(id,response.getString("computerName"),url,token,pin,if(target==null)"tunnel" else "lan")
                        }
                        ui.post {
                            if (current()) result.mapCatching { authorized ->
                                val old = computers.all().firstOrNull { it.id == authorized.id }
                                require(old == null || old.pin == authorized.pin) { "电脑证书已变化，请先核对原连接" }
                                connectComputer(old?.withAddress(authorized.mode,url)?.copy(token=authorized.token) ?: authorized)
                            }.onFailure(::showError)
                            else showError(IllegalStateException("连接已暂停"))
                        }
                    }
                }
            }
        }
    }

    private fun stopConnectionTasks() {
        connectionEpoch++
        lanDiscovery?.let {
            it.stop()
            pauseLanScan?.invoke()
        }
        pauseLanScan=null
        lanDiscovery = null
        scanProgressAnimator?.cancel()
        scanProgressAnimator = null
        pairingClient?.setForeground(false)
        pairingClient = null
    }
    private fun lanScanPage(onlyId: String? = null) {
        val page = panel("lan-scan")
        headerRow(page, "局域网扫描", icon("back", "返回上一页") { navigateBack() },
            icon("refresh", "重新扫描") { restartLanScan?.invoke() })

        val summary = column().apply {
            background = shape(surface, 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val title = text("", 12f, ink, true).apply { gravity = Gravity.CENTER; isSingleLine = true }
        val detail = text("", 10f, muted).apply { gravity = Gravity.CENTER; isSingleLine = true }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(WhaleTheme.RIPPLE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        summary.addView(title)
        summary.addView(detail)
        summary.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(4) })
        page.addView(summary, LinearLayout.LayoutParams(-1, -2))

        val scroll = scrollView()
        val list = column()
        val results = column()
        val empty = text("", 11f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(5))
        }
        list.addView(results)
        list.addView(empty)
        list.addView(action("手动连接") { manualConnectionSettings(computers.all().firstOrNull { it.id == onlyId }) })
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun reconnect(profile: ComputerProfile, candidate: ConnectionCandidate) {
            runCatching {
                require(profile.pin == candidate.pin) { "电脑证书已变化，请核对原连接" }
                connectComputer(profile.withAddress("lan",candidate.url))
            }.onFailure { toast(it.message ?: "连接失败") }
        }

        restartLanScan = {
            stopConnectionTasks()
            for (index in 0 until results.childCount) results.getChildAt(index).animate().cancel()
            results.removeAllViews()
            scroll.scrollTo(0, 0)
            title.text = "正在扫描局域网"
            detail.text = "已找到 0 台电脑"
            empty.text = "正在寻找同一 Wi-Fi 下的电脑…"
            empty.visibility = View.VISIBLE
            progress.progress = 0

            val generation = connectionEpoch
            val seen = mutableSetOf<String>()
            pauseLanScan = {
                title.text="扫描已暂停"
                detail.text="已找到 ${seen.size} 台电脑"
                empty.text="点右上角可重新扫描。"
            }
            val saved = computers.all().associateBy { it.id }
            val animate = backMotion.enabled(progress)
            var secondsLeft = LanDiscovery.SCAN_SECONDS
            fun current() = mode == "lan-scan" && generation == connectionEpoch
            fun showProgress() {
                detail.text = "剩 ${secondsLeft} 秒 · 已找到 ${seen.size} 台"
            }

            lanDiscovery = LanDiscovery(this, found = { candidate ->
                if (current() && (onlyId == null || candidate.id == onlyId) && seen.add(candidate.id)) {
                    val old = saved[candidate.id]
                    if (onlyId != null && old != null && old.pin == candidate.pin) {
                        reconnect(old, candidate)
                    }
                    if (current()) {
                        showProgress()
                        empty.visibility = View.GONE
                        val result = navigation(old?.name ?: candidate.name) {
                            if (old != null) reconnect(old, candidate)
                            else confirmLanPairing(candidate)
                        }
                        results.addView(result)
                        if (backMotion.enabled(result)) {
                            result.alpha = 0f
                            result.translationY = dp(4).toFloat()
                            result.animate().alpha(1f).translationY(0f).setDuration(160).start()
                        }
                    }
                }
            }, progress = { seconds ->
                if (current()) {
                    secondsLeft = seconds
                    showProgress()
                    if (animate && scanProgressAnimator == null) {
                        scanProgressAnimator = ValueAnimator.ofInt(progress.progress, progress.max).apply {
                            duration = seconds * 1000L
                            interpolator = LinearInterpolator()
                            addUpdateListener { progress.progress = it.animatedValue as Int }
                            start()
                        }
                    } else if (!animate) {
                        progress.progress = (LanDiscovery.SCAN_SECONDS - seconds) * progress.max / LanDiscovery.SCAN_SECONDS
                    }
                }
            }, finished = { error ->
                if (current()) {
                    pauseLanScan=null
                    scanProgressAnimator?.cancel()
                    scanProgressAnimator = null
                    title.text = if (error == null) "扫描完成" else "扫描暂不可用"
                    detail.text = if (seen.isEmpty()) "未找到电脑" else "已找到 ${seen.size} 台电脑"
                    if (seen.isEmpty()) empty.text = error ?: "确认电脑和手表在同一 Wi-Fi，再点右上角重试。"
                    progress.progress = if (error == null) progress.max else 0
                }
            })
            lanDiscovery?.start()
        }
        restartLanScan?.invoke()
    }

    private fun pairingPaused(target:ConnectionCandidate) {
        val page=panel("pair-device");pageHeader(page,"连接电脑")
        page.addView(text("连接已暂停",12f,muted).apply { gravity=Gravity.CENTER;setPadding(0,dp(16),0,dp(10)) })
        page.addView(action("重新连接",true) { confirmLanPairing(target) })
    }

    private fun confirmLanPairing(target: ConnectionCandidate) {
        val saved = computers.all().firstOrNull { it.id == target.id }
        internetConnectionPage(saved, target)
    }

    private fun toggleRecording() {
        if(recording!=null) { finishRecording(); return }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1); return }
        hideKeyboard(); releasePlayback()
        runCatching { val value=VoiceRecorder(); recording=value; value.start { postResult { if(recording===value)finishRecording() } }; localError="" }.onFailure { recording=null; toast(it.message ?: "无法开始录音") }
        updateControls()
    }
    private fun finishRecording() {
        val value=recording ?: return
        recording=null; val audio=value.stop(); val selected=sessionId
        if(audio.size<9644 || value.error!=null) { toast(value.error ?: "录音太短，请再说一次"); updateControls(); return }
        voiceBusy=true; updateControls()
        work {
            val result=JSONObject(requestClient().bytes("/api/transcribe",audio,"audio/wav").toString(Charsets.UTF_8)).getString("text")
            postResult {
                voiceBusy=false
                if(selected==sessionId) { val prefix=input.text.toString(); input.setText(prefix+(if(prefix.isEmpty())"" else " ")+result); input.setSelection(input.text.length); localError="" }
                else { computers.putState("draft:$selected",result); toast("识别文字已保存到原对话草稿") }
                updateControls()
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==1 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)toggleRecording() else toast("允许麦克风权限后才能录音")
    }
    private fun speak(id:String) {
        if(id.isEmpty() || !resumed)return
        val selected=sessionId
        releasePlayback(); val generation=playbackGeneration
        preparingPlayback=true
        syncScreenAwake()
        toast("正在准备朗读")
        work {
            val audio=try {
                requestClient().bytes("/api/speak",JSONObject().put("sessionId",selected).put("messageId",id).toString().toByteArray(Charsets.UTF_8))
            } catch(error: Exception) {
                postResult {
                    if(selected==sessionId && generation==playbackGeneration) {
                        releasePlayback()
                        toast(error.message?.take(100) ?: "朗读暂不可用")
                    }
                }
                return@work
            }
            postResult {
                if(selected!=sessionId || generation!=playbackGeneration)return@postResult
                runCatching {
                    val path=File(cacheDir,"reply-$generation.wav")
                    playbackFile=path
                    path.writeBytes(audio)
                    val playback=MediaPlayer()
                    player=playback
                    preparingPlayback=false
                    playback.apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        setDataSource(path.absolutePath)
                        setOnPreparedListener {
                            if(player===it && resumed && generation==playbackGeneration)it.start() else it.release()
                        }
                        setOnCompletionListener { if(player===it)releasePlayback() else { it.release(); path.delete() } }
                        setOnErrorListener { failed,_,_ ->
                            if(player===failed) { releasePlayback(); toast("朗读失败，请检查手表音量") } else failed.release()
                            true
                        }
                        prepareAsync()
                    }
                }.onFailure { releasePlayback(); toast("朗读失败，请检查手表音量") }
            }
        }
    }
    @Deprecated("Platform back navigation")
    override fun onBackPressed() { navigateBack() }
}
