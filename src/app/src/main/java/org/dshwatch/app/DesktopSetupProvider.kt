package org.dshwatch.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Accepts a short-lived pairing proof only from a computer already authorized for ADB. */
class DesktopSetupProvider : ContentProvider() {
    companion object {
        @Volatile private var wakeUntil = 0L

        fun consumeWakeRequest(): Boolean {
            val pending = wakeUntil > SystemClock.elapsedRealtime()
            wakeUntil = 0
            return pending
        }
    }

    override fun onCreate() = true

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != 2000) throw SecurityException("ADB shell only")
        if (method == "prepare") {
            wakeUntil = SystemClock.elapsedRealtime() + 15_000
            return Bundle().apply { putString("status", "ready") }
        }
        require(method == "pair" && arg != null && arg.length <= 768)
        val identity = Binder.clearCallingIdentity()
        return try {
            Bundle().apply { putString("status", setup(arg)) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun setup(arg: String): String =
        runCatching {
            val payload = JSONObject(String(Base64.decode(arg, Base64.URL_SAFE), Charsets.UTF_8))
            val address = URI(ConnectionAddress.internetUrl(payload.getString("address")))
            val url = ConnectionAddress.manualLanUrl(address.host, address.port.toString())
            val pin = payload.getString("pin")
            val id = payload.getString("id")
            val code = payload.getString("code")
            require(code.matches(Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{16}")))
            val prefs = requireNotNull(context).getSharedPreferences("connection", 0)
            val computers = ComputerProfiles(prefs)
            computers.migrateLegacy()
            val previous = computers.all().firstOrNull { it.id == id }
            if (previous != null && previous.pin != pin) return@runCatching "pin_changed"
            val authorized = previous?.let {
                try {
                    val info = BridgeClient(url, it.token, pin).json("/api/connect/check")
                    require(info.getString("computerId") == id)
                    it.withAddress("lan", url)
                } catch (error: BridgeHttpError) {
                    if (error.status != 401) throw error
                    null
                }
            } ?: pair(url, id, pin, code, previous)
            computers.save(authorized, activate = false, desktopSetup = true)
            "ok"
        }.getOrElse {
            Log.w("DSHWatchSetup", "Desktop pairing failed: ${it.javaClass.simpleName}")
            if (it is java.io.IOException) "unreachable" else "failed"
        }

    private fun pair(url: String, id: String, pin: String, code: String, previous: ComputerProfile?): ComputerProfile {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val key = code.toByteArray(Charsets.US_ASCII)
        val response = BridgeClient(url, "", pin).json("/api/connect/internet", JSONObject()
            .put("nonce", nonce).put("proof", ConnectionAddress.pairingProof(key, 1, "watch", pin, nonce)))
        require(response.getString("computerId") == id)
        val token = response.getString("token")
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val proof = ConnectionAddress.pairingProof(key, 1, "computer", pin, nonce, id, token)
        require(MessageDigest.isEqual(proof.toByteArray(), response.getString("proof").toByteArray()))
        return previous?.withAddress("lan", url)?.copy(token = token)
            ?: ComputerProfile(id, response.getString("computerName"), url, token, pin)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
