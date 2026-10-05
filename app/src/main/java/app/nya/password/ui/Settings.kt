package app.nya.password.ui

import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.nya.password.MainActivity
import app.nya.password.core.DeviceRecord
import app.nya.password.core.EmergencyKit
import app.nya.password.core.Updater
import app.nya.password.core.decode
import app.nya.password.core.errorText
import app.nya.password.ffi.coreVersion
import app.nya.password.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOCK_OPTIONS = listOf(1 to "1 分钟", 5 to "5 分钟", 10 to "10 分钟", 30 to "30 分钟", 60 to "1 小时", 240 to "4 小时", 0 to "从不（仅锁屏 / 重启时）")

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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Group(title = "账户") {
            Field("账号", v.lock.login)
            Field("服务器", v.lock.serverUrl)
            Field("上次同步", ago(v.lock.lastSyncAt) + if (v.syncError.isNotEmpty()) " · ${v.syncError}" else "")
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { act { kit = decode(v.call { it.emergencyKit() }) } }) { Text("查看紧急恢复包") }
                OutlinedButton(onClick = { v.syncSoon(silent = false) }, enabled = !v.syncing) { Text(if (v.syncing) "同步中…" else "立即同步") }
            }
        }

        Group(title = "解锁与锁定") {
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

        Group(title = "自动填充与通行密钥") {
            Field(
                "自动填充服务",
                if (autofillOn) "已启用 NyaPassword" else "未启用：在系统设置里选择 NyaPassword",
            ) { Chip(if (autofillOn) "已启用" else "未启用", if (autofillOn) ChipKind.OK else ChipKind.WARN) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Button(onClick = { activity.open(Route.Guide) }) { Text("设置引导") }
            }
        }

        Group(title = "保险库") {
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

        Group(title = "主密码与设备") {
            Field("修改主密码", "Secret Key 不变；紧急恢复包上的主密码记得一起更新") {
                OutlinedButton(onClick = { changePw = true }) { Text("修改") }
            }
            Field("设备", "登录了这个账户的设备") {
                OutlinedButton(onClick = { act { devices = decode(v.call { it.devices() }) } }) { Text(if (devices == null) "查看" else "刷新") }
            }
            devices?.forEach { d ->
                Field(
                    d.name + if (d.current) "（本机）" else "",
                    "${d.platform} · ${d.clientVersion} · 最近活动 ${ago(d.lastSeenAt)}" + if (d.revokedAt != null) " · 已移除" else "",
                ) {
                    if (!d.current && d.revokedAt == null) TextButton(onClick = { revoke = d }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        AboutCard(activity, checkUpdates) {
            checkUpdates = it
            v.prefs.checkUpdates = it
        }

        Group(title = "退出") {
            Field("退出此设备上的账户", "删除此设备上的本地副本；下次登录需要 Secret Key") {
                OutlinedButton(onClick = { signOut = true }) { Text("退出", color = MaterialTheme.colorScheme.error) }
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
                devices = decode(v.call { it.devices() })
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
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) autofillOn = autofillEnabled(activity) }
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
                    "Edge 同理：设置 → 密码 / 自动填充 → 使用其他服务自动填充（版本不同菜单可能略有差异）",
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
