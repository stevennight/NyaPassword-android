package app.nya.password.autofill

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.widget.Toast
import android.widget.inline.InlinePresentationSpec
import app.nya.password.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The autofill service (apps and browsers): parses the screen, matches the
 * vault with the core's URL rules (apps by package and signing certificate),
 * and answers with datasets — or, when locked, with one "tap to unlock"
 * entry that opens [AutofillActivity]. No accessibility service.
 */
class NpwAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val screen = runCatching { StructureParser.parse(structure) }.getOrElse {
            Log.w(TAG, "parse failed", it)
            return callback.onSuccess(null)
        }
        if (screen.classification.isEmpty || screen.packageName == packageName) return callback.onSuccess(null)
        val specs: List<InlinePresentationSpec>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.inlineSuggestionsRequest?.inlinePresentationSpecs
        } else {
            null
        }
        val v = vault
        v.scope.launch {
            val response = runCatching {
                val target = withContext(Dispatchers.IO) { FillTarget.resolve(this@NpwAutofillService, screen) }
                    ?: return@runCatching null
                v.refresh()
                v.checkAutoLock()
                when {
                    !v.lock.signedIn -> null
                    !v.lock.unlocked -> Fill.lockedResponse(this@NpwAutofillService, screen, target, specs)
                    else -> withContext(Dispatchers.IO) { Fill.response(this@NpwAutofillService, v, screen, target, specs) }
                        .also { v.touch() }
                }
            }.onFailure { Log.w(TAG, "fill failed", it) }.getOrNull()
            if (!cancellationSignal.isCanceled) {
                runCatching { callback.onSuccess(response) }
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        val screen = runCatching { StructureParser.parse(structure) }.getOrNull() ?: return callback.onSuccess()
        val password = screen.valueOf(Role.PASSWORD)
        if (password.isNullOrEmpty()) return callback.onSuccess()
        val username = screen.valueOf(Role.USERNAME).orEmpty()
        val target = FillTarget.resolve(this, screen) ?: return callback.onSuccess()
        val token = PendingSaves.put(PendingSave(target, username, password, screen.classification.newPassword))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The user picks "update" or "new" (after unlocking) in our activity.
            callback.onSuccess(AutofillActivity.saveSender(this, token))
        } else {
            val v = vault
            v.scope.launch {
                v.refresh()
                if (!v.lock.unlocked) {
                    PendingSaves.take(token)
                    Toast.makeText(this@NpwAutofillService, "NyaPassword 已锁定，未保存", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val save = PendingSaves.take(token) ?: return@launch
                val msg = runCatching { withContext(Dispatchers.IO) { Saver.saveAuto(v, save) } }.getOrElse { "保存失败：${it.message}" }
                Toast.makeText(this@NpwAutofillService, msg, Toast.LENGTH_LONG).show()
            }
            callback.onSuccess()
        }
    }

    companion object {
        const val TAG = "npw-autofill"
    }
}

/** What a save request carries to [AutofillActivity] (kept in memory, not in the intent). */
data class PendingSave(val target: FillTarget, val username: String, val password: String, val newPassword: Boolean)

object PendingSaves {
    private val map = HashMap<String, PendingSave>()

    @Synchronized
    fun put(s: PendingSave): String {
        val token = java.util.UUID.randomUUID().toString()
        map[token] = s
        return token
    }

    @Synchronized
    fun take(token: String): PendingSave? = map.remove(token)

    @Synchronized
    fun peek(token: String): PendingSave? = map[token]
}
