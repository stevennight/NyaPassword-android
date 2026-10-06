package app.nya.password.autofill

import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
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
        val started = SystemClock.elapsedRealtime()
        val requestedSpecs: List<InlinePresentationSpec>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.inlineSuggestionsRequest?.inlinePresentationSpecs
        } else {
            null
        }
        var entry = FillLog.Entry(at = System.currentTimeMillis(), inline = describeInline(requestedSpecs), outcome = "")
        fun log(outcome: String) {
            entry = entry.copy(outcome = outcome, ms = SystemClock.elapsedRealtime() - started)
            Log.i(TAG, "fill ${entry.pkg} ${entry.domain.orEmpty()} [${entry.fields}] [${entry.inline}] → ${entry.outcome} (${entry.ms} ms)")
            runCatching { FillLog.add(this, entry) }
        }

        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            log("请求里没有界面结构")
            return callback.onSuccess(null)
        }
        val compat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.flags and FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST != 0
        } else {
            // Android 9 has compatibility mode but no flag for it
            structure.activityComponent?.packageName.orEmpty().let { it in Browsers.BY_PACKAGE || UrlBars.idOf(it) != null }
        }
        // The system hands a response with any inline presentation to the keyboard and then
        // never falls back to the drop-down, whether the keyboard draws it or not. Without
        // inline presentations it shows the drop-down: the user picks (settings).
        val prefs = vault.prefs
        val useInline = if (compat) prefs.inlineCompat else prefs.inlineApps
        val specs = if (useInline) requestedSpecs else null
        val maxInline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && specs != null) {
            request.inlineSuggestionsRequest?.maxSuggestionCount ?: 0
        } else {
            0
        }
        val notes = buildList {
            if (compat) add("兼容模式")
            if (!requestedSpecs.isNullOrEmpty() && !useInline) add("按设置不用输入法候选栏，改用下拉框")
            if (maxInline > 0) add("候选栏最多 $maxInline 条")
        }
        entry = entry.copy(inline = (notes + entry.inline).filter { it.isNotEmpty() }.joinToString(" · "))
        val screen = runCatching { StructureParser.parse(structure, compat) }.getOrElse {
            Log.w(TAG, "parse failed", it)
            log("解析界面失败：${it.message ?: it.javaClass.simpleName}")
            return callback.onSuccess(null)
        }
        entry = entry.copy(pkg = screen.packageName, domain = screen.webDomain, fields = FillLog.fields(screen), views = FillLog.views(screen))
        if (screen.urlBarAtBottom) {
            entry = entry.copy(inline = listOf(entry.inline, "地址栏在底部：建议可能显示为空白或一闪而过，请在浏览器设置里把地址栏移到顶部").filter { it.isNotEmpty() }.joinToString(" · "))
        }
        if (screen.packageName == packageName) return callback.onSuccess(null)
        if (screen.classification.isEmpty) {
            log("没有识别出用户名 / 密码 / 验证码输入框")
            return callback.onSuccess(null)
        }
        // The system gives up after a few seconds; record it when our answer comes too late.
        cancellationSignal.setOnCancelListener { log("系统已取消请求（可能是超时）") }
        val v = vault
        v.scope.launch {
            var outcome = ""
            val response = runCatching {
                val target = withContext(Dispatchers.IO) { FillTarget.resolve(this@NpwAutofillService, screen) }
                if (target == null) {
                    outcome = "浏览器没有提供网页地址，不填写"
                    return@runCatching null
                }
                v.refresh()
                v.checkAutoLock()
                when {
                    !v.lock.signedIn -> null.also { outcome = "NyaPassword 尚未登录账号" }
                    !v.lock.unlocked -> Fill.lockedResponse(this@NpwAutofillService, screen, target, specs, maxInline)
                        .also { outcome = "已锁定：提供“点按解锁”（${target.target}）" }
                    else -> {
                        var stats = "已解锁"
                        withContext(Dispatchers.IO) {
                            Fill.response(this@NpwAutofillService, v, screen, target, specs, maxInline) { n, err ->
                                stats = if (err == null && n == 0 && screen.classification.guessed) "已解锁：猜测的用户名框没有匹配条目，不显示（${target.target}）"
                                else if (err == null) "已解锁：$n 个匹配条目 + 搜索（${target.target}）"
                                else "已解锁，但读取匹配条目失败：${err.message ?: err.javaClass.simpleName}；只提供搜索"
                            }
                        }.also {
                            outcome = stats
                            v.touch()
                        }
                    }
                }
            }.getOrElse {
                Log.w(TAG, "fill failed", it)
                outcome = "出错：${it.message ?: it.javaClass.simpleName}"
                null
            }
            if (cancellationSignal.isCanceled) return@launch
            val sent = runCatching { callback.onSuccess(response) }
            log(sent.exceptionOrNull()?.let { "$outcome；回传给系统失败：${it.message ?: it.javaClass.simpleName}" } ?: outcome)
        }
    }

    /** What the keyboard asked for: inline suggestions, and whether it draws our style. */
    private fun describeInline(specs: List<InlinePresentationSpec>?): String {
        if (specs.isNullOrEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val ok = specs.count { Fill.inlineV1(it) }
        return "输入法请求内嵌建议 ${specs.size} 条，可显示 $ok 条"
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        // (at save time the inputs have text, so compatibility mode needs no special case)
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
