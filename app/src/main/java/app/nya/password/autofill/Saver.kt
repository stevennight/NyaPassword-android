package app.nya.password.autofill

import app.nya.password.core.Content
import app.nya.password.core.ItemDoc
import app.nya.password.core.ItemView
import app.nya.password.core.Vault
import app.nya.password.core.VaultView
import app.nya.password.core.decode
import app.nya.password.ffi.newShortId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Saving logins from autofill and the credential provider (blocking: background threads). */
object Saver {
    /** Items for the same site / app; those with the same username first. */
    fun matches(vault: Vault, s: PendingSave): List<Pair<ItemView, Content>> =
        Fill.candidates(vault, s.target, limit = 20)
            .filter { (_, c) -> c.fields.any { it.purpose == "password" } || c.username != null }
            .sortedByDescending { (_, c) -> (c.username ?: "") == s.username }

    /** Sets the password (and the username when the item has none) of an existing item. */
    fun update(vault: Vault, item: ItemView, s: PendingSave) {
        val full: ItemView = decode(vault.callNow { it.item(item.vaultId, item.itemId) })
        var doc = ItemDoc(full.content ?: error("no content"))
        val pw = doc.fields.firstOrNull { ItemDoc.text(it, "purpose") == "password" }
        doc = if (pw != null) {
            doc.withFieldValue(ItemDoc.idOf(pw), s.password)
        } else {
            doc.withField(ItemDoc.newField("password", "密码", "concealed", purpose = "password"))
                .withFieldValue("password", s.password)
        }
        val user = doc.fields.firstOrNull { ItemDoc.text(it, "purpose") == "username" }
        if (s.username.isNotEmpty() && user != null && (user["value"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()) {
            doc = doc.withFieldValue(ItemDoc.idOf(user), s.username)
        }
        vault.callNow { it.saveItem(item.vaultId, item.itemId, doc.toJson()) }
        linkIfApp(vault, item.vaultId, item.itemId, s.target)
    }

    /** Only mask characters: what some browsers report for a password input in compatibility mode. */
    fun masked(value: String): Boolean = value.isNotEmpty() && value.all { it in MASK_CHARS }

    private const val MASK_CHARS = "•●∙⋅·*＊◦⁕"

    /** A new login for the site / app. Returns the item id. */
    fun create(vault: Vault, vaultId: String, s: PendingSave, title: String = s.target.label): String {
        var doc = ItemDoc(vault.callNow { it.newItem("login") })
        doc = doc.withTitle(title.trim().ifEmpty { s.target.label })
        if (doc.field("username") != null) doc = doc.withFieldValue("username", s.username)
        doc = if (doc.field("password") != null) {
            doc.withFieldValue("password", s.password)
        } else {
            doc.withField(ItemDoc.newField("password", "密码", "concealed", purpose = "password")).withFieldValue("password", s.password)
        }
        if (s.target.browser) {
            doc = doc.withNewUrl(newShortId("u"), s.target.target, "domain")
        }
        val id = vault.callNow { it.saveItem(vaultId, null, doc.toJson()) }
        linkIfApp(vault, vaultId, id, s.target)
        return id
    }

    /** Apps: remember the package with its signing certificate (anti-spoofing on later fills). */
    fun linkIfApp(vault: Vault, vaultId: String, itemId: String, t: FillTarget) {
        if (t.browser) return
        val cert = t.certs.lastOrNull().orEmpty()
        vault.callNow { it.linkApp(vaultId, itemId, t.packageName, cert) }
    }

    /** Without a confirmation screen (Android 8.x): update the one item with this username, else create. */
    fun saveAuto(vault: Vault, s: PendingSave): String {
        val same = matches(vault, s).filter { (_, c) -> (c.username ?: "") == s.username }
        if (same.size == 1) {
            if (same[0].second.password == s.password) return "密码没有变化"
            update(vault, same[0].first, s)
            vault.syncSoon()
            return "已更新“${same[0].first.title}”的密码"
        }
        val vaults: List<VaultView> = decode(vault.callNow { it.vaults() })
        val v = vaults.firstOrNull() ?: return "没有可用的保险库"
        create(vault, v.id, s)
        vault.syncSoon()
        return "已保存到 NyaPassword"
    }
}
