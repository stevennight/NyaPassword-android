package app.nya.password.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The Emergency Kit QR code: `{"v":1,"server":…,"login":…,"secret_key":…}`
 * (the web vault's Welcome page accepts the same text pasted). A bare Secret
 * Key is accepted too.
 */
data class KitQr(val server: String?, val login: String?, val secretKey: String) {
    fun toJson(): String = buildJsonObject {
        put("v", 1)
        server?.let { put("server", it) }
        login?.let { put("login", it) }
        put("secret_key", secretKey)
    }.toString()

    companion object {
        fun parse(text: String): KitQr? {
            val t = text.trim()
            if (t.startsWith("{")) {
                val o = runCatching { PlainJson.parseToJsonElement(t) as JsonObject }.getOrNull() ?: return null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val v = (o["v"] as? JsonPrimitive)?.contentOrNull
                if (v != null && v != "1") return null
                val sk = s("secret_key") ?: return null
                return KitQr(s("server"), s("login"), sk)
            }
            return if (t.uppercase().startsWith("A1-")) KitQr(null, null, t) else null
        }
    }
}
