@file:OptIn(ExperimentalSerializationApi::class)

package app.nya.password.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Read-only views of what the core returns (JSON from npw-ffi, same shapes as
 * the web vault's `types.ts`). Unknown keys are ignored when reading; item
 * content is never written back from these classes — edits go through
 * [ItemDoc], which keeps every key it does not know.
 */
val CoreJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/** Plain JSON (no renaming) for trees and requests. */
val PlainJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class LockState(
    val signedIn: Boolean = false,
    val unlocked: Boolean = false,
    val login: String = "",
    val serverUrl: String = "",
    val accountId: String = "",
    val deviceId: String = "",
    val lastSyncAt: Long = 0,
)

@Serializable
data class EmergencyKit(
    val serverUrl: String = "",
    val login: String = "",
    val accountId: String = "",
    val secretKey: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class VaultView(val id: String, val name: String = "", val role: String = "", val items: Int = 0)

@Serializable
data class ItemView(
    val vaultId: String,
    val itemId: String,
    val template: String = "",
    val title: String = "",
    val subtitle: String = "",
    val favorite: Boolean = false,
    val archived: Boolean = false,
    /** "使用前需要验证": verify (biometrics / master password) before secrets are shown, copied or filled. */
    val reprompt: Boolean = false,
    val deleted: Boolean = false,
    val tags: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val hasTotp: Boolean = false,
    val passkeys: Int = 0,
    val attachments: Int = 0,
    val conflicts: Int = 0,
    val readOnly: Boolean = false,
    val pending: Boolean = false,
    val rejected: String? = null,
    val revision: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Full content (only from `item`), kept as a JSON tree. */
    val content: JsonObject? = null,
)

@Serializable
data class SyncReport(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val merged: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val restoredToServer: Int = 0,
    val undecryptable: Int = 0,
    val rollbacksDetected: Int = 0,
    val fullResyncs: Int = 0,
    val finishedAt: Long = 0,
)

@Serializable
data class Finding(val issue: String, val vaultId: String, val itemId: String, val title: String = "", val detail: String = "")

@Serializable
data class SecurityReport(
    val findings: List<Finding> = emptyList(),
    val weak: Int = 0,
    val reused: Int = 0,
    val old: Int = 0,
    val totpAvailable: Int = 0,
    val insecure: Int = 0,
)

@Serializable
data class HealthReport(
    val checked: Int = 0,
    val ok: Int = 0,
    val pending: Int = 0,
    val rejected: Int = 0,
    val readOnly: Int = 0,
    val conflicts: Int = 0,
    val problems: List<List<String>> = emptyList(),
    val checkedAt: Long = 0,
)

@Serializable
data class DeviceRecord(
    val id: String,
    val name: String = "",
    val platform: String = "",
    val clientVersion: String = "",
    val createdAt: Long = 0,
    val lastSeenAt: Long = 0,
    val revokedAt: Long? = null,
    val current: Boolean = false,
)

@Serializable
data class RevisionInfo(
    val revision: Long,
    val deleted: Boolean = false,
    val createdAt: Long = 0,
    val deviceId: String = "",
    val size: Long = 0,
)

@Serializable
data class TemplateFieldInfo(val id: String, val kind: String, val purpose: String? = null, val multiline: Boolean = false, val label: String = "")

@Serializable
data class TemplateInfo(val id: String, val label: String = "", val icon: String = "", val fields: List<TemplateFieldInfo> = emptyList())

@Serializable
data class OtpCode(val code: String = "", val remaining: Int = 0, val period: Int = 30, val issuer: String = "", val account: String = "")

@Serializable
data class Generated(val password: String = "", val bits: Double = 0.0)

@Serializable
data class PasskeyCandidate(
    val vaultId: String,
    val itemId: String,
    val passkeyId: String,
    val title: String = "",
    val rpId: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val reprompt: Boolean = false,
)

@Serializable
data class PasskeyCreated(val vaultId: String, val itemId: String, val response: JsonObject)

// ---------------------------------------------------------------- item content (read side)

@Serializable
data class ItemField(
    val id: String,
    val label: String = "",
    val kind: String = "text",
    val purpose: String? = null,
    val value: JsonElement = JsonNull,
    val section: String? = null,
    val multiline: Boolean = false,
) {
    val secret: Boolean get() = kind == "concealed" || kind == "pin"

    /** The value as text; addresses are joined (country, province, city, district, street, postal code). */
    val text: String
        get() = when (val v = value) {
            is JsonPrimitive -> v.contentOrNull ?: ""
            is JsonObject -> if (kind == "address") {
                listOf("country", "province", "city", "district", "street", "postal_code")
                    .mapNotNull { (v[it] as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                    .joinToString(" ")
            } else {
                v.toString()
            }
            else -> if (v is JsonNull) "" else v.toString()
        }

    val isEmpty: Boolean
        get() = when (val v = value) {
            is JsonNull -> true
            is JsonPrimitive -> v.contentOrNull.isNullOrEmpty()
            is JsonObject -> v.values.all { (it as? JsonPrimitive)?.contentOrNull.isNullOrEmpty() }
            else -> false
        }
}

@Serializable
data class Section(val id: String, val label: String = "")

@Serializable
data class UrlEntry(
    val id: String = "",
    val url: String = "",
    @kotlinx.serialization.SerialName("match") val match: String = "domain",
    val certSha256: List<String> = emptyList(),
)

@Serializable
data class Passkey(
    val id: String,
    val rpId: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val rpName: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class Attachment(val id: String, val name: String = "", val size: Long = 0, val mime: String = "")

@Serializable
data class HistoryEntry(val id: String, val field: String = "", val label: String = "", val value: JsonElement = JsonNull, val until: Long = 0)

@Serializable
data class Conflict(
    val id: String,
    val path: String = "",
    val label: String = "",
    val value: JsonElement = JsonNull,
    val kept: JsonElement = JsonNull,
    val device: String = "",
    val at: Long = 0,
)

@Serializable
data class AutofillSettings(val autoSubmit: Boolean = false, val never: Boolean = false)

@Serializable
data class Content(
    val format: String = "1.0",
    val template: String = "",
    val title: String = "",
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val reprompt: Boolean = false,
    val tags: List<String> = emptyList(),
    val fields: List<ItemField> = emptyList(),
    val sections: List<Section> = emptyList(),
    val urls: List<UrlEntry> = emptyList(),
    val passkeys: List<Passkey> = emptyList(),
    val notes: String = "",
    val attachments: List<Attachment> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val autofill: AutofillSettings = AutofillSettings(),
    val conflicts: List<Conflict> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    private fun byPurpose(p: String) = fields.firstOrNull { it.purpose == p && !it.isEmpty }?.text

    val username: String? get() = byPurpose("username")
    val password: String? get() = byPurpose("password")
    val totp: String? get() = fields.firstOrNull { it.kind == "totp" && !it.isEmpty }?.text

    companion object {
        fun of(tree: JsonObject): Content = CoreJson.decodeFromJsonElement(serializer(), tree)
    }
}

inline fun <reified T> decode(json: String): T = CoreJson.decodeFromString(json)
