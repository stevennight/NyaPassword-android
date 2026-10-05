package app.nya.password

import app.nya.password.core.ItemDoc
import app.nya.password.core.ItemView
import app.nya.password.core.LockState
import app.nya.password.core.SyncReport
import app.nya.password.core.VaultView
import app.nya.password.core.decode
import app.nya.password.ffi.NpwClient
import app.nya.password.ffi.NpwException
import app.nya.password.ffi.generate
import app.nya.password.ffi.normalizeSecretKey
import app.nya.password.ffi.randomKey
import app.nya.password.ui.ListFilter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The generated Kotlin bindings against the core built for the host, and a
 * real server — the same calls the app makes, minus Android. Skipped unless
 * both are given:
 *
 *   cargo build -p npw-android --lib            # target/debug/npw_android.dll (or .so / .dylib)
 *   NPW_HOST_LIB_DIR=<that dir> NPW_TEST_SERVER=http://127.0.0.1:<port> ./gradlew testDebugUnitTest --rerun-tasks
 *
 * The server must be fresh (registration is only open for the first account).
 */
class HostBindingsTest {
    @Test
    fun registerEditSyncAndSignInAgain() {
        val libDir = System.getenv("NPW_HOST_LIB_DIR")
        val server = System.getenv("NPW_TEST_SERVER")
        assumeTrue("NPW_HOST_LIB_DIR / NPW_TEST_SERVER not set", !libDir.isNullOrBlank() && !server.isNullOrBlank())
        System.setProperty("jna.library.path", libDir!!)

        val dir = Files.createTempDirectory("npw-host").toFile()
        val a = NpwClient(dir.resolve("a.sqlite3").path, randomKey(), "JVM A", "0.0.0-test", "zh-CN")
        val kit = decode<app.nya.password.core.EmergencyKit>(a.register(server!!, "test@example.com", "correct horse battery", null))
        assertEquals(kit.secretKey, normalizeSecretKey(kit.secretKey.lowercase()))
        val vault = decode<List<VaultView>>(a.vaults()).first()
        assertEquals("个人", vault.name)

        // edit a new item as a JSON tree, with a key from a "newer client"
        var doc = ItemDoc(a.newItem("login"))
            .withTitle("示例网站")
            .withFieldValue("username", "me@example.com")
            .withFieldValue("password", decode<app.nya.password.core.Generated>(generate("")).password)
            .withNewUrl("u_1", "https://example.com/login")
            .with("future_key", JsonPrimitive("kept"))
        val id = a.saveItem(vault.id, null, doc.toJson())
        doc = ItemDoc(decode<ItemView>(a.item(vault.id, id)).content!!)
        assertEquals("kept", doc.str("future_key"))
        // edit again through the tree: still kept
        a.saveItem(vault.id, id, doc.withNotes("备注").toJson())
        assertEquals("kept", ItemDoc(decode<ItemView>(a.item(vault.id, id)).content!!).str("future_key"))

        val r = decode<SyncReport>(a.sync())
        assertEquals(1, r.pushed)
        val hits = decode<List<ItemView>>(a.autofillCandidates("https://www.example.com", emptyList()))
        assertEquals(listOf(id), hits.map { it.itemId })
        assertEquals("示例网站", decode<List<ItemView>>(a.listItems(ListFilter(query = "slwz").toJson())).single().title)

        // app linking + certificate check
        assertTrue(a.linkApp(vault.id, id, "com.example.app", "AA:BB"))
        assertEquals("[]", a.autofillCandidates("androidapp://com.example.app", listOf("ccdd")))
        assertEquals(1, decode<List<ItemView>>(a.autofillCandidates("androidapp://com.example.app", listOf("aabb"))).size)

        // lock / errors with codes / quick unlock
        val quick = a.quickUnlockKey()
        a.lock()
        val e = assertFailsWith<NpwException.Core> { a.unlock("wrong") }
        assertEquals("wrong_password", e.code)
        assertEquals("locked", assertFailsWith<NpwException.Core> { a.listItems("{}") }.code)
        a.unlockWithKey(quick)
        assertTrue(decode<LockState>(a.lockState()).unlocked)
        a.sync()

        // a second device with the Secret Key
        val b = NpwClient(dir.resolve("b.sqlite3").path, randomKey(), "JVM B", "0.0.0-test", "zh-CN")
        b.signIn(server, "test@example.com", "correct horse battery", kit.secretKey)
        b.sync()
        val items = decode<List<ItemView>>(b.listItems("{}"))
        assertEquals(1, items.size)
        val content = decode<ItemView>(b.item(items[0].vaultId, items[0].itemId)).content!!
        assertEquals("kept", content["future_key"]!!.jsonPrimitive.content)
        assertTrue(content["urls"]!!.jsonArray.any { it.jsonObject["url"]!!.jsonPrimitive.content == "androidapp://com.example.app" })
        b.signOut(false)
        a.signOut(false)
        dir.deleteRecursively()
    }
}
