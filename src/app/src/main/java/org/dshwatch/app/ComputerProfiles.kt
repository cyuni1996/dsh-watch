package org.dshwatch.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ComputerProfile(val id: String, val name: String, val url: String, val token: String, val pin: String,
    val mode: String="lan", val lanUrl: String=if(mode=="lan")url else "",
    val tunnelUrl: String=if(mode=="tunnel")url else "") {
    override fun toString()="ComputerProfile($id, $name)"
    fun json()=JSONObject().put("id",id).put("name",name).put("url",url).put("token",token).put("pin",pin)
        .put("mode",mode).put("lanUrl",address("lan")).put("tunnelUrl",address("tunnel"))
    fun address(connectionMode: String): String = when (connectionMode) {
        mode -> url
        "lan" -> lanUrl
        "tunnel" -> tunnelUrl
        else -> error("连接模式无效")
    }
    fun using(nextMode:String):ComputerProfile {
        val endpoint=address(nextMode)
        require(endpoint.isNotEmpty()) { "请先配置此模式的地址" }
        return withAddress(nextMode,endpoint)
    }
    fun withAddress(connectionMode: String, endpoint: String): ComputerProfile = copy(
        mode = connectionMode, url = endpoint,
        lanUrl = if (connectionMode == "lan") endpoint else address("lan"),
        tunnelUrl = if (connectionMode == "tunnel") endpoint else address("tunnel")
    )
}

/** Connection credentials and all mutable chat state are scoped to a computer. */
class ComputerProfiles(private val prefs: SharedPreferences) {
    fun all(): List<ComputerProfile> {
        val data=JSONArray(prefs.getString("computers","[]"))
        return (0 until data.length()).map {
            val item=data.getJSONObject(it)
            val storedMode = item.optString("mode","lan")
            val url = item.getString("url")
            val lan = if (storedMode=="lan") url else item.optString("lanUrl")
            val tunnel = if (storedMode=="tunnel") url else item.optString("tunnelUrl")
            // Old domain-only profiles retain their identity and address for re-pairing.
            val mode = when (storedMode) {
                "public" -> if (tunnel.isNotBlank() || lan.isBlank()) "tunnel" else "lan"
                else -> storedMode
            }
            val remote = tunnel.ifBlank { if(storedMode=="public") url else item.optString("publicUrl") }
            ComputerProfile(item.getString("id"),item.getString("name"),if(mode=="tunnel") remote else lan,
                item.getString("token"),item.getString("pin"),mode,lan,remote)
        }
    }
    fun activeId(): String=prefs.getString("activeComputer","") ?: ""
    fun active(): ComputerProfile?=all().firstOrNull { it.id==activeId() }
    fun stateKey(key: String, id: String=activeId())="computer:$id:$key"
    fun getState(key: String): String=prefs.getString(stateKey(key),"") ?: ""
    fun putState(key: String, value: String) { prefs.edit().putString(stateKey(key),value).apply() }

    fun migrateLegacy() {
        if(all().isNotEmpty())return
        val url=prefs.getString("url","") ?: ""
        val pin=prefs.getString("pin","") ?: ""
        val token=prefs.getString("token","") ?: ""
        if(url.isBlank() || pin.isBlank() || token.isBlank())return
        val id=UUID.nameUUIDFromBytes((url+"|"+pin).toByteArray(Charsets.UTF_8)).toString()
        val profile=ComputerProfile(id,"原来的电脑",url,token,pin)
        val editor=prefs.edit()
        for((key,value) in prefs.all) {
            if(value is String && (key=="sessionId" || key=="draft" || key.startsWith("draft:") || key.startsWith("pendingText:") || key.startsWith("pendingId:")))
                editor.putString(stateKey(key,id),value)
        }
        val session=prefs.getString("sessionId","") ?: ""
        val oldDraft=prefs.getString("draft","") ?: ""
        if(oldDraft.isNotEmpty() && !prefs.contains("draft:$session"))editor.putString(stateKey("draft:$session",id),oldDraft)
        // Preserve unknown and legacy fields for rollback; migrate atomically, exactly once.
        check(editor.putString("computers",JSONArray().put(profile.json()).toString()).putString("activeComputer",id).commit())
    }

    fun save(profile: ComputerProfile, activate: Boolean=true, desktopSetup: Boolean=false) {
        require(profile.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "电脑编号无效" }
        require(profile.name.isNotBlank() && profile.name.length<=40) { "电脑名称需为1到40个字符" }
        require(profile.mode in listOf("lan","tunnel")) { "连接模式无效" }
        val values=all().toMutableList()
        val index=values.indexOfFirst { it.id==profile.id }
        if(index<0)values.add(profile) else values[index]=profile
        val editor=prefs.edit().putString("computers",JSONArray().apply { values.forEach { put(it.json()) } }.toString())
        if(activate)editor.putString("activeComputer",profile.id)
        if(desktopSetup)editor.putString("desktopSetup",profile.id)
        check(editor.commit()) { "电脑连接保存失败" }
    }
    fun takeDesktopSetup(): ComputerProfile? {
        val id=prefs.getString("desktopSetup",null) ?: return null
        if(!prefs.edit().remove("desktopSetup").commit())return null
        return all().firstOrNull { it.id==id }
    }
    fun activate(id: String) {
        require(all().any { it.id==id }) { "电脑连接不存在" }
        check(prefs.edit().putString("activeComputer",id).commit()) { "切换电脑失败" }
    }
}
