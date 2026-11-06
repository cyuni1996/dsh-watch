package org.dshwatch.app

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.net.ssl.SSLContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ConnectionModesTest {
    @Test fun internetEnrollmentAcceptsHttpsOriginsAndMatchesServerProof() {
        assertEquals("https://203.0.113.5:24876",ConnectionAddress.internetUrl("https://203.0.113.5:24876/"))
        assertEquals("https://[fd00::5]",ConnectionAddress.internetUrl("https://[fd00::5]"))
        assertEquals("https://watch.example.com",ConnectionAddress.internetUrl("https://watch.example.com"))
        for (bad in listOf("http://example.com","https://user@example.com","https://example.com/a","https://example.com?x","https://example.com:0","https://example.com:")) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionAddress.internetUrl(bad) }
        }
        val code = ConnectionAddress.internetCode("01234 56789")
        assertEquals("0123456789",code)
        assertThrows(IllegalArgumentException::class.java) { ConnectionAddress.internetCode("abcd-2345-efgh-6789") }
        val pin="a".repeat(64)
        val nonce="1".repeat(32)
        val key=ConnectionAddress.pairingKey(code,pin,nonce)
        assertEquals("be39dbbf50613cc33c26a4b6e52392e66d182127fed1dbf2a664429cb5012b21",
            ConnectionAddress.pairingProof(key,2,"watch",pin,nonce))
        assertEquals("31bda755e775b4d6b859d8dfcf70b85dcc39a11230453acc7189953475df3689",
            ConnectionAddress.pairingProof("ABCD2345EFGH6789".toByteArray(),1,"watch",pin,nonce))
    }
    @Test fun manualEndpointAcceptsOnlyAnIpAndPort() {
        assertEquals("https://192.168.1.5:8766",ConnectionAddress.manualLanUrl("192.168.1.5","8766"))
        assertEquals("https://[fd00::5]:8766",ConnectionAddress.manualLanUrl("fd00::5","8766"))
        for((ip,port) in listOf("pc.local" to "8766","192.168.1.999" to "8766","1.2.3.4" to "65536","1.2.3.4/path" to "8766")) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionAddress.manualLanUrl(ip,port) }
        }
    }
    @Test fun discoveryChoosesTheWatchSubnetInsteadOfVirtualAdapters() {
        val addresses=listOf("192.0.2.1","198.51.100.1","192.168.50.10")
        assertEquals("192.168.50.10",ConnectionAddress.lanAddress(addresses,listOf("192.168.50.20" to 24)))
        assertNull(ConnectionAddress.lanAddress(addresses,listOf("192.168.5.8" to 24)))
        assertNull(ConnectionAddress.lanAddress(listOf("192.168.50.999"),listOf("192.168.50.20" to 24)))
        assertNull(ConnectionAddress.lanAddress(addresses,listOf("0.0.0.0" to 0)))
    }
    @Test fun sameComputerAcrossModesRetainsSessionDraftAndPendingRequest() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("dual-mode-test",0);prefs.edit().clear().commit()
        val store=ComputerProfiles(prefs)
        val lan=ComputerProfile("same-pc","电脑","https://192.168.1.2:8766","token","a".repeat(64))
        store.save(lan);store.putState("sessionId","same-thread");store.putState("draft:same-thread","原草稿");store.putState("pendingId:same-thread","original-request")
        val tunnelUrl = "https://203.0.113.5:24876"
        store.save(lan.withAddress("tunnel",tunnelUrl))
        val reopened=ComputerProfiles(prefs)
        assertEquals(1,reopened.all().size);assertEquals("tunnel",reopened.active()!!.mode)
        assertEquals("same-thread",reopened.getState("sessionId"));assertEquals("原草稿",reopened.getState("draft:same-thread"))
        assertEquals("original-request",reopened.getState("pendingId:same-thread"))
        val tunnel = reopened.active()!!
        assertEquals(tunnelUrl,tunnel.url)
        assertEquals(lan.pin,tunnel.pin)
        assertEquals(lan.token,tunnel.token)
        reopened.save(reopened.active()!!.using("lan"));assertEquals(lan.url,reopened.active()!!.url)
        assertEquals(tunnelUrl,reopened.active()!!.using("tunnel").url)
        assertEquals("原草稿",reopened.getState("draft:same-thread"))
    }
    @Test fun legacyProfilesDefaultToLanWithoutDiscardingOtherPreferences() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("dual-mode-legacy",0);prefs.edit().clear().commit()
        prefs.edit().putString("computers","""[{"id":"pc","name":"原电脑","url":"https://192.168.1.2:8766","token":"secret","pin":"${"a".repeat(64)}"}]""").putString("activeComputer","pc").putString("unknown","keep").commit()
        val store=ComputerProfiles(prefs);assertEquals("lan",store.active()!!.mode);assertEquals(store.active()!!.url,store.active()!!.lanUrl)
        assertEquals("keep",prefs.getString("unknown",null));assertThrows(IllegalArgumentException::class.java) { store.active()!!.using("tunnel") }
    }
    @Test fun bothConnectionModesValidateTheExactEndpointHost() {
        val lan=BridgeClient("https://watch.example.com","token","a".repeat(64))
        val tunnel=BridgeClient("https://203.0.113.5:24876","token","a".repeat(64))
        val ipv6Tunnel=BridgeClient("https://[FD00:0:0:0:0:0:0:5]:24876","token","a".repeat(64))
        fun http(client:BridgeClient)=BridgeClient::class.java.getDeclaredField("http").apply { isAccessible=true }.get(client) as OkHttpClient
        val unverified=SSLContext.getDefault().createSSLEngine().session
        assertTrue(http(lan).hostnameVerifier.verify("watch.example.com",unverified))
        assertTrue(http(tunnel).hostnameVerifier.verify("203.0.113.5",unverified))
        assertFalse(http(tunnel).hostnameVerifier.verify("203.0.113.6",unverified))
        assertTrue(http(ipv6Tunnel).hostnameVerifier.verify("fd00::5",unverified))
    }
    @Test fun oldDomainProfileUsesSavedLanAndRetainsTheAddressForRePairing() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("old-domain-profile",0)
        prefs.edit().clear().putString("computers","""[{"id":"pc","name":"电脑","url":"https://old.example.com","mode":"public","lanUrl":"https://192.168.1.2:8766","token":"token","pin":"${"a".repeat(64)}"}]""")
            .putString("activeComputer","pc").commit()
        val profile = ComputerProfiles(prefs).active()!!
        assertEquals("pc",profile.id)
        assertEquals("lan",profile.mode)
        assertEquals("https://192.168.1.2:8766",profile.url)
        assertEquals("https://old.example.com",profile.tunnelUrl)
        assertEquals("token",profile.token)
    }
}
