package org.dshwatch.app

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ConnectionUiTest {
    private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var activity:MainActivity
    @Before fun start() {
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        RuntimeEnvironment.getApplication().getSharedPreferences("connection",0).edit().clear().commit()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible();activity=controller.get()
    }
    @After fun stop() { controller.pause().stop().destroy() }
    private fun call(name:String) { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity) }
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
    private fun texts(view:View=activity.window.decorView):List<String> {
        val own=if(view is TextView)listOf(view.text.toString()) else emptyList()
        return own+if(view is ViewGroup)(0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    }
    private fun editHints(view:View=activity.window.decorView):List<String> {
        if(view is EditText)return listOf(view.hint.toString())
        return if(view is ViewGroup)(0 until view.childCount).flatMap { editHints(view.getChildAt(it)) } else emptyList()
    }
    @Test fun internetConnectionRequiresHttpsAddressAndCodeWithoutExistingProfile() {
        call("computersPage")
        assertTrue(texts().containsAll(listOf("局域网扫描","外网连接")))
        assertFalse(texts().contains("扫描添加电脑"))
        assertTrue(click("外网连接"))
        assertEquals("internet-connect",field("mode"))
        val overlay = field("overlay") as View
        assertEquals(listOf("HTTPS 地址","外网配对码"),editHints(overlay))
        val address = requireNotNull(findEdit(overlay,"HTTPS 地址"))
        val code = requireNotNull(findEdit(overlay,"外网配对码"))
        assertEquals(android.text.InputType.TYPE_CLASS_NUMBER,code.inputType)
        code.setText("12345678901")
        assertEquals("1234567890",code.text.toString())
        code.text.clear()
        address.setText("https://203.0.113.5:24876")
        assertTrue(click("配对并连接"))
        assertTrue(texts(overlay).any { it.contains("10 位数字配对码") })
        activity.onBackPressed();assertEquals("computers",field("mode"))
    }
    private fun findEdit(view: View, hint: String): EditText? {
        if (view is EditText && view.hint.toString() == hint) return view
        return if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { findEdit(view.getChildAt(it),hint) } else null
    }
    private fun click(label: String, view: View = field("overlay") as View): Boolean {
        if (view is TextView && view.isClickable && view.text.toString() == label) return view.performClick()
        return view is ViewGroup && (0 until view.childCount).any { click(label,view.getChildAt(it)) }
    }
    @Test fun leavingForegroundReleasesDiscoveryAndNeverCreatesAComputer() {
        MainActivity::class.java.getDeclaredMethod("lanScanPage",String::class.java)
            .apply { isAccessible=true }.invoke(activity,null)
        assertEquals("lan-scan",field("mode"));assertNotNull(field("lanDiscovery"))
        controller.pause();assertNull(field("lanDiscovery"))
        assertTrue(ComputerProfiles(activity.getSharedPreferences("connection",0)).all().isEmpty())
        controller.resume()
    }
    @Test fun manualConnectionOnlyAsksForIpAndPort() {
        MainActivity::class.java.getDeclaredMethod("manualConnectionSettings",ComputerProfile::class.java)
            .apply { isAccessible=true }.invoke(activity,null)
        assertEquals(listOf("电脑 IP","端口"),editHints(field("overlay") as View))
    }
    @Test fun refreshClearsPriorResultsWithoutReplacingTheScanPage() {
        val saved = ComputerProfile("pc-saved","已保存的电脑","https://192.168.1.3:8766","t".repeat(64),"b".repeat(64))
        ComputerProfiles(activity.getSharedPreferences("connection",0)).save(saved)
        MainActivity::class.java.getDeclaredMethod("lanScanPage",String::class.java)
            .apply { isAccessible=true }.invoke(activity,null)
        val overlay = field("overlay") as View
        val discovery = field("lanDiscovery") as LanDiscovery
        @Suppress("UNCHECKED_CAST")
        val found = LanDiscovery::class.java.getDeclaredField("found").apply { isAccessible=true }
            .get(discovery) as (ConnectionCandidate) -> Unit
        val candidate = ConnectionCandidate("pc-one","已发现的电脑","https://192.168.1.4:8766","a".repeat(64))
        found(ConnectionCandidate(saved.id,saved.name,"https://192.168.1.5:8766",saved.pin))
        found(candidate)
        assertEquals("lan-scan",field("mode"))
        assertTrue(texts(overlay).containsAll(listOf(saved.name,candidate.name)))
        assertTrue(click("手动连接"))
        activity.onBackPressed()
        assertSame(overlay,field("overlay"))
        assertTrue(texts(overlay).containsAll(listOf(saved.name,candidate.name)))
        (field("restartLanScan") as Function0<*>).invoke()
        assertSame(overlay,field("overlay"))
        assertNotSame(discovery,field("lanDiscovery"))
        found(candidate)
        assertFalse(texts(overlay).any { it == saved.name || it == candidate.name })
    }
}
