package app.nya.password

import app.nya.password.core.KitQr
import app.nya.password.core.Origins
import app.nya.password.core.Updater
import app.nya.password.core.errorText
import app.nya.password.core.passkeyText
import app.nya.password.ui.ListFilter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    @Test
    fun versionOrder() {
        assertTrue(Updater.newer("0.2.0", "0.1.9"))
        assertTrue(Updater.newer("1.0.0", "0.99.99"))
        assertTrue(Updater.newer("0.1.10", "0.1.9"))
        assertFalse(Updater.newer("0.1.0", "0.1.0"))
        assertFalse(Updater.newer("0.1.0", "0.2.0"))
        // a release is newer than its prereleases, not the other way round
        assertTrue(Updater.newer("0.2.0", "0.2.0-beta.3"))
        assertFalse(Updater.newer("0.2.0-beta.3", "0.2.0"))
        assertTrue(Updater.newer("0.2.0-beta.10", "0.2.0-beta.9"))
        assertFalse(Updater.newer("0.2.0-beta.1", "0.2.0-beta.1"))
        assertTrue(Updater.newer("0.2.0-beta.1", "0.1.5"))
    }

    @Test
    fun releaseParsing() {
        val json = """
            {"tag_name": "v0.3.1", "body": "修复", "html_url": "https://github.com/example/NyaPassword-android/releases/tag/v0.3.1",
             "assets": [
               {"name": "NyaPassword-Android_0.3.1.apk.sha256", "browser_download_url": "https://example.com/a.sha256"},
               {"name": "other.apk", "browser_download_url": "https://example.com/other.apk"},
               {"name": "NyaPassword-Android_0.3.1.apk", "browser_download_url": "https://example.com/a.apk"}
             ]}
        """.trimIndent()
        val r = assertNotNull(Updater.parseRelease(json))
        assertEquals("0.3.1", r.version)
        assertEquals("https://example.com/a.apk", r.apkUrl)
        assertEquals("https://example.com/a.sha256", r.sha256Url)
        assertEquals("修复", r.notes)
        // only an asset with the expected name counts
        assertNull(Updater.parseRelease("""{"tag_name":"v1.0.0","assets":[{"name":"x.apk","browser_download_url":"https://example.com/x.apk"}]}"""))
        assertNull(Updater.parseRelease("not json"))
    }

    @Test
    fun sha256Lines() {
        val h = "a".repeat(64)
        assertEquals(h, Updater.parseSha256("$h  NyaPassword-Android_0.3.1.apk\n"))
        assertEquals(h, Updater.parseSha256(h.uppercase()))
        assertNull(Updater.parseSha256("abc  file"))
        assertEquals("NyaPassword-Android_1.2.3.apk", Updater.assetName("1.2.3"))
    }
}

class OriginsTest {
    @Test
    fun appOriginIsBase64UrlOfTheCertHash() {
        val hash = ByteArray(32) { (it * 8 + 3).toByte() } // includes bytes that need '-' / '_'
        val o = Origins.apkKeyHashOrigin(hash)
        assertTrue(o.startsWith("android:apk-key-hash:"))
        val b64 = o.removePrefix("android:apk-key-hash:")
        assertFalse(b64.contains('=') || b64.contains('+') || b64.contains('/'))
        assertEquals(hash.toList(), java.util.Base64.getUrlDecoder().decode(b64).toList())
        // a known value: sha256("") as the cert hash
        val empty = Origins.sha256(ByteArray(0))
        assertEquals("android:apk-key-hash:47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU", Origins.apkKeyHashOrigin(empty))
    }

    @Test
    fun fingerprintsAndTargets() {
        val b = byteArrayOf(0x0a, 0xff.toByte(), 0x10)
        assertEquals("0aff10", Origins.hex(b))
        assertEquals("0A:FF:10", Origins.colonHex(b))
        assertEquals("0aff10", Origins.normalizeFingerprint("0A:FF:10"))
        assertEquals("androidapp://com.example.app", Origins.appTarget("com.example.app"))
        assertEquals("https://login.example.com", Origins.webTarget(null, "Login.Example.com."))
        assertEquals("http://192.168.1.1", Origins.webTarget("http", "192.168.1.1"))
        assertEquals("https://example.com", Origins.webTarget("javascript", "example.com"))
    }

    @Test
    fun rpIdFromRequests() {
        assertEquals("example.com", Origins.rpIdOf("""{"rp":{"id":"example.com","name":"Ex"},"user":{"id":"AA"}}"""))
        assertEquals("example.com", Origins.rpIdOf("""{"challenge":"AA","rpId":"example.com"}"""))
        assertNull(Origins.rpIdOf("""{"challenge":"AA"}"""))
        assertNull(Origins.rpIdOf("nope"))
    }

    @Test
    fun privilegedBrowsersByCertificate() {
        val list = File("src/main/assets/privileged_browsers.json").readText()
        val chrome = "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"
        assertTrue(Origins.privileged(list, "com.android.chrome", listOf(Origins.normalizeFingerprint(chrome))))
        // same package, another signer: an impostor
        assertFalse(Origins.privileged(list, "com.android.chrome", listOf("00".repeat(32))))
        // a real certificate under another package name
        assertFalse(Origins.privileged(list, "com.example.fake", listOf(Origins.normalizeFingerprint(chrome))))
        assertTrue(listOf("com.microsoft.emmx", "com.brave.browser", "com.sec.android.app.sbrowser").all { list.contains("\"$it\"") })
        assertFalse(Origins.privileged("not json", "com.android.chrome", listOf(chrome)))
    }

    @Test
    fun digitalAssetLinks() {
        val fp = "AB:CD:EF:01"
        val json = """
            [
              {"relation": ["delegate_permission/common.handle_all_urls"],
               "target": {"namespace": "web", "site": "https://example.com"}},
              {"relation": ["delegate_permission/common.get_login_creds"],
               "target": {"namespace": "android_app", "package_name": "com.example.app", "sha256_cert_fingerprints": ["$fp"]}}
            ]
        """.trimIndent()
        assertTrue(Origins.assetLinksAllow(json, "com.example.app", listOf("abcdef01")))
        assertFalse(Origins.assetLinksAllow(json, "com.example.app", listOf("abcdef02")))
        assertFalse(Origins.assetLinksAllow(json, "com.example.other", listOf("abcdef01")))
        val noLogin = json.replace("common.get_login_creds", "common.something_else")
        assertFalse(Origins.assetLinksAllow(noLogin, "com.example.app", listOf("abcdef01")))
        assertFalse(Origins.assetLinksAllow("{}", "com.example.app", listOf("abcdef01")))
    }
}

class KitQrTest {
    @Test
    fun emergencyKitQr() {
        val k = assertNotNull(KitQr.parse("""{"v":1,"server":"https://vault.example.com","login":"me@example.com","secret_key":"A1-ABCDEF-GHJKL"}"""))
        assertEquals("https://vault.example.com", k.server)
        assertEquals("me@example.com", k.login)
        assertEquals("A1-ABCDEF-GHJKL", k.secretKey)
        assertEquals(k, KitQr.parse(k.toJson()))
        assertEquals(KitQr(null, null, "A1-XYZ"), KitQr.parse("  A1-XYZ "))
        assertNull(KitQr.parse("""{"v":2,"secret_key":"A1-X"}"""))
        assertNull(KitQr.parse("""{"v":1,"server":"https://vault.example.com"}"""))
        assertNull(KitQr.parse("hello"))
    }
}

class MiscTest {
    @Test
    fun listFilters() {
        assertEquals("""{"query":""}""", ListFilter().toJson())
        assertEquals("""{"query":"zs","trash":true}""", ListFilter("trash", query = "zs").toJson())
        assertEquals("""{"query":"","vault_id":"v1"}""", ListFilter("vault", "v1").toJson())
        assertEquals("""{"query":"","template":"bank_account"}""", ListFilter("template", "bank_account").toJson())
    }

    @Test
    fun errorMessages() {
        assertEquals("主密码或 Secret Key 不正确", errorText("wrong_password", "x"))
        assertEquals("这个账户在该网站已经有通行密钥了", passkeyText("passkey:InvalidStateError:a credential for this account already exists"))
        assertEquals("来源与网站不匹配，已拒绝", passkeyText("passkey:SecurityError:bad"))
        assertTrue(errorText("network", "network error: timed out").contains("timed out"))
    }
}
