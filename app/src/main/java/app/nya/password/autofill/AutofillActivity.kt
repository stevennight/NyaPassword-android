@file:Suppress("DEPRECATION")

package app.nya.password.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import android.widget.inline.InlinePresentationSpec
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.nya.password.core.Content
import app.nya.password.core.ItemView
import app.nya.password.core.decode
import app.nya.password.core.errorText
import app.nya.password.ui.ItemRow
import app.nya.password.ui.ListFilter
import app.nya.password.ui.NpwTheme
import app.nya.password.ui.SecureActivity
import app.nya.password.ui.UnlockPanel
import app.nya.password.ui.muted
import app.nya.password.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opened by the autofill framework:
 * - [MODE_UNLOCK]: the vault was locked — unlock, then hand back the datasets;
 * - [MODE_PICK]: "search NyaPassword" — pick any item; for apps the choice is
 *   remembered (package + signing certificate) so it is suggested next time;
 * - [MODE_SAVE]: a login was submitted — update an item or create one;
 * - [MODE_REPROMPT]: the user picked an item marked "使用前需要验证" — verify
 *   (biometrics / master password), then hand back its dataset.
 *
 * Items marked "使用前需要验证" are never filled, picked or updated here
 * without the user verifying in this activity.
 */
class AutofillActivity : SecureActivity() {
    private var mode = MODE_UNLOCK
    private var target: FillTarget? = null
    private var structure: AssistStructure? = null
    private var specs: List<InlinePresentationSpec>? = null
    private var saveToken: String? = null
    private var repromptItem: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        target = FillTarget.from(intent.getBundleExtra(EXTRA_TARGET))
        structure = intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            specs = intent.getParcelableArrayListExtra(EXTRA_SPECS)
        }
        saveToken = intent.getStringExtra(EXTRA_SAVE)
        val rv = intent.getStringExtra(EXTRA_VAULT)
        val ri = intent.getStringExtra(EXTRA_ITEM)
        if (rv != null && ri != null) repromptItem = rv to ri
        setResult(RESULT_CANCELED)
        setContent {
            NpwTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) { Content() }
                }
            }
        }
    }

    @Composable
    private fun Content() {
        val v = vault
        // the known state first: an unlocked vault must not flash the unlock panel (it may start biometrics)
        var unlocked by remember { mutableStateOf(v.lock.unlocked) }
        LaunchedEffect(Unit) {
            runCatching { v.refresh() }
            v.checkAutoLock()
            unlocked = v.lock.unlocked
            if (!v.lock.signedIn) finish()
            if (unlocked && mode == MODE_UNLOCK) finishUnlock()
        }
        val t = target
        if (!unlocked) {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                UnlockPanel(
                    this@AutofillActivity,
                    "解锁 NyaPassword",
                    when (mode) {
                        MODE_SAVE -> "保存 ${t?.label ?: ""} 的登录信息"
                        else -> "填写 ${t?.label ?: ""}"
                    },
                ) {
                    unlocked = true
                    if (mode == MODE_UNLOCK) finishUnlock()
                    // the master password / biometrics just now are the verification
                    if (mode == MODE_REPROMPT) finishReprompt()
                }
                TextButton(onClick = { finish() }) { Text("取消") }
            }
            return
        }
        when (mode) {
            MODE_PICK -> Picker()
            MODE_SAVE -> SaveScreen()
            MODE_REPROMPT -> RepromptScreen()
            else -> Text("正在填写…", Modifier.padding(32.dp), color = muted)
        }
    }

    @Composable
    private fun RepromptScreen() {
        var verified by remember { mutableStateOf(false) }
        if (verified) {
            Text("正在填写…", Modifier.padding(32.dp), color = muted)
            return
        }
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            UnlockPanel(this@AutofillActivity, "此条目需要验证", "填写 ${target?.label ?: ""} 前，请验证身份", verify = true) {
                verified = true
                finishReprompt()
            }
            TextButton(onClick = { finish() }) { Text("取消") }
        }
    }

    /** Dataset authentication: the item is still a match for this app / site; return its real dataset. */
    private fun finishReprompt() {
        val v = vault
        val s = structure ?: return finish()
        val t = target ?: return finish()
        val (vaultId, itemId) = repromptItem ?: return finish()
        v.scope.launch {
            val dataset = runCatching {
                withContext(Dispatchers.IO) {
                    val (view, c) = Fill.candidates(v, t).firstOrNull { it.first.vaultId == vaultId && it.first.itemId == itemId }
                        ?: return@withContext null
                    Fill.dataset(this@AutofillActivity, StructureParser.parse(s, t.compat), view, c, null)
                }
            }.getOrNull()
            if (dataset != null) {
                v.touch()
                setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
            }
            finish()
        }
    }

    /** Whole-response authentication: return the datasets that the locked response stood for. */
    private fun finishUnlock() {
        val v = vault
        val s = structure ?: return finish()
        val t = target ?: return finish()
        v.scope.launch {
            val response = runCatching {
                withContext(Dispatchers.IO) {
                    val screen = StructureParser.parse(s, t.compat)
                    Fill.response(this@AutofillActivity, v, screen, t, specs)
                }
            }.getOrNull()
            if (response != null) {
                setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
            }
            finish()
        }
    }

    @Composable
    private fun Picker() {
        val v = vault
        val t = target
        var query by remember { mutableStateOf("") }
        var items by remember { mutableStateOf<List<ItemView>>(emptyList()) }
        var verifyFor by remember { mutableStateOf<ItemView?>(null) }
        LaunchedEffect(query) {
            items = runCatching { v.items(ListFilter(query = query).toJson()) }.getOrDefault(emptyList())
        }
        verifyFor?.let { item ->
            // an item marked "使用前需要验证": verify before it is filled
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                UnlockPanel(this@AutofillActivity, "此条目需要验证", "填写“${item.title}”前，请验证身份", verify = true) {
                    verifyFor = null
                    pick(item)
                }
                TextButton(onClick = { verifyFor = null }) { Text("返回") }
            }
            return
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text("选择要填写的条目", Modifier.padding(top = 16.dp, start = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (t != null) {
                Text(
                    if (t.browser) "网站：${t.label}" else "应用：${t.label}（${t.packageName}）。选中后会记住这个应用和它的签名证书，下次直接建议。",
                    Modifier.padding(4.dp), fontSize = 13.sp, color = muted,
                )
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                placeholder = { Text("搜索（支持拼音、首字母）") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.vaultId + it.itemId }) { item ->
                    ItemRow(item) { if (item.reprompt) verifyFor = item else pick(item) }
                    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            TextButton(onClick = { finish() }, Modifier.align(Alignment.End)) { Text("取消") }
        }
    }

    private fun pick(item: ItemView) {
        val v = vault
        val s = structure ?: return finish()
        val t = target ?: return finish()
        v.scope.launch {
            try {
                val dataset = withContext(Dispatchers.IO) {
                    val full: ItemView = decode(v.callNow { it.item(item.vaultId, item.itemId) })
                    val c = Content.of(full.content ?: error("no content"))
                    if (!t.browser) Saver.linkIfApp(v, item.vaultId, item.itemId, t)
                    Fill.dataset(this@AutofillActivity, StructureParser.parse(s, t.compat), full, c, null)
                }
                if (dataset == null) {
                    v.say("这个条目没有可以填写的用户名或密码")
                    return@launch
                }
                if (!t.browser) v.edited()
                v.touch()
                setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
                finish()
            } catch (e: Exception) {
                v.say(errorText(e))
            }
        }
    }

    @Composable
    private fun SaveScreen() {
        val v = vault
        val token = saveToken
        val save = token?.let { PendingSaves.peek(it) }
        if (save == null) {
            LaunchedEffect(Unit) { finish() }
            return
        }
        var matches by remember { mutableStateOf<List<Pair<ItemView, Content>>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var verifyFor by remember { mutableStateOf<ItemView?>(null) }
        LaunchedEffect(Unit) { matches = withContext(Dispatchers.IO) { runCatching { Saver.matches(v, save) }.getOrDefault(emptyList()) } }

        fun run(block: () -> String) {
            busy = true
            v.scope.launch {
                try {
                    val msg = withContext(Dispatchers.IO) { block() }
                    PendingSaves.take(token)
                    v.edited()
                    v.say(msg)
                    android.widget.Toast.makeText(this@AutofillActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    error = errorText(e)
                    busy = false
                }
            }
        }

        verifyFor?.let { item ->
            // updating an item marked "使用前需要验证" needs the user verified
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                UnlockPanel(this@AutofillActivity, "此条目需要验证", "更新“${item.title}”前，请验证身份", verify = true) {
                    verifyFor = null
                    run { Saver.update(v, item, save); "已更新“${item.title}”" }
                }
                TextButton(onClick = { verifyFor = null }) { Text("返回") }
            }
            return
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存到 NyaPassword", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${if (save.target.browser) "网站" else "应用"}：${save.target.label}", color = muted)
            Text("用户名：${save.username.ifEmpty { "（无）" }}")
            Text("密码：${"•".repeat(save.password.length.coerceAtMost(12))}")
            val list = matches
            if (list == null) {
                Text("正在查找已有条目…", color = muted)
            } else {
                list.forEach { (item, c) ->
                    val same = (c.username ?: "") == save.username
                    // a "使用前需要验证" item does not tell whether the submitted password is its own
                    val unchanged = !item.reprompt && same && c.password == save.password
                    OutlinedButton(
                        onClick = { if (item.reprompt) verifyFor = item else run { Saver.update(v, item, save); "已更新“${item.title}”" } },
                        enabled = !busy && !unchanged,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (unchanged) "“${item.title}”已是这个密码"
                            else "更新“${item.title}”${c.username?.let { "（$it）" } ?: ""}${if (item.reprompt) "（需要验证）" else ""}",
                        )
                    }
                }
                Button(
                    onClick = {
                        run {
                            val vaultId = v.vaults.firstOrNull()?.id ?: error("没有可用的保险库")
                            Saver.create(v, vaultId, save)
                            "已保存到 NyaPassword"
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("新建登录项") }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    PendingSaves.take(token)
                    finish()
                }) { Text("不保存") }
            }
        }
    }

    companion object {
        const val MODE_UNLOCK = "unlock"
        const val MODE_PICK = "pick"
        const val MODE_SAVE = "save"
        const val MODE_REPROMPT = "reprompt"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_VAULT = "vault_id"
        private const val EXTRA_ITEM = "item_id"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_SPECS = "inline_specs"
        private const val EXTRA_SAVE = "save_token"
        private var requestCode = 1

        /** An IntentSender for the autofill framework (mutable: the system adds the assist structure). */
        fun sender(context: Context, mode: String, target: FillTarget, specs: List<InlinePresentationSpec>?): IntentSender {
            val i = Intent(context, AutofillActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_TARGET, target.toBundle())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && specs != null) i.putParcelableArrayListExtra(EXTRA_SPECS, ArrayList(specs))
            return pending(context, i).intentSender
        }

        /** Dataset authentication for an item marked "使用前需要验证". */
        fun repromptSender(context: Context, target: FillTarget, vaultId: String, itemId: String): IntentSender {
            val i = Intent(context, AutofillActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_REPROMPT)
                .putExtra(EXTRA_TARGET, target.toBundle())
                .putExtra(EXTRA_VAULT, vaultId)
                .putExtra(EXTRA_ITEM, itemId)
            return pending(context, i).intentSender
        }

        fun saveSender(context: Context, token: String): IntentSender {
            val i = Intent(context, AutofillActivity::class.java).putExtra(EXTRA_MODE, MODE_SAVE).putExtra(EXTRA_SAVE, token)
            return pending(context, i).intentSender
        }

        private fun pending(context: Context, i: Intent): PendingIntent {
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getActivity(context, requestCode++, i, flags)
        }
    }
}
