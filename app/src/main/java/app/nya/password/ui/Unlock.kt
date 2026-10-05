package app.nya.password.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.nya.password.core.CoreFailure
import app.nya.password.core.LocalUnlock
import app.nya.password.core.QuickUnlock
import app.nya.password.core.Stale
import app.nya.password.core.Vault
import app.nya.password.core.errorText
import app.nya.password.vault
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/**
 * Base of every activity: no screenshots or recents previews (FLAG_SECURE),
 * and every touch or key counts as use for the auto-lock.
 */
open class SecureActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }

    /** Called for every touch, key and trackball event delivered to this activity. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        vault.touch()
    }
}

object Biometrics {
    fun available(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Shows the system biometric prompt for [cipher]; [onOk] gets the authorized cipher. */
    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        cipher: Cipher,
        onOk: (Cipher) -> Unit,
        onFail: (String?) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("使用主密码")
            .setConfirmationRequired(false)
            .build()
        val p = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) onFail("没有得到密钥") else onOk(c)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED
                    onFail(if (cancelled) null else errString.toString())
                }
            },
        )
        p.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * Unlocks (or, when already unlocked, verifies the user) with the stored
     * quick-unlock key. Invalidated keys are removed and the user is told to
     * use the master password.
     */
    fun unlock(activity: FragmentActivity, vault: Vault, subtitle: String?, onDone: () -> Unit, onFail: (String?) -> Unit) {
        val cipher = try {
            QuickUnlock.decryptCipher(activity)
        } catch (e: QuickUnlock.Invalidated) {
            vault.prefs.biometric = false
            onFail("指纹或面容有变化，生物识别解锁已失效。请输入主密码，然后在设置里重新开启。")
            return
        } catch (e: Exception) {
            onFail("无法使用生物识别：${e.message}")
            return
        }
        prompt(activity, "解锁 NyaPassword", subtitle, cipher, onOk = { c ->
            vault.scope.launch {
                try {
                    val key = QuickUnlock.open(activity, c)
                    // already unlocked: user verification; the key must still be this account's
                    if (vault.unlocked) vault.verifyKey(key) else vault.unlockWithKey(key)
                    vault.touch()
                    onDone()
                } catch (e: CoreFailure) {
                    QuickUnlock.clear(activity)
                    vault.prefs.biometric = false
                    onFail(errorText(e))
                } catch (e: Exception) {
                    onFail(e.message)
                }
            }
        }, onFail = onFail)
    }

    /** Turns biometric unlock on: encrypts the core's quick-unlock key behind a biometric. */
    fun enable(activity: FragmentActivity, vault: Vault, onDone: (String?) -> Unit) {
        val cipher = try {
            QuickUnlock.encryptCipher()
        } catch (e: Exception) {
            onDone("无法创建密钥：${e.message}")
            return
        }
        prompt(activity, "开启生物识别解锁", "确认是你本人", cipher, onOk = { c ->
            vault.scope.launch {
                try {
                    val key = vault.call { it.quickUnlockKey() }
                    QuickUnlock.save(activity, c, key)
                    key.fill(0)
                    vault.prefs.biometric = true
                    onDone(null)
                } catch (e: Exception) {
                    QuickUnlock.clear(activity)
                    onDone(errorText(e))
                }
            }
        }, onFail = { msg -> onDone(msg ?: "已取消") })
    }
}

/**
 * Master password, the PIN and biometrics (when offered: 14-day rule). [verify]:
 * the vault may be unlocked already, the user must still prove it is them
 * (passkeys, credential requests, "使用前需要验证").
 */
@Composable
fun UnlockPanel(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    verify: Boolean = false,
    onDone: () -> Unit,
) {
    val v = activity.vault
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val bio = v.quickUnlockOffered() && Biometrics.available(activity)
    var pin by remember { mutableStateOf(v.pinStatus()) }
    /** The field takes the PIN instead of the master password (the PIN is the default when set). */
    var usePin by remember { mutableStateOf(pin.usable) }
    val focus = remember { FocusRequester() }

    fun submit() {
        if (password.isEmpty() || busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                when {
                    usePin && verify -> v.verifyPin(password)
                    usePin -> v.unlockWithPin(password)
                    verify -> v.verifyPassword(password)
                    else -> v.unlock(password)
                }
                password = ""
                onDone()
            } catch (e: Exception) {
                error = errorText(e)
                if (usePin) {
                    // tries left, or a deleted / suspended PIN: back to the master password
                    pin = v.pinStatus()
                    if (!pin.usable) {
                        usePin = false
                        password = ""
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    fun biometric() {
        error = ""
        Biometrics.unlock(activity, v, subtitle, onDone = onDone, onFail = { m -> if (m != null) error = m })
    }

    LaunchedEffect(Unit) {
        if (bio) biometric() else runCatching { focus.requestFocus() }
    }

    val triesHint: (@Composable () -> Unit)? = if (usePin && pin.triesLeft < LocalUnlock.PIN_MAX_TRIES) {
        { Text("还可以再试 ${pin.triesLeft} 次，之后 PIN 作废") }
    } else {
        null
    }

    Column(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Logo(64)
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(if (usePin) "PIN" else "主密码") },
            supportingText = triesHint,
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                if (bio) IconButton(onClick = ::biometric) { Icon(Icons.Filled.Fingerprint, "生物识别") }
            },
        )
        Button(onClick = ::submit, enabled = password.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Lock, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (busy) "正在解锁…" else if (verify) "确认" else "解锁")
        }
        if (bio) {
            OutlinedButton(onClick = ::biometric, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Fingerprint, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("使用指纹 / 面容")
            }
        }
        if (pin.usable) {
            TextButton(onClick = {
                usePin = !usePin
                password = ""
                error = ""
            }) { Text(if (usePin) "改用主密码" else "使用 PIN") }
        }
        if (!usePin && (v.prefs.biometric || pin.set)) {
            v.quickUnlockBlocked()?.takeIf { it != Stale.RESTARTED || !pin.usable }?.let {
                Text(it.message, color = muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
        if (error.isNotEmpty()) Banner(error, error = true)
    }
}

/** The app's mark: a key with cat ears, in the accent colour. */
@Composable
fun Logo(size: Int) {
    Row(
        Modifier.size(size.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(app.nya.password.R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(size.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
    }
}
