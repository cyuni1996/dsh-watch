package org.dshwatch.app

import java.net.URI
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object ConnectionAddress {
    const val PAIRING_PROTOCOL = 2
    fun internetUrl(value: String): String {
        val address = URI(value.trim())
        require(address.scheme.equals("https",ignoreCase=true) && address.host != null &&
            address.rawUserInfo == null && address.rawQuery == null && address.rawFragment == null &&
            address.rawPath in listOf("","/") && (address.port == -1 || address.port in 1..65535) &&
            !address.rawAuthority.endsWith(":")) { "请输入完整 HTTPS 地址，可带端口" }
        return "https://"+address.rawAuthority
    }

    fun internetCode(value: String): String {
        val code = value.filterNot(Char::isWhitespace)
        require(code.matches(Regex("[0-9]{10}"))) { "请输入电脑显示的 10 位数字配对码" }
        return code
    }

    fun pairingKey(code: String, pin: String, nonce: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), "watch-internet-v2\n$pin\n$nonce".toByteArray(Charsets.UTF_8), 200_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun pairingProof(key: ByteArray, protocol: Int, role: String, pin: String, nonce: String, vararg fields: String): String {
        val message = (listOf("watch-internet-v$protocol",role,pin,nonce)+fields).joinToString("\n")
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key,"HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
    fun manualLanUrl(ip: String, port: String): String {
        val host = ip.trim().removeSurrounding("[", "]")
        val number = port.trim().toIntOrNull()
        require(number != null && number in 1..65535) { "请输入有效的端口（1–65535）" }
        val ipv4 = host.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.matches(Regex("0|[1-9][0-9]{0,2}")) && part.toInt() in 0..255
            }
        }
        val ipv6 = host.contains(':') && host.matches(Regex("[0-9a-fA-F:.]+"))
        require(ipv4 || ipv6) { "请输入电脑的 IP 地址" }
        val authority = if (ipv6) "[$host]" else host
        val address = "https://$authority:$number"
        require(runCatching { URI(address).host }.getOrNull() != null) { "请输入有效的 IP 地址" }
        return address
    }

    /** Pick an address on the watch's Wi-Fi subnet, never a PC's virtual adapter. */
    fun lanAddress(addresses:List<String>,subnets:List<Pair<String,Int>>):String? {
        fun ipv4(value:String):Long? {
            val parts=value.split('.');if(parts.size!=4)return null
            var bits=0L
            for(part in parts) { val n=part.toIntOrNull() ?: return null;if(n !in 0..255)return null;bits=(bits shl 8) or n.toLong() }
            return bits
        }
        return addresses.firstOrNull { candidate ->
            val bits=ipv4(candidate)
            bits!=null && subnets.any { (local,prefix) ->
                val own=ipv4(local);val mask=if(prefix in 1..32)(0xffffffffL shl (32-prefix)) and 0xffffffffL else 0L
                own!=null && mask!=0L && (bits and mask)==(own and mask)
            }
        }
    }
    fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
