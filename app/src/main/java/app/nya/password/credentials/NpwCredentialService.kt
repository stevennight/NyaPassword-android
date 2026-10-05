package app.nya.password.credentials

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import app.nya.password.autofill.Fill
import app.nya.password.autofill.FillTarget
import app.nya.password.core.Origins
import app.nya.password.core.PasskeyCandidate
import app.nya.password.core.Vault
import app.nya.password.core.decode
import app.nya.password.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Credential Manager provider (Android 14+): passwords and passkeys for apps
 * and privileged browsers. Listing happens here; choosing an entry opens
 * [CredentialActivity], which verifies the user (biometric or master
 * password) and signs / creates through the core.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class NpwCredentialService : CredentialProviderService() {

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val v = vault
        v.scope.launch {
            try {
                v.refresh()
                v.checkAutoLock()
                val response = when {
                    !v.lock.signedIn -> BeginGetCredentialResponse()
                    !v.lock.unlocked -> BeginGetCredentialResponse(
                        authenticationActions = listOf(
                            AuthenticationAction("NyaPassword 已锁定，点按解锁", CredentialActivity.pending(this@NpwCredentialService, CredentialActivity.unlockIntent(this@NpwCredentialService))),
                        ),
                    )
                    else -> withContext(Dispatchers.IO) { entries(this@NpwCredentialService, v, request) }
                }
                if (!cancellationSignal.isCanceled) callback.onResult(response)
            } catch (e: Exception) {
                Log.w(TAG, "begin get failed", e)
                callback.onError(GetCredentialUnknownException(e.message))
            }
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest && request !is BeginCreatePasswordCredentialRequest) {
            callback.onResult(BeginCreateCredentialResponse())
            return
        }
        val v = vault
        v.scope.launch {
            try {
                v.refresh()
                if (!v.lock.signedIn) return@launch callback.onResult(BeginCreateCredentialResponse())
                val what = if (request is BeginCreatePublicKeyCredentialRequest) "通行密钥" else "密码"
                // One entry per vault while unlocked; locked, one entry for the first vault (unlocked in the activity).
                val vaults = if (v.lock.unlocked) v.vaults.map { it.id to it.name } else listOf("" to v.lock.login)
                val entries = vaults.map { (id, name) ->
                    CreateEntry.Builder(name.ifEmpty { "NyaPassword" }, CredentialActivity.pending(this@NpwCredentialService, CredentialActivity.createIntent(this@NpwCredentialService, id)))
                        .setDescription("把$what 保存到 NyaPassword（${v.lock.login}）")
                        .build()
                }
                callback.onResult(BeginCreateCredentialResponse(createEntries = entries))
            } catch (e: Exception) {
                Log.w(TAG, "begin create failed", e)
                callback.onError(CreateCredentialUnknownException(e.message))
            }
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        // Nothing is cached per caller.
        callback.onResult(null)
    }

    companion object {
        const val TAG = "npw-credentials"

        /** Entries for every option of a get request (passwords and passkeys). Blocking. */
        fun entries(context: Context, v: Vault, request: BeginGetCredentialRequest): BeginGetCredentialResponse {
            val info = request.callingAppInfo ?: return BeginGetCredentialResponse()
            val caller = Callers.forListing(context, info)
            val out = ArrayList<CredentialEntry>()
            for (option in request.beginGetCredentialOptions) {
                when (option) {
                    is BeginGetPublicKeyCredentialOption -> {
                        val cands: List<PasskeyCandidate> = runCatching {
                            decode<List<PasskeyCandidate>>(v.callNow { it.passkeyCandidates(caller.json, option.requestJson) })
                        }.getOrDefault(emptyList())
                        cands.forEach { c ->
                            val intent = CredentialActivity.getIntent(context, "passkey", c.vaultId, c.itemId, c.passkeyId)
                            out += PublicKeyCredentialEntry.Builder(
                                context,
                                c.userName.ifEmpty { c.userDisplayName.ifEmpty { c.title } },
                                CredentialActivity.pending(context, intent),
                                option,
                            ).setDisplayName(c.title.ifEmpty { c.rpId }).build()
                        }
                    }
                    is BeginGetPasswordOption -> {
                        val target = when {
                            caller.webOrigin != null -> FillTarget(caller.webOrigin, info.packageName, true, caller.certs, caller.webOrigin)
                            caller.json.contains("\"android_app\"") ->
                                FillTarget(Origins.appTarget(info.packageName), info.packageName, false, caller.certs, info.packageName)
                            else -> null
                        } ?: continue
                        Fill.candidates(v, target).forEach { (item, content) ->
                            val user = content.username ?: return@forEach
                            if (content.password == null) return@forEach
                            val intent = CredentialActivity.getIntent(context, "password", item.vaultId, item.itemId, null)
                            out += PasswordCredentialEntry.Builder(context, user, CredentialActivity.pending(context, intent), option)
                                .setDisplayName(item.title)
                                .build()
                        }
                    }
                }
            }
            return BeginGetCredentialResponse(credentialEntries = out)
        }
    }
}
