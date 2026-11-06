package org.dshwatch.app

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ComputerProfilesTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: ComputerProfiles
    private val a=ComputerProfile("pc-a","电脑A","https://a.invalid:8766","token-a","a".repeat(64))
    private val b=ComputerProfile("pc-b","电脑B","https://b.invalid:8766","token-b","b".repeat(64))
    @Before fun start() {
        prefs=RuntimeEnvironment.getApplication().getSharedPreferences("connection",0)
        prefs.edit().clear().commit();store=ComputerProfiles(prefs)
    }
    @Test fun upgradePreservesOldConnectionDraftAndUnknownSettingsOnce() {
        prefs.edit().putString("url",a.url).putString("pin",a.pin).putString("token",a.token)
            .putString("sessionId","shared-id").putString("draft:shared-id","原草稿")
            .putString("pendingId:shared-id","request-a").putString("custom","untouched").commit()
        store.migrateLegacy();val original=store.active()!!
        assertEquals(a.url,original.url);assertEquals("原草稿",store.getState("draft:shared-id"))
        assertEquals("request-a",store.getState("pendingId:shared-id"))
        store.save(b);store.migrateLegacy()
        assertEquals(2,store.all().size);assertEquals(b,store.active())
        assertEquals("untouched",prefs.getString("custom",null))
        assertEquals("原草稿",prefs.getString("draft:shared-id",null))
    }
    @Test fun sameSessionIdOnDifferentComputersCannotShareDraftOrPendingRequest() {
        store.save(a);store.putState("sessionId","same-id");store.putState("draft:same-id","A draft")
        store.putState("pendingId:same-id","A-request")
        store.save(b);assertEquals("",store.getState("sessionId"));assertEquals("",store.getState("draft:same-id"))
        store.putState("sessionId","same-id");store.putState("draft:same-id","B draft")
        store.putState("pendingId:same-id","B-request")
        store.activate(a.id)
        assertEquals("A draft",store.getState("draft:same-id"));assertEquals("A-request",store.getState("pendingId:same-id"))
        val reopened=ComputerProfiles(prefs);assertEquals(a,reopened.active())
        reopened.activate(b.id);assertEquals("B draft",reopened.getState("draft:same-id"))
    }
    @Test fun repairingSameComputerUpdatesAddressWithoutResettingItsConversation() {
        store.save(a);store.putState("sessionId","old-conversation")
        store.save(a.copy(name="新名称",url="https://new.invalid:8766"))
        assertEquals(1,store.all().size);assertEquals("old-conversation",store.getState("sessionId"))
        assertFalse(store.active().toString().contains(a.token))
    }
}
