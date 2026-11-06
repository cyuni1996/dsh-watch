package org.dshwatch.app

import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal class BridgeHttpError(val status: Int, message: String) : IllegalStateException(message)
internal data class StateRead(val value: JSONObject?, val etag: String?)

class BridgeClient(
    val url: String, private val token: String, pin: String,
    private val callFactory: Call.Factory? = null
) {
    companion object {
        // First-use certificate probe. The handshake sends no HTTP or credentials.
        // Subsequent requests require this exact certificate and a locally approved token.
        fun probeLanCertificate(url: String): String {
            val endpoint = URL(url)
            val probeTrust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    throw java.security.cert.CertificateException("不支持客户端证书")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    (chain.firstOrNull() ?: throw java.security.cert.CertificateException("缺少电脑证书")).checkValidity()
                }
            }
            val probe = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(probeTrust), null)
            }
            val host = endpoint.host.removeSurrounding("[", "]")
            val port = endpoint.port.takeIf { it > 0 } ?: endpoint.defaultPort
            return Socket().use { socket ->
                socket.connect(InetSocketAddress(host,port),5000)
                (probe.socketFactory.createSocket(socket,host,port,true) as SSLSocket).use { tls ->
                    tls.soTimeout = 5000
                    tls.startHandshake()
                    certificateFingerprint(tls.session.peerCertificates.first().encoded)
                }
            }
        }

        private fun certificateFingerprint(encoded: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
    }
    private val requestLock = Any()
    private var foreground = true
    private val requests = mutableSetOf<Call>()

    fun setForeground(value: Boolean) {
        val pending=synchronized(requestLock) {
            if(foreground==value)return
            foreground = value
            if(value)return
            requests.toList()
        }
        if(pending.isEmpty() && http.connectionPool.connectionCount()==0)return
        // Android's TLS close can write close_notify. Both cancellation and idle
        // socket eviction must therefore run off the Activity/lifecycle thread.
        Thread({
            pending.forEach { it.cancel() }
            val stillPaused=synchronized(requestLock) { !foreground }
            if(stillPaused)http.connectionPool.evictAll()
        },"bridge-cleanup").apply { isDaemon=true; start() }
    }
    private val expected = pin.lowercase()
    private val endpoint = url.toHttpUrl()
    init {
        require(endpoint.isHttps) { "连接地址必须使用 https" }
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "证书指纹应为64位" }
    }
    private val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("不支持客户端证书")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("缺少电脑证书")
                chain[0].checkValidity()
                val actual = certificateFingerprint(chain[0].encoded)
                if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                    throw java.security.cert.CertificateException("电脑证书不匹配，请重新配对")
                }
            }
    }
    private val socketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }.socketFactory
    private val http = OkHttpClient.Builder()
        .sslSocketFactory(socketFactory, trustManager)
        .hostnameVerifier { hostname, _ -> hostname == endpoint.host }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        // Mutating requests must never be retried invisibly after ambiguous delivery.
        .retryOnConnectionFailure(false)
        .build()
    private val readHttp = http.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val stateHttp = readHttp.newBuilder().callTimeout(17, TimeUnit.SECONDS).build()
    private val voiceHttp = http.newBuilder().readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS).build()



    private data class ResponseData(val body: ByteArray?, val etag: String?)

    private fun exchange(path: String, payload: ByteArray?, type: String, etag: String? = null): ResponseData {
        val request = Request.Builder().url(url.trimEnd('/') + path)
            .header("Authorization", "Bearer $token").apply {
                if (payload != null) post(payload.toRequestBody(type.toMediaType()))
                if (etag != null) header("If-None-Match", etag)
            }.build()
        val transport = callFactory ?: when {
            path == "/api/speak" || path == "/api/transcribe" -> voiceHttp
            payload == null && path.substringBefore('?') == "/api/state" -> stateHttp
            payload == null -> readHttp
            else -> http
        }
        val call = transport.newCall(request)
        synchronized(requestLock) {
            if (!foreground || Thread.currentThread().isInterrupted) {
                call.cancel()
                throw java.io.InterruptedIOException("应用已离开前台")
            }
            requests.add(call)
        }
        try {
            return call.execute().use { response ->
                if (response.code == 304 && etag != null) return@use ResponseData(null, etag)
                val body = response.body?.bytes() ?: byteArrayOf()
                if (!response.isSuccessful) {
                    val raw = body.toString(Charsets.UTF_8)
                    val message = runCatching { JSONObject(raw).optString("error", raw) }.getOrDefault(raw)
                    throw BridgeHttpError(response.code, message.take(250).ifEmpty { "电脑返回错误 ${response.code}" })
                }
                ResponseData(body, response.header("ETag"))
            }
        } finally { synchronized(requestLock) { requests.remove(call) } }
    }

    internal fun readState(sessionId: String, etag: String?): StateRead {
        val path = "/api/state" + if (sessionId.isEmpty()) "" else "?sessionId=" + URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        val response = exchange(path, null, "application/json", etag)
        return StateRead(response.body?.let { JSONObject(it.toString(Charsets.UTF_8)) }, response.etag)
    }

    fun bytes(path: String, payload: ByteArray? = null, type: String = "application/json"): ByteArray =
        checkNotNull(exchange(path, payload, type).body)

    fun json(path: String, payload: JSONObject? = null): JSONObject =
        JSONObject(bytes(path, payload?.toString()?.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
}
