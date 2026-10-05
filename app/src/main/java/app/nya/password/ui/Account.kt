package app.nya.password.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.password.MainActivity
import app.nya.password.core.EmergencyKit
import app.nya.password.core.KitQr
import app.nya.password.core.errorText
import app.nya.password.ffi.normalizeSecretKey
import app.nya.password.ffi.passwordStrength
import app.nya.password.vault
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch

/** Pages on top of the tabs. */
sealed interface Route {
    data class Detail(val vaultId: String, val itemId: String) : Route
    data class Edit(val vaultId: String, val itemId: String?, val template: String = "login") : Route
    data object Guide : Route
}

private val STRENGTH = listOf("很弱", "弱", "一般", "强", "很强")

/** Sign in to an existing account, or create one (the web vault's Welcome). */
@Composable
fun WelcomeScreen(activity: MainActivity, onScan: ((String) -> Unit) -> Unit) {
    val v = activity.vault
    val scope = rememberCoroutineScope()
    var register by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(v.prefs.lastServer) }
    var login by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun takeKit(text: String) {
        val k = KitQr.parse(text)
        if (k == null) {
            secretKey = text
            return
        }
        secretKey = k.secretKey
        k.server?.let { server = it }
        k.login?.let { login = it }
    }

    fun submit() {
        error = ""
        if (register) {
            if (password != password2) return run { error = "两次输入的主密码不一致" }
            if (password.length < 10) return run { error = "主密码至少 10 个字符（建议用一句话）" }
        }
        busy = true
        scope.launch {
            try {
                if (register) {
                    v.register(server.trim(), login.trim(), password, invite.trim().ifEmpty { null })
                } else {
                    val sk = try {
                        normalizeSecretKey(secretKey)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Secret Key 格式不对，请检查是否抄错")
                    }
                    v.signIn(server.trim(), login.trim(), password, sk)
                }
                password = ""
                password2 = ""
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(44)
                Spacer(Modifier.width(10.dp))
                Text("NyaPassword", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Seg(listOf(false to "登录已有账户", true to "创建账户"), register) { register = it; error = "" }
            OutlinedTextField(
                server, { server = it }, Modifier.fillMaxWidth(), label = { Text("服务器") },
                placeholder = { Text("https://vault.example.com") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                login, { login = it }, Modifier.fillMaxWidth(), label = { Text("账号（邮箱或用户名）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(), label = { Text("主密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (register) {
                if (password.isNotEmpty()) {
                    val s = passwordStrength(password).toInt()
                    Text(
                        "强度：${STRENGTH[s.coerceIn(0, 4)]}${if (s < 3) "（建议更长，用一句只有你知道的话）" else ""}",
                        fontSize = 12.5.sp,
                        color = if (s < 3) MaterialTheme.colorScheme.error else muted,
                    )
                }
                OutlinedTextField(
                    password2, { password2 = it }, Modifier.fillMaxWidth(), label = { Text("再输一次主密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(invite, { invite = it }, Modifier.fillMaxWidth(), label = { Text("邀请码（第一个账户不需要）") }, singleLine = true)
                Text(
                    "注册时会在本设备生成一串 Secret Key，和主密码一起加密你的数据；它不会发给服务器。新设备第一次登录需要它，请务必保存紧急恢复包。",
                    fontSize = 12.5.sp, color = muted, lineHeight = 18.sp,
                )
            } else {
                OutlinedTextField(
                    secretKey, { takeKit(it) }, Modifier.fillMaxWidth(),
                    label = { Text("Secret Key（紧急恢复包上的 A1-…）") },
                    placeholder = { Text("A1-XXXXXX-XXXXX-XXXXX-XXXXX-XXXXX-X") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    trailingIcon = {
                        IconButton(onClick = { onScan { takeKit(it) } }) { Icon(Icons.Filled.QrCodeScanner, "扫描二维码") }
                    },
                )
                Text("可以扫描紧急恢复包上的二维码，服务器、账号和 Secret Key 会一起填好。", fontSize = 12.5.sp, color = muted)
            }
            if (error.isNotEmpty()) Banner(error, error = true)
            Button(onClick = ::submit, enabled = !busy && server.isNotBlank() && login.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "请稍候…" else if (register) "创建账户" else "登录")
            }
        }
    }
}

/** Locked: master password or biometrics; sign out from here too. */
@Composable
fun LockScreen(activity: MainActivity) {
    val v = activity.vault
    val scope = rememberCoroutineScope()
    var confirmOut by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            UnlockPanel(activity, "已锁定", "${v.lock.login} · ${v.lock.serverUrl}") {}
            Text("离线也能解锁 · 上次同步 ${ago(v.lock.lastSyncAt)}", color = muted, fontSize = 12.sp)
            TextButton(onClick = { confirmOut = true }) { Text("退出此设备上的账户") }
        }
    }
    if (confirmOut) {
        ConfirmDialog(
            title = "退出此设备上的账户？",
            text = "会删除此设备上的本地副本。尚未同步的修改会丢失。下次登录需要 Secret Key。",
            confirm = "退出",
            danger = true,
            onDismiss = { confirmOut = false },
        ) {
            confirmOut = false
            scope.launch { runCatching { v.signOut(true) }.onFailure { v.say(errorText(it)) } }
        }
    }
}

/** The Emergency Kit: what to keep to sign in on a new device (shown once after registering, and from settings). */
@Composable
fun KitScreen(kit: EmergencyKit, first: Boolean, onDone: () -> Unit) {
    val qr = remember(kit) { kitQr(kit) }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (first) "账户已创建" else "紧急恢复包", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (first) "这是唯一一次显示完整的紧急恢复包（之后可以在“设置”里重新查看）。请现在抄写或打印，和主密码分开保存。"
                else "新设备登录需要这些信息和主密码。不要截图发给别人，也不要只保存在这个密码库里。",
                color = muted, fontSize = 13.5.sp, lineHeight = 19.sp,
            )
            KitBody(kit, qr)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(if (first) "我已保存，进入密码库" else "完成") }
        }
    }
}

@Composable
fun KitBody(kit: EmergencyKit, qr: Bitmap?) {
    Group {
        Field("服务器", kit.serverUrl, divider = false)
        Field("账号", kit.login)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Secret Key", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(kit.secretKey, fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("主密码：只有你知道，这里不显示。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp, color = muted)
    }
    if (qr != null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                qr.asImageBitmap(), "二维码",
                Modifier.size(220.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp),
            )
            Text("新设备登录时扫描这个二维码", fontSize = 12.5.sp, color = muted)
        }
    }
}

private fun kitQr(kit: EmergencyKit): Bitmap? = runCatching {
    BarcodeEncoder().encodeBitmap(KitQr(kit.serverUrl, kit.login, kit.secretKey).toJson(), BarcodeFormat.QR_CODE, 600, 600)
}.getOrNull()

/** A yes / no dialog (the web vault's `confirm`). */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
