package app.nya.password.credentials

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.credentials.provider.PendingIntentHandler
import app.nya.password.autofill.Fill
import app.nya.password.autofill.FillTarget
import app.nya.password.autofill.PendingSave
import app.nya.password.autofill.Saver
import app.nya.password.core.Content
import app.nya.password.core.CoreFailure
import app.nya.password.core.ItemView
import app.nya.password.core.Origins
import app.nya.password.core.PasskeyCreated
import app.nya.password.core.PlainJson
import app.nya.password.core.decode
import app.nya.password.core.errorText
import app.nya.password.ui.NpwTheme
import app.nya.password.ui.SecureActivity
import app.nya.password.ui.UnlockPanel
import app.nya.password.ui.muted
import app.nya.password.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Completes a Credential Manager request after the user chose a NyaPassword
 * entry: verifies the user (biometric or master password — even when the
 * vault is unlocked), checks the caller (privileged browser origin, or the
 * app's Digital Asset Links), then signs / creates through the core.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialActivity : SecureActivity() {
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        setResult(RESULT_CANCELED)
        setContent {
            NpwTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) { Screen() }
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        val v = vault
        var verified by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var choices by remember { mutableStateOf<List<Pair<ItemView, Content>>?>(null) }
        LaunchedEffect(Unit) {
            runCatching { v.refresh() }
            v.checkAutoLock()
            if (!v.lock.signedIn) finish()
        }
        val subtitle = when (mode) {
            MODE_GET -> "确认是你本人后登录"
            MODE_CREATE -> "确认是你本人后保存"
            else -> "解锁后显示可用的密码和通行密钥"
        }
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!verified) {
                UnlockPanel(this@CredentialActivity, if (mode == MODE_UNLOCK) "解锁 NyaPassword" else "验证身份", subtitle, verify = mode != MODE_UNLOCK) {
                    verified = true
                    busy = true
                    v.scope.launch {
                        try {
                            when (mode) {
                                MODE_GET -> get()
                                MODE_CREATE -> choices = prepareCreate()
                                else -> unlocked()
                            }
                        } catch (e: Exception) {
                            Log.w(NpwCredentialService.TAG, "request failed", e)
                            error = message(e)
                        } finally {
                            busy = false
                        }
                    }
                }
                TextButton(onClick = { finish() }) { Text("取消") }
                return@Column
            }
            if (busy) CircularProgressIndicator()
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = { fail(error) }) { Text("关闭") }
            }
            val list = choices
            if (mode == MODE_CREATE && list != null && !busy && error.isEmpty()) {
                Text("保存通行密钥", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("可以加到已有的登录项，也可以新建一个。", color = muted, fontSize = 13.sp)
                list.forEach { (item, c) ->
                    OutlinedButton(onClick = {
                        busy = true
                        v.scope.launch {
                            runCatching { createPasskey(item.vaultId, item.itemId) }.onFailure { error = message(it) }
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("加到“${item.title}”${c.username?.let { "（$it）" } ?: ""}") }
                }
                Button(onClick = {
                    busy = true
                    v.scope.launch {
                        runCatching { createPasskey(null, null) }.onFailure { error = message(it) }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("新建登录项") }
                TextButton(onClick = { finish() }) { Text("取消") }
            }
        }
    }

    private fun message(e: Throwable): String {
        if (e is CoreFailure) lastFailure = e
        return when (e) {
            is CallerRejected -> e.message ?: "来源不被允许"
            else -> errorText(e)
        }
    }

    /** Authentication action: unlocked now, hand back the entries. */
    private suspend fun unlocked() {
        val req = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent) ?: return finish()
        val response = withContext(Dispatchers.IO) { NpwCredentialService.entries(this@CredentialActivity, vault, req) }
        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(result, response)
        setResult(RESULT_OK, result)
        finish()
    }

    private suspend fun get() {
        val v = vault
        val req = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: return finish()
        val vaultId = intent.getStringExtra(EXTRA_VAULT) ?: return finish()
        val itemId = intent.getStringExtra(EXTRA_ITEM) ?: return finish()
        val credential = when (intent.getStringExtra(EXTRA_KIND)) {
            "passkey" -> {
                val option = req.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull() ?: return finish()
                val rpId = Origins.rpIdOf(option.requestJson) ?: throw IllegalArgumentException("请求里没有 rpId")
                val json = withContext(Dispatchers.IO) {
                    val caller = Callers.forOperation(this@CredentialActivity, req.callingAppInfo, rpId, option.clientDataHash)
                    v.callNow { it.passkeyGet(caller.json, option.requestJson, vaultId, itemId, intent.getStringExtra(EXTRA_PASSKEY) ?: "") }
                }
                PublicKeyCredential(json)
            }
            else -> {
                val c = Content.of(v.item(vaultId, itemId).content ?: return finish())
                PasswordCredential(c.username ?: "", c.password ?: throw IllegalStateException("这个条目没有密码"))
            }
        }
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(credential))
        setResult(RESULT_OK, result)
        v.touch()
        v.syncSoon()
        finish()
    }

    /** Password: saved right away. Passkey: returns the logins it could be added to (empty = create directly). */
    private suspend fun prepareCreate(): List<Pair<ItemView, Content>>? {
        val v = vault
        val req = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: run { finish(); return null }
        when (val r = req.callingRequest) {
            is CreatePasswordRequest -> {
                val info = req.callingAppInfo
                val origin = Callers.browserOrigin(this, info)
                val certs = Callers.certs(info).map(Origins::hex)
                val target = if (origin != null) {
                    FillTarget(origin, info.packageName, true, certs, origin.substringAfter("://"))
                } else {
                    FillTarget(Origins.appTarget(info.packageName), info.packageName, false, certs, Fill.appLabel(this, info.packageName))
                }
                withContext(Dispatchers.IO) {
                    val save = PendingSave(target, r.id, r.password, false)
                    val same = Saver.matches(v, save).filter { (_, c) -> (c.username ?: "") == r.id }
                    if (same.isNotEmpty()) Saver.update(v, same.first().first, save) else Saver.create(v, vaultId(), save)
                }
                val result = Intent()
                PendingIntentHandler.setCreateCredentialResponse(result, CreatePasswordResponse())
                setResult(RESULT_OK, result)
                v.edited()
                finish()
                return null
            }
            is CreatePublicKeyCredentialRequest -> {
                val rpId = Origins.rpIdOf(r.requestJson) ?: throw IllegalArgumentException("请求里没有 rp.id")
                val userName = runCatching {
                    ((PlainJson.parseToJsonElement(r.requestJson) as JsonObject)["user"] as JsonObject)["name"].let { (it as? JsonPrimitive)?.contentOrNull }
                }.getOrNull().orEmpty()
                val existing = withContext(Dispatchers.IO) {
                    Fill.candidates(v, FillTarget("https://$rpId", "", true, emptyList(), rpId), limit = 20)
                }.sortedByDescending { (_, c) -> (c.username ?: "") == userName }
                if (existing.isEmpty()) {
                    createPasskey(null, null)
                    return null
                }
                return existing
            }
            else -> {
                fail("不支持的请求类型")
                return null
            }
        }
    }

    private fun vaultId(): String =
        intent.getStringExtra(EXTRA_VAULT)?.takeIf { it.isNotEmpty() } ?: vault.vaults.firstOrNull()?.id ?: throw IllegalStateException("没有可用的保险库")

    private suspend fun createPasskey(targetVault: String?, targetItem: String?) {
        val v = vault
        val req = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: return finish()
        val r = req.callingRequest as? CreatePublicKeyCredentialRequest ?: return finish()
        val rpId = Origins.rpIdOf(r.requestJson) ?: throw IllegalArgumentException("请求里没有 rp.id")
        val created: PasskeyCreated = withContext(Dispatchers.IO) {
            val caller = Callers.forOperation(this@CredentialActivity, req.callingAppInfo, rpId, r.clientDataHash)
            decode(v.callNow { it.passkeyCreate(caller.json, r.requestJson, targetVault, targetItem, targetVault ?: vaultId()) })
        }
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(created.response.toString()))
        setResult(RESULT_OK, result)
        v.edited()
        v.say("通行密钥已保存")
        finish()
    }

    /** Reports an error to the caller (the system shows it). */
    private fun fail(msg: String) {
        val result = Intent()
        val dom = domError(msg)
        when (mode) {
            MODE_GET -> PendingIntentHandler.setGetCredentialException(
                result,
                if (dom != null) GetPublicKeyCredentialDomException(dom, msg) else GetCredentialUnknownException(msg),
            )
            MODE_CREATE -> PendingIntentHandler.setCreateCredentialException(
                result,
                if (dom != null) CreatePublicKeyCredentialDomException(dom, msg) else CreateCredentialUnknownException(msg),
            )
            else -> return finish()
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private var lastFailure: CoreFailure? = null

    private fun domError(msg: String): DomError? {
        val detail = lastFailure?.detail ?: msg
        return when {
            detail.contains("InvalidStateError") || msg.contains("已经有通行密钥") -> InvalidStateError()
            detail.contains("SecurityError") || msg.contains("不能使用") || msg.contains("授权") -> SecurityError()
            detail.contains("NotSupportedError") -> NotSupportedError()
            detail.contains("NotAllowedError") -> NotAllowedError()
            else -> null
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_VAULT = "vault"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_PASSKEY = "passkey"
        const val MODE_GET = "get"
        const val MODE_CREATE = "create"
        const val MODE_UNLOCK = "unlock"
        private var requestCode = 1

        fun getIntent(context: Context, kind: String, vaultId: String, itemId: String, passkeyId: String?): Intent =
            Intent(context, CredentialActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_GET)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_VAULT, vaultId)
                .putExtra(EXTRA_ITEM, itemId)
                .putExtra(EXTRA_PASSKEY, passkeyId)

        fun createIntent(context: Context, vaultId: String): Intent =
            Intent(context, CredentialActivity::class.java).putExtra(EXTRA_MODE, MODE_CREATE).putExtra(EXTRA_VAULT, vaultId)

        fun unlockIntent(context: Context): Intent = Intent(context, CredentialActivity::class.java).putExtra(EXTRA_MODE, MODE_UNLOCK)

        /** Credential Manager adds the request to the intent, so it must be mutable. */
        fun pending(context: Context, i: Intent): PendingIntent =
            PendingIntent.getActivity(context, requestCode++, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
