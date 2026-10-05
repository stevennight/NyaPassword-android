package app.nya.password.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.edit

/** App settings (nothing secret). */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Lock after this many minutes without use; 0 = only when the screen turns off / the app restarts. */
    var autoLockMinutes: Int
        get() = p.getInt("auto_lock_minutes", 5)
        set(v) = p.edit { putInt("auto_lock_minutes", v) }

    var lockOnScreenOff: Boolean
        get() = p.getBoolean("lock_on_screen_off", true)
        set(v) = p.edit { putBoolean("lock_on_screen_off", v) }

    var biometric: Boolean
        get() = p.getBoolean("biometric", false)
        set(v) = p.edit { putBoolean("biometric", v) }

    /**
     * "启动时可直接用生物识别解锁" (on by default). The time of the last
     * master-password unlock is not here but in the Keystore-sealed guard
     * ([LocalUnlock], [GuardFile]): editing preferences must not extend the 14 days.
     */
    var biometricAtStart: Boolean
        get() = p.getBoolean("biometric_at_start", true)
        set(v) = p.edit { putBoolean("biometric_at_start", v) }

    var checkUpdates: Boolean
        get() = p.getBoolean("check_updates", true)
        set(v) = p.edit { putBoolean("check_updates", v) }

    /** The server last typed on the sign-in page. */
    var lastServer: String
        get() = p.getString("last_server", "") ?: ""
        set(v) = p.edit { putString("last_server", v) }
}

/**
 * Copying secrets: marked sensitive (Android 13+ hides the preview; keyboards
 * with clipboard history skip it) and cleared after [CLEAR_AFTER_MS], unless
 * something else was copied meanwhile (when the system lets us tell).
 */
object Clipboard {
    const val CLEAR_AFTER_MS = 90_000L
    private const val LABEL = "NyaPassword"
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun copy(context: Context, text: String, sensitive: Boolean) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(LABEL, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                } else {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
        }
        cm.setPrimaryClip(clip)
        pending?.let { main.removeCallbacks(it) }
        if (!sensitive) return
        val stamp = runCatching { cm.primaryClipDescription?.timestamp }.getOrNull()
        val r = Runnable { clear(context, stamp) }
        pending = r
        main.postDelayed(r, CLEAR_AFTER_MS)
    }

    private fun clear(context: Context, stamp: Long?) {
        pending = null
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val desc = runCatching { cm.primaryClipDescription }.getOrNull()
        // Something newer was copied (only visible while we may read the clipboard): leave it.
        if (desc != null && (desc.label != LABEL || (stamp != null && desc.timestamp != stamp))) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { cm.clearPrimaryClip() }
        } else {
            runCatching { cm.setPrimaryClip(ClipData.newPlainText("", "")) }
        }
    }
}
