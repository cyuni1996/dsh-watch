package org.dshwatch.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BridgeClientTest {
    private class BlockingCall(private val original: Request, private val block: Boolean) : Call {
        val entered=CountDownLatch(1)
        private val released=CountDownLatch(1)
        @Volatile private var cancelled=false
        @Volatile private var executed=false
        @Volatile var cancelThread: Thread?=null
        val cancelCount=java.util.concurrent.atomic.AtomicInteger()
        override fun request()=original
        override fun isExecuted()=executed
        override fun isCanceled()=cancelled
        override fun timeout()=Timeout()
        override fun clone(): Call=BlockingCall(original,block)
        override fun enqueue(responseCallback: Callback)=error("Synchronous requests only")
        override fun cancel() { cancelThread=Thread.currentThread(); cancelCount.incrementAndGet(); cancelled=true; released.countDown() }
        override fun execute(): Response {
            executed=true; entered.countDown()
            if(block)check(released.await(3,TimeUnit.SECONDS)) { "request wasn't cancelled" }
            if(cancelled)throw IOException("Canceled")
            return Response.Builder().request(original).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("ok".toResponseBody()).build()
        }
    }

    @Test fun leavingForegroundCancelsInFlightReadAndDoesNotReplayOnResume() {
        val calls=java.util.concurrent.CopyOnWriteArrayList<BlockingCall>()
        val bridge=BridgeClient("https://watch.invalid","test","0".repeat(64),Call.Factory { request ->
            BlockingCall(request,calls.isEmpty()).also { calls.add(it) }
        })
        val failure=AtomicReference<Throwable>()
        val thread=Thread { try { bridge.bytes("/api/state") } catch(t:Throwable) { failure.set(t) } }
        thread.start()
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(calls.isEmpty() && System.nanoTime()<deadline)Thread.yield()
        assertTrue(calls.first().entered.await(2,TimeUnit.SECONDS))
        val lifecycleThread=Thread.currentThread()
        bridge.setForeground(false); bridge.setForeground(false); thread.join(2_000)
        assertFalse("Request remained alive in background",thread.isAlive)
        assertNotSame("TLS cancellation ran on the lifecycle thread",lifecycleThread,calls.first().cancelThread)
        assertEquals(1,calls.first().cancelCount.get())
        assertTrue(failure.get() is IOException)
        bridge.setForeground(true)
        assertEquals(1,calls.size)
        assertEquals("ok",bridge.bytes("/api/state").toString(Charsets.UTF_8))
    }

    @Test fun unchangedStateUsesConditionalRequestAndSkipsJsonParsing() {
        val bridge=BridgeClient("https://watch.invalid","test","0".repeat(64),Call.Factory { request ->
            assertEquals("session ?&", request.url.queryParameter("sessionId"))
            assertEquals("\"snapshot\"", request.header("If-None-Match"))
            object : Call by BlockingCall(request,false) {
                override fun execute() = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(304).message("Not Modified").body("".toResponseBody()).build()
            }
        })
        val result = bridge.readState("session ?&", "\"snapshot\"")
        assertNull(result.value)
        assertEquals("\"snapshot\"", result.etag)
    }

    @Test fun pausedClientCannotStartNetworkAndCancelledPostIsNotReplayed() {
        val calls=java.util.concurrent.CopyOnWriteArrayList<BlockingCall>()
        val bridge=BridgeClient("https://watch.invalid","test","0".repeat(64),Call.Factory { request ->
            BlockingCall(request,false).also { calls.add(it) }
        })
        bridge.setForeground(false)
        assertThrows(IOException::class.java) { bridge.bytes("/api/message","draft".toByteArray()) }
        assertTrue(calls.single().isCanceled()); assertFalse(calls.single().isExecuted())
        bridge.setForeground(true)
        assertEquals(1,calls.size)
    }
}
