package app.nya.password.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Who is calling: app signing certificates, WebAuthn origins of apps,
 * privileged browsers and Digital Asset Links. Pure functions (tested on the
 * JVM) plus the PackageManager / network parts.
 */
object Origins {
    const val APK_KEY_HASH = "android:apk-key-hash:"

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** `AB:CD:…` as Digital Asset Links and Google's allowlist write fingerprints. */
    fun colonHex(b: ByteArray): String = b.joinToString(":") { "%02X".format(it) }

    /** Fingerprint text in any of those forms → lower-case hex without separators. */
    fun normalizeFingerprint(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** WebAuthn origin of an Android app: `android:apk-key-hash:<base64url(sha256(cert))>`. */
    fun apkKeyHashOrigin(certSha256: ByteArray): String =
        APK_KEY_HASH + Base64.getUrlEncoder().withoutPadding().encodeToString(certSha256)

    /** What autofill matches for an app: `androidapp://<package>`. */
    fun appTarget(packageName: String): String = "androidapp://$packageName"

    /** What autofill matches for a page in a browser. */
    fun webTarget(scheme: String?, domain: String): String {
        val s = scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: "https"
        return "$s://${domain.trim().trimEnd('.').lowercase()}"
    }

    /** The RP ID in a WebAuthn request JSON (`rp.id` for create, `rpId` for get). */
    fun rpIdOf(requestJson: String): String? = runCatching {
        val o = PlainJson.parseToJsonElement(requestJson) as JsonObject
        ((o["rp"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
            ?: (o["rpId"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** Google's privileged-apps JSON format: is ([packageName], one of [certsSha256Hex]) listed? */
    fun privileged(allowlistJson: String, packageName: String, certsSha256Hex: List<String>): Boolean = runCatching {
        val apps = (PlainJson.parseToJsonElement(allowlistJson) as JsonObject)["apps"] as JsonArray
        val have = certsSha256Hex.map(::normalizeFingerprint).toSet()
        apps.any { a ->
            val info = (a as JsonObject)["info"] as? JsonObject ?: return@any false
            (info["package_name"] as? JsonPrimitive)?.contentOrNull == packageName &&
                ((info["signatures"] as? JsonArray) ?: JsonArray(emptyList())).any { s ->
                    val fp = ((s as JsonObject)["cert_fingerprint_sha256"] as? JsonPrimitive)?.contentOrNull ?: ""
                    normalizeFingerprint(fp) in have
                }
        }
    }.getOrDefault(false)

    private val LOGIN_RELATIONS = setOf("delegate_permission/common.get_login_creds", "delegate_permission/common.handle_all_urls")

    /**
     * Does a site's `/.well-known/assetlinks.json` let app [packageName] signed
     * with one of [certsSha256Hex] use its logins (Digital Asset Links)?
     */
    fun assetLinksAllow(json: String, packageName: String, certsSha256Hex: List<String>): Boolean = runCatching {
        val have = certsSha256Hex.map(::normalizeFingerprint).toSet()
        (PlainJson.parseToJsonElement(json) as JsonArray).any { st ->
            val o = st as? JsonObject ?: return@any false
            val rel = (o["relation"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val t = o["target"] as? JsonObject ?: return@any false
            rel.any { it in LOGIN_RELATIONS } &&
                (t["namespace"] as? JsonPrimitive)?.contentOrNull == "android_app" &&
                (t["package_name"] as? JsonPrimitive)?.contentOrNull == packageName &&
                ((t["sha256_cert_fingerprints"] as? JsonArray) ?: JsonArray(emptyList())).any {
                    normalizeFingerprint((it as? JsonPrimitive)?.contentOrNull ?: "") in have
                }
        }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- Android parts

    /** SHA-256 of the signing certificates of an installed package (empty if unknown). */
    fun signingCerts(context: Context, packageName: String): List<ByteArray> = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return@runCatching emptyList()
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            sigs.map { sha256(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { sha256(it.toByteArray()) }
        }
    }.getOrDefault(emptyList())

    fun privilegedAllowlist(context: Context): String =
        context.assets.open("privileged_browsers.json").bufferedReader().use { it.readText() }

    private val dalHttp by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
    private val dalCache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** Fetches `https://<rpId>/.well-known/assetlinks.json` (cached for an hour) and checks it. Blocking. */
    fun verifyAssetLinks(rpId: String, packageName: String, certsSha256Hex: List<String>): Boolean {
        val now = System.currentTimeMillis()
        val cached = dalCache[rpId]?.takeIf { now - it.first < 3_600_000 }?.second
        val json = cached ?: runCatching {
            dalHttp.newCall(Request.Builder().url("https://$rpId/.well-known/assetlinks.json").build()).execute().use { r ->
                if (!r.isSuccessful) "" else r.body?.string().orEmpty()
            }
        }.getOrDefault("").also { if (it.isNotEmpty()) dalCache[rpId] = now to it }
        return json.isNotEmpty() && assetLinksAllow(json, packageName, certsSha256Hex)
    }
}
