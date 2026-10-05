package app.nya.password

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.nya.password.core.Vault

/** Holds the one [Vault] of the process and wires it to the app's lifecycle and the screen. */
class NpwApp : Application() {
    lateinit var vault: Vault
        private set

    override fun onCreate() {
        super.onCreate()
        vault = Vault(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = vault.onForeground()
            override fun onStop(owner: LifecycleOwner) = vault.onBackground()
        })
        // Lock when the screen turns off (setting). Only dynamically registered receivers get this broadcast.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) vault.onScreenOff()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

val Context.vault: Vault get() = (applicationContext as NpwApp).vault
