package app.nya.password.credentials

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.CallingAppInfo
import app.nya.password.autofill.Browsers
import app.nya.password.core.Origins
import app.nya.password.ffi.passkeyOriginAllowsRp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** A credential request's caller, as the core's `PasskeyCaller` JSON plus what autofill matching needs. */
data class Caller(
    /** `{"kind":"web"|"android_app"|"client_data_hash", …}` for npw-ffi. */
    val json: String,
    /** The page origin when a privileged browser asks for a site. */
    val webOrigin: String?,
    val packageName: String,
    /** SHA-256 hex of the app's signing certificates (current last). */
    val certs: List<String>,
)

class CallerRejected(message: String) : Exception(message)

@RequiresApi(Build.VERSION_CODES.P)
object Callers {
    fun certs(info: CallingAppInfo): List<ByteArray> {
        val si = info.signingInfo
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs.orEmpty().map { Origins.sha256(it.toByteArray()) }
    }

    /** The origin a privileged browser vouches for, or null for ordinary apps. */
    fun browserOrigin(context: Context, info: CallingAppInfo): String? =
        runCatching { info.getOrigin(Browsers.allowlist(context)) }.getOrNull()?.trimEnd('/')

    /**
     * For listing entries (no network): privileged browsers by their origin
     * (or, without one, by the RP ID alone), apps by `android:apk-key-hash`.
     */
    fun forListing(context: Context, info: CallingAppInfo): Caller {
        val certs = certs(info)
        val hex = certs.map(Origins::hex)
        val origin = browserOrigin(context, info)
        return when {
            origin != null -> Caller(web(origin), origin, info.packageName, hex)
            Origins.privileged(Browsers.allowlist(context), info.packageName, hex) ->
                Caller(hashCaller(ByteArray(32)), null, info.packageName, hex)
            else -> Caller(app(certs.lastOrNull()), null, info.packageName, hex)
        }
    }

    /**
     * For signing / creating: privileged browsers must name an origin that may
     * use [rpId]; ordinary apps must be listed in the RP's Digital Asset Links
     * (blocking network call). With a [clientDataHash] from a browser, the
     * browser builds clientDataJSON itself.
     */
    fun forOperation(context: Context, info: CallingAppInfo, rpId: String, clientDataHash: ByteArray?): Caller {
        val certs = certs(info)
        val hex = certs.map(Origins::hex)
        val origin = browserOrigin(context, info)
        if (origin != null) {
            if (!passkeyOriginAllowsRp(origin, rpId)) throw CallerRejected("$origin 不能使用 $rpId 的通行密钥")
            val json = if (clientDataHash != null && clientDataHash.size == 32) hashCaller(clientDataHash) else web(origin)
            return Caller(json, origin, info.packageName, hex)
        }
        if (!Origins.verifyAssetLinks(rpId, info.packageName, hex)) {
            throw CallerRejected("应用 ${info.packageName} 没有得到 $rpId 的授权（Digital Asset Links），不能使用它的通行密钥")
        }
        return Caller(app(certs.lastOrNull()), null, info.packageName, hex)
    }

    private fun web(origin: String) = buildJsonObject {
        put("kind", "web")
        put("origin", origin)
    }.toString()

    private fun app(cert: ByteArray?) = buildJsonObject {
        put("kind", "android_app")
        put("origin", if (cert != null) Origins.apkKeyHashOrigin(cert) else Origins.APK_KEY_HASH)
    }.toString()

    private fun hashCaller(hash: ByteArray) = buildJsonObject {
        put("kind", "client_data_hash")
        put("hash", Base64.getUrlEncoder().withoutPadding().encodeToString(hash))
    }.toString()
}
