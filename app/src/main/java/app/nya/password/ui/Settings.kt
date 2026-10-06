package app.nya.password.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.credentials.CredentialManager
import app.nya.password.BuildConfig
import app.nya.password.autofill.Fill
import app.nya.password.autofill.FillLog
import app.nya.password.MainActivity
import app.nya.password.core.AuditEntry
import app.nya.password.core.DeviceRecord
import app.nya.password.core.EmergencyKit
import app.nya.password.core.Updater
import app.nya.password.core.decode
import app.nya.password.core.errorText
import app.nya.password.ffi.coreVersion
import app.nya.password.vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOCK_OPTIONS = listOf(1 to "1 分钟", 5 to "5 分钟", 10 to "10 分钟", 30 to "30 分钟", 60 to "1 小时", 240 to "4 小时", 0 to "从不（仅锁屏 / 重启时）")

/** The settings pages: the desktop / web client's categories that apply on Android, in the same order. */
private enum class SettingsCategory(val title: String, val summary: String) {
    GENERAL("通用", "外观、版本与更新"),
    SECURITY("安全与解锁", "自动锁定、生物识别、PIN"),
    AUTOFILL("自动填充与通行密钥", "自动填充服务与设置引导"),
    ACCOUNT("账户", "紧急恢复包、同步、主密码、退出"),
    VAULTS("保险库", "保险库列表、新建与改名"),
    DEVICES("设备与日志", "登录的设备与账户日志"),
}

/**
 * The category to show again when the settings come back: a page on top of
 * the tabs (the setup guide) takes this screen out of the composition.
 */
private var reopenCategory: SettingsCategory? = null

/** Account log actions (the web vault's `ACTIONS`). */
private val AUDIT_ACTIONS = mapOf(
    "register" to "注册", "login" to "登录", "login_failed" to "登录失败", "password_change" to "修改主密码",
    "device_revoke" to "移除设备", "vault_create" to "新建保险库", "import" to "导入", "purge" to "永久删除",
)

/**
 * Settings: a list of categories, then one category's groups (side by side
 * on wide screens). The selected category survives configuration changes.
 */
@Composable
fun SettingsScreen(activity: MainActivity, pad: PaddingValues) {
    val v = activity.vault
    val scope = rememberCoroutineScope()
    var autoLock by remember { mutableIntStateOf(v.prefs.autoLockMinutes) }
    var screenOff by remember { mutableStateOf(v.prefs.lockOnScreenOff) }
    var bio by remember { mutableStateOf(v.prefs.biometric) }
    var checkUpdates by remember { mutableStateOf(v.prefs.checkUpdates) }
    var kit by remember { mutableStateOf<EmergencyKit?>(null) }
    var devices by remember { mutableStateOf<List<DeviceRecord>?>(null) }
    var audit by remember { mutableStateOf<List<AuditEntry>?>(null) }
    var revoke by remember { mutableStateOf<DeviceRecord?>(null) }
    var changePw by remember { mutableStateOf(false) }
    var signOut by remember { mutableStateOf(false) }
    var newVault by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var autofillOn by remember { mutableStateOf(autofillEnabled(activity)) }
    val bioAvailable = remember { Biometrics.available(activity) }
    var atStart by remember { mutableStateOf(v.prefs.biometricAtStart) }
    var pinState by remember { mutableStateOf(v.pinStatus()) }
    var pinDialog by remember { mutableStateOf(false) }
    var removePin by remember { mutableStateOf(false) }
    // the open category (null: the list), saved by name
    var catName by rememberSaveable {
        val r = reopenCategory
        reopenCategory = null
        mutableStateOf(r?.name)
    }
    val cat = SettingsCategory.entries.find { it.name == catName }

    // the autofill status changes in system settings
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) autofillOn = autofillEnabled(activity) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    fun act(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: Exception) {
            v.say(errorText(e))
        }
    }

    /** Devices and the account log (the log is best effort, as on the web). */
    suspend fun loadDevices() {
        try {
            devices = decode<List<DeviceRecord>>(v.call { it.devices() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            v.say(errorText(e))
        }
        audit = try {
            decode<List<AuditEntry>>(v.call { it.auditLog() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList<AuditEntry>()
        }
    }

    // one category's groups, scrolled; the bottom padding keeps clear of the navigation bar
    val detail: @Composable (SettingsCategory, Modifier) -> Unit = { c, modifier ->
        key(c) {
            Column(
                modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (c) {
                    SettingsCategory.GENERAL -> {
                        Text("外观跟随系统的浅色 / 深色设置。", Modifier.padding(horizontal = 4.dp), fontSize = 12.5.sp, color = muted)
                        AboutCard(activity, checkUpdates) {
                            checkUpdates = it
                            v.prefs.checkUpdates = it
                        }
                    }

                    SettingsCategory.SECURITY -> Group(title = "解锁与锁定") {
                        Field("空闲后自动锁定", "这段时间没有使用就锁定（自动填充也算使用）") {
                            Select(LOCK_OPTIONS, autoLock) {
                                autoLock = it
                                v.prefs.autoLockMinutes = it
                                v.touch()
                            }
                        }
                        SwitchField("屏幕关闭时锁定", checked = screenOff) {
                            screenOff = it
                            v.prefs.lockOnScreenOff = it
                        }
                        if (bioAvailable) {
                            SwitchField(
                                "使用指纹 / 面容解锁",
                                "每 14 天仍需输入一次主密码；新增指纹 / 面容后自动失效",
                                checked = bio,
                            ) { on ->
                                if (on) {
                                    Biometrics.enable(activity, v) { err ->
                                        if (err == null) {
                                            bio = true
                                            v.say("已开启生物识别解锁")
                                        } else {
                                            v.say(err)
                                        }
                                    }
                                } else {
                                    app.nya.password.core.QuickUnlock.clear(activity)
                                    v.prefs.biometric = false
                                    bio = false
                                }
                            }
                            if (bio) {
                                SwitchField(
                                    "启动时可直接用生物识别解锁",
                                    "关闭后，应用重启后第一次解锁需要主密码",
                                    checked = atStart,
                                ) { on ->
                                    atStart = on
                                    v.prefs.biometricAtStart = on
                                }
                            }
                        } else {
                            Field("指纹 / 面容解锁", "这台设备没有可用的强生物识别（或尚未录入）")
                        }
                        Field(
                            "PIN 解锁",
                            if (pinState.set) {
                                "已设置 · 至少 4 个字符，连续输错 5 次作废；每 14 天仍需输入一次主密码；也可用于“使用前需要验证”"
                            } else {
                                "至少 4 个字符（任意字符），连续输错 5 次作废；只保存在这台手机上（硬件 Keystore 保护）"
                            },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (pinState.set) TextButton(onClick = { removePin = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                OutlinedButton(onClick = { pinDialog = true }) { Text(if (pinState.set) "修改" else "设置") }
                            }
                        }
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            OutlinedButton(onClick = { v.lock() }) { Text("立即锁定") }
                        }
                    }

                    SettingsCategory.AUTOFILL -> Group(title = "自动填充与通行密钥") {
                        Field(
                            "自动填充服务",
                            if (autofillOn) "已启用 NyaPassword" else "未启用：在系统设置里选择 NyaPassword",
                        ) { Chip(if (autofillOn) "已启用" else "未启用", if (autofillOn) ChipKind.OK else ChipKind.WARN) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            var inlineApps by remember { mutableStateOf(v.prefs.inlineApps) }
                            var inlineCompat by remember { mutableStateOf(v.prefs.inlineCompat) }
                            SwitchField(
                                "建议显示在输入法候选栏",
                                "关闭后显示在输入框下方的下拉框里。输入法不支持候选栏建议时系统也会用下拉框",
                                checked = inlineApps,
                            ) { on ->
                                inlineApps = on
                                v.prefs.inlineApps = on
                            }
                            SwitchField(
                                "Edge 等浏览器也显示在输入法候选栏",
                                "这类浏览器通过系统兼容模式填写，有的输入法不显示它们的建议；看不到建议时关掉，改用下拉框",
                                checked = inlineCompat,
                            ) { on ->
                                inlineCompat = on
                                v.prefs.inlineCompat = on
                            }
                        }
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Button(onClick = {
                                // come back to this category from the guide
                                reopenCategory = SettingsCategory.AUTOFILL
                                activity.open(Route.Guide)
                            }) { Text("设置引导") }
                        }
                    }

                    SettingsCategory.ACCOUNT -> {
                        Group(title = "账户") {
                            Field("账号", v.lock.login)
                            Field("服务器", v.lock.serverUrl)
                            Field("上次同步", ago(v.lock.lastSyncAt) + if (v.syncError.isNotEmpty()) " · ${v.syncError}" else "")
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { act { kit = decode(v.call { it.emergencyKit() }) } }) { Text("查看紧急恢复包") }
                                OutlinedButton(onClick = { v.syncSoon(silent = false) }, enabled = !v.syncing) { Text(if (v.syncing) "同步中…" else "立即同步") }
                            }
                        }
                        Group(title = "主密码") {
                            Field("修改主密码", "Secret Key 不变；紧急恢复包上的主密码记得一起更新") {
                                OutlinedButton(onClick = { changePw = true }) { Text("修改") }
                            }
                        }
                        Group(title = "退出") {
                            Field("退出此设备上的账户", "删除此设备上的本地副本；下次登录需要 Secret Key") {
                                OutlinedButton(onClick = { signOut = true }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }

                    SettingsCategory.VAULTS -> Group(title = "保险库") {
                        v.vaults.forEach { vv ->
                            Field(vv.name, "${vv.items} 个条目") { TextButton(onClick = { rename = vv.id to vv.name }) { Text("改名") } }
                        }
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(newVault, { newVault = it }, Modifier.weight(1f), placeholder = { Text("新保险库名称") }, singleLine = true)
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                val name = newVault.trim()
                                if (name.isNotEmpty()) {
                                    act {
                                        v.call { it.createVault(name) }
                                        newVault = ""
                                        v.refresh()
                                        v.say("保险库已创建")
                                    }
                                }
                            }) { Text("新建") }
                        }
                    }

                    SettingsCategory.DEVICES -> {
                        // loaded every time the category opens
                        LaunchedEffect(Unit) { loadDevices() }
                        val list = devices
                        Group(title = "设备") {
                            Field("登录了这个账户的设备", if (list == null) "加载中…" else "${list.size} 台") {
                                OutlinedButton(onClick = { act { loadDevices() } }) { Text("刷新") }
                            }
                            list?.forEach { d ->
                                Field(
                                    d.name + if (d.current) "（本机）" else "",
                                    "${d.platform} · ${d.clientVersion} · 最近活动 ${ago(d.lastSeenAt)}" + if (d.revokedAt != null) " · 已移除" else "",
                                ) {
                                    if (!d.current && d.revokedAt == null) TextButton(onClick = { revoke = d }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                        val log = audit
                        Group(title = "账户日志") {
                            when {
                                log == null -> Field("加载中…")
                                log.isEmpty() -> Field("暂无记录")
                                else -> log.take(50).forEach { a ->
                                    Field(
                                        AUDIT_ACTIONS[a.action] ?: a.action,
                                        listOf(dateTime(a.at), a.detail, a.ip).filter { it.isNotBlank() }.joinToString(" · "),
                                    )
                                }
                            }
                            if (log != null && log.size > 50) Text("只显示最近 50 条", Modifier.padding(16.dp), fontSize = 12.sp, color = muted)
                        }
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        // composed after MainActivity's handler, so it wins: back from a category goes to the list;
        // from the list MainActivity's handler goes back to the 密码库 tab as before
        BackHandler(enabled = !wide && cat != null) { catName = null }
        when {
            wide -> {
                val shown = cat ?: SettingsCategory.GENERAL
                Row(Modifier.fillMaxSize()) {
                    CategoryList(shown, pad, Modifier.width(300.dp).fillMaxHeight()) { catName = it.name }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(shown.title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        detail(shown, Modifier.fillMaxSize())
                    }
                }
            }

            cat == null -> CategoryList(null, pad, Modifier.fillMaxSize()) { catName = it.name }

            else -> Column(Modifier.fillMaxSize()) {
                PageTop(cat.title, { catName = null })
                detail(cat, Modifier.fillMaxSize())
            }
        }
    }

    kit?.let { k ->
        AlertDialog(
            onDismissRequest = { kit = null },
            title = { Text("紧急恢复包") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KitBody(k, remember(k) { runCatching { com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(app.nya.password.core.KitQr(k.serverUrl, k.login, k.secretKey).toJson(), com.google.zxing.BarcodeFormat.QR_CODE, 600, 600) }.getOrNull() })
                }
            },
            confirmButton = { TextButton(onClick = { kit = null }) { Text("关闭") } },
        )
    }
    revoke?.let { d ->
        ConfirmDialog("移除设备？", "“${d.name}”会立即退出，需要主密码和 Secret Key 才能重新登录。", "移除", true, { revoke = null }) {
            revoke = null
            act {
                v.call { it.revokeDevice(d.id) }
                loadDevices()
            }
        }
    }
    rename?.let { (id, old) ->
        var name by remember { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("重命名保险库") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    rename = null
                    if (name.isNotBlank() && name != old) act {
                        v.call { it.renameVault(id, name.trim()) }
                        v.refresh()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } },
        )
    }
    if (changePw) {
        ChangePasswordDialog(activity) {
            changePw = false
            bio = v.prefs.biometric
            pinState = v.pinStatus()
        }
    }
    if (pinDialog) {
        PinDialog(activity, change = pinState.set) {
            pinDialog = false
            pinState = v.pinStatus()
        }
    }
    if (removePin) {
        ConfirmDialog("删除 PIN？", "之后只能用主密码或生物识别解锁。", "删除", true, { removePin = false }) {
            removePin = false
            act {
                v.removePin()
                pinState = v.pinStatus()
                v.say("PIN 已删除")
            }
        }
    }
    if (signOut) {
        val pending = v.attention.second
        ConfirmDialog(
            "退出此设备上的账户？",
            (if (pending > 0) "还有 $pending 处修改没有同步到服务器，退出会丢失它们！" else "会删除此设备上的本地副本。") + "\n下次登录需要 Secret Key。",
            "退出", true, { signOut = false },
        ) {
            signOut = false
            act { v.signOut(true) }
        }
    }
}

/** The categories, one row each (the selected one highlighted on wide screens). */
@Composable
private fun CategoryList(selected: SettingsCategory?, pad: PaddingValues, modifier: Modifier, onPick: (SettingsCategory) -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Group {
            SettingsCategory.entries.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (c == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onPick(c) }
                        .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(c.summary, fontSize = 12.5.sp, color = muted, lineHeight = 17.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = muted)
                }
            }
        }
    }
}

private fun autofillEnabled(activity: MainActivity): Boolean = runCatching {
    activity.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
}.getOrDefault(false)

@Composable
private fun ChangePasswordDialog(activity: MainActivity, onClose: () -> Unit) {
    val v = activity.vault
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var next2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("修改主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("当前主密码", cur) { s: String -> cur = s }, Triple("新主密码", next) { s: String -> next = s }, Triple("再输一次新主密码", next2) { s: String -> next2 = s })
                    .forEach { (label, value, set) ->
                        OutlinedTextField(
                            value, set, label = { Text(label) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Text("Secret Key 不变；其他设备需要用新密码重新解锁。", fontSize = 12.sp, color = muted)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && cur.isNotEmpty() && next.isNotEmpty(), onClick = {
                msg = ""
                if (next != next2) return@TextButton run { msg = "两次输入的新密码不一致" }
                if (next.length < 10) return@TextButton run { msg = "新主密码至少 10 个字符" }
                busy = true
                scope.launch {
                    try {
                        v.call { it.changePassword(cur, next) }
                        val cleared = v.prefs.biometric || v.pinStatus().set
                        // a new master password: biometrics and the PIN are set up again
                        v.afterPasswordChange()
                        v.say("主密码已修改；其他设备需要用新密码重新登录" + if (cleared) "。本机的指纹 / 面容和 PIN 解锁已清除，请重新设置" else "")
                        onClose()
                    } catch (e: Exception) {
                        msg = errorText(e)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "请稍候…" else "修改") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** Sets or changes the PIN (twice, at least 4 characters). */
@Composable
private fun PinDialog(activity: MainActivity, change: Boolean, onClose: () -> Unit) {
    val v = activity.vault
    val scope = rememberCoroutineScope()
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (change) "修改 PIN" else "设置 PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("新 PIN", pin1) { s: String -> pin1 = s }, Triple("再输一次 PIN", pin2) { s: String -> pin2 = s })
                    .forEach { (label, value, set) ->
                        OutlinedTextField(
                            value, set, label = { Text(label) }, singleLine = true, enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Text("PIN 只保存在这台手机上，忘记时用主密码解锁即可。", fontSize = 12.sp, color = muted)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pin1.isNotEmpty(), onClick = {
                msg = ""
                if (!app.nya.password.core.LocalUnlock.pinValid(pin1)) return@TextButton run { msg = "PIN 至少 4 个字符（任意字符）" }
                if (pin1 != pin2) return@TextButton run { msg = "两次输入的 PIN 不一致" }
                busy = true
                scope.launch {
                    try {
                        v.setPin(pin1)
                        v.say(if (change) "PIN 已修改" else "PIN 已设置")
                        onClose()
                    } catch (e: Exception) {
                        msg = errorText(e)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "请稍候…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** Version and updates (after NyaRemoteControl's UpdateCard). */
class UpdateModel(private val activity: MainActivity) {
    var state by mutableStateOf("")
    var latest by mutableStateOf<Updater.Release?>(null)
    var progress by mutableIntStateOf(0)
    var message by mutableStateOf("")
    var checkedAt by mutableLongStateOf(0L)
    private var apk: File? = null

    fun check(quiet: Boolean = false) {
        if (state == "checking" || state == "downloading") return
        state = "checking"
        message = ""
        activity.vault.scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Updater.latest() }
                checkedAt = System.currentTimeMillis()
                latest = r
                state = if (r != null && Updater.newer(r.version, BuildConfig.VERSION_NAME)) "available" else "up_to_date"
                if (r == null && !quiet) message = "没有找到发布（${BuildConfig.UPDATE_REPO}）"
            } catch (e: Exception) {
                state = if (quiet) "" else "failed"
                message = "检查失败：${e.message}"
            }
        }
    }

    fun install() {
        val r = latest ?: return
        if (!Updater.canInstall(activity)) {
            message = "请允许 NyaPassword 安装应用，然后回来再点一次更新"
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()))
            }
            return
        }
        apk?.takeIf { it.exists() }?.let {
            state = "installing"
            Updater.install(activity, it)
            return
        }
        state = "downloading"
        progress = 0
        activity.vault.scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    Updater.download(activity, r) { done, total -> if (total > 0) progress = (done * 100 / total).toInt() }
                }
                apk = f
                state = "installing"
                Updater.install(activity, f)
            } catch (e: Exception) {
                state = "failed"
                message = e.message ?: e.toString()
            }
        }
    }
}

@Composable
private fun AboutCard(activity: MainActivity, checkUpdates: Boolean, onCheckUpdates: (Boolean) -> Unit) {
    val u = activity.updates
    var notes by remember { mutableStateOf(false) }
    Group(title = "关于") {
        Field("NyaPassword", "版本 ${BuildConfig.VERSION_NAME} · 核心 ${coreVersion()}${if (BuildConfig.COMMON_REF.isNotEmpty()) " (${BuildConfig.COMMON_REF.take(8)})" else ""}")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("版本与更新", color = muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (u.state == "available") {
                    Button(onClick = u::install) { Text("更新到 ${u.latest?.version}") }
                } else {
                    OutlinedButton(onClick = { u.check() }, enabled = u.state != "checking" && u.state != "downloading") {
                        Text(if (u.state == "checking") "检查中…" else "检查更新")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("当前版本", fontSize = 14.sp, color = muted)
                Text(BuildConfig.VERSION_NAME, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                when (u.state) {
                    "up_to_date" -> Chip("已是最新，检查于 ${SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(u.checkedAt))}", ChipKind.OK)
                    "available" -> Chip("有新版本 ${u.latest?.version}", ChipKind.WARN)
                    "downloading" -> Chip("正在下载 ${u.progress}%")
                    "installing" -> Chip("正在安装…")
                    "failed" -> Chip("出错", ChipKind.ERR)
                }
            }
            if (u.state == "downloading") LinearProgressIndicator(progress = { u.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (u.message.isNotBlank()) Text(u.message, fontSize = 13.sp, color = if (u.state == "failed") MaterialTheme.colorScheme.error else muted)
            if (u.state == "available") {
                Text("下载后校验 SHA-256 和签名证书（必须与当前应用相同），再交给系统安装程序。", fontSize = 12.5.sp, color = muted)
                if (u.latest?.notes?.isNotBlank() == true) {
                    TextButton(onClick = { notes = !notes }) { Text(if (notes) "收起更新内容" else "查看更新内容") }
                    if (notes) {
                        Text(
                            u.latest?.notes ?: "",
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                            fontSize = 12.5.sp,
                        )
                    }
                }
            }
        }
        SwitchField("启动时检查新版本", "从 GitHub（${BuildConfig.UPDATE_REPO}）查看是否有新版本；是否安装由你决定", checkUpdates, onChange = onCheckUpdates)
    }
}

/** How to turn on autofill and passkeys, including Chrome and Chinese ROMs. */
@Composable
fun SetupGuideScreen(activity: MainActivity) {
    var autofillOn by remember { mutableStateOf(autofillEnabled(activity)) }
    var reload by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                autofillOn = autofillEnabled(activity)
                reload++
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(Unit) { autofillOn = autofillEnabled(activity) }
    Column(Modifier.fillMaxSize()) {
        PageTop("自动填充设置引导", activity::back)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Group(title = "1. 设为自动填充服务") {
                Field("状态", if (autofillOn) "NyaPassword 已是自动填充服务" else "尚未启用") {
                    Chip(if (autofillOn) "已启用" else "未启用", if (autofillOn) ChipKind.OK else ChipKind.WARN)
                }
                Text(
                    "应用和浏览器里的登录框会显示 NyaPassword 的建议（Android 11 起也会出现在输入法的建议栏）。",
                    Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = muted,
                )
                Row(Modifier.padding(16.dp)) {
                    Button(onClick = {
                        val ok = runCatching {
                            activity.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, "package:${activity.packageName}".toUri()))
                        }.isSuccess
                        if (!ok) activity.vault.say("这个系统打不开自动填充设置，请按下面的路径手动设置")
                    }) { Text("打开系统自动填充设置") }
                }
            }
            Group(title = "2. 通行密钥与密码（Android 14 及以上）") {
                Text(
                    "在系统的“密码、通行密钥和账号”（凭据管理器）里启用 NyaPassword，网站和应用的通行密钥登录、注册都会交给 NyaPassword。",
                    Modifier.padding(16.dp), fontSize = 13.sp, color = muted,
                )
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Button(
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                        onClick = {
                            val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && runCatching {
                                CredentialManager.create(activity).createSettingsPendingIntent().send()
                            }.isSuccess
                            if (!ok) activity.vault.say("这个系统打不开凭据管理器设置，请在设置里搜索“通行密钥”")
                        },
                    ) { Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) "打开凭据管理器设置" else "需要 Android 14") }
                }
            }
            Group(title = "3. Chrome / Edge") {
                Steps(
                    "打开 Chrome → 右上角 ⋮ → 设置 → 自动填充服务",
                    "选择“使用其他服务自动填充”",
                    "完全退出并重新打开 Chrome（在最近任务里划掉）",
                    "Edge 通过系统的兼容模式填写：启用 NyaPassword 或更新后在最近任务里划掉 Edge 再打开；地址栏要放在顶部（在 Edge 设置里找“地址栏位置”），放在底部时建议会显示为空白或一闪而过，其他密码管理器也一样。候选栏里仍看不到时，在 设置 → 自动填充 关掉“Edge 等浏览器也显示在输入法候选栏”，改用下拉框",
                )
            }
            Group(title = "4. 国内系统的设置位置") {
                Text(
                    "各厂商把“自动填充服务”放在不同的菜单里，版本之间也会变化。找不到时在系统设置顶部搜索“自动填充”。以下路径未在每个版本上逐一验证。",
                    Modifier.padding(16.dp), fontSize = 13.sp, color = muted,
                )
                Rom("小米 MIUI / HyperOS", "设置 → 更多设置 → 语言与输入法 → 自动填充服务；或 设置 → 密码与安全 → 自动填充")
                Rom("OPPO / 一加 / realme（ColorOS）", "设置 → 密码与安全 → 系统安全 → 自动填充服务；或 设置 → 其他设置 → 键盘与输入法 → 自动填充服务")
                Rom("vivo / iQOO（OriginOS）", "设置 → 系统管理 → 语言与输入法 → 自动填充服务；或 设置 → 安全与隐私 → 更多安全设置 → 自动填充服务")
                Rom("华为 / 荣耀（HarmonyOS 4 / MagicOS）", "设置 → 系统和更新 → 语言和输入法 → 自动填充服务；或 设置 → 安全 → 更多安全设置 → 自动填充服务。HarmonyOS NEXT 不能运行 Android 应用")
                Text(
                    "如果建议有时不出现：在“电池 / 应用启动管理”里允许 NyaPassword 自启动和后台运行；部分系统自带的密码保险箱会抢先填写，可以在它的设置里关闭。",
                    Modifier.padding(16.dp), fontSize = 13.sp, color = muted,
                )
            }
            Group(title = "5. 应用里没有找到匹配的条目") {
                Text(
                    "点建议里的“搜索 NyaPassword”，选中条目后会记住这个应用（包名和签名证书），下次直接建议；冒名的同名应用因为签名不同不会得到这个密码。",
                    Modifier.padding(16.dp), fontSize = 13.sp, color = muted,
                )
            }
            FillLogGroup(activity, reload)
        }
    }
}

/** "6. 诊断": off by default; when on, the last autofill requests and what the service answered ([FillLog]). */
@Composable
private fun FillLogGroup(activity: MainActivity, reload: Int) {
    var on by remember { mutableStateOf(FillLog.enabled(activity)) }
    var entries by remember { mutableStateOf(if (on) FillLog.read(activity) else emptyList()) }
    LaunchedEffect(reload, on) {
        // records kept from before the switch existed go away while it is off
        if (on) entries = FillLog.read(activity) else FillLog.clear(activity)
    }
    val time = remember { SimpleDateFormat("M月d日 HH:mm:ss", Locale.CHINA) }
    Group(title = "6. 诊断") {
        SwitchField(
            "记录自动填充请求",
            "排查建议不出现时打开；关闭后不再记录，并清除已有记录",
            checked = on,
            divider = on,
        ) {
            on = it
            FillLog.setEnabled(activity, it)
            if (!it) entries = emptyList()
        }
        if (!on) return@Group
        Text(
            "建议不出现时，先到其他应用的登录框里点一下，再回到这里看记录。没有任何记录说明系统没有把请求交给 NyaPassword；有记录则会写明这次为什么没有显示。只记录应用、网址和输入框的类型 / 名称 / 提示文字，不记录输入内容。",
            Modifier.padding(16.dp), fontSize = 13.sp, color = muted,
        )
        if (entries.isEmpty()) {
            Text("暂无记录", Modifier.padding(horizontal = 16.dp), fontSize = 14.sp)
        }
        entries.forEach { e ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${time.format(Date(e.at))} · ${e.domain ?: Fill.appLabel(activity, e.pkg).ifEmpty { "（未知应用）" }}",
                    fontSize = 13.sp, color = muted,
                )
                Text(e.outcome, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                val detail = listOf(e.pkg, e.fields, e.inline, "${e.ms} ms").filter { it.isNotBlank() }.joinToString(" · ")
                Text(detail, fontSize = 12.sp, color = muted, lineHeight = 16.sp)
                if (e.views.isNotBlank()) {
                    Text(e.views, fontSize = 11.sp, color = muted, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { entries = FillLog.read(activity) }) { Text("刷新") }
            OutlinedButton(enabled = entries.isNotEmpty(), onClick = {
                val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("NyaPassword 自动填充诊断", FillLog.text(entries)))
                activity.vault.say("已复制诊断记录")
            }) { Text("复制全部") }
            OutlinedButton(enabled = entries.isNotEmpty(), onClick = {
                FillLog.clear(activity)
                entries = emptyList()
            }) { Text("清除记录") }
        }
    }
}

@Composable
private fun Steps(vararg steps: String) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 14.sp) }
    }
}

@Composable
private fun Rom(name: String, path: String) {
    Field(name, path, stacked = true)
}
