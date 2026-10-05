package app.nya.password.core

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nya.password.BuildConfig
import app.nya.password.ffi.NpwClient
import app.nya.password.ffi.NpwException
import app.nya.password.ffi.otpCode
import app.nya.password.ffi.randomKey
import app.nya.password.ffi.templates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The account on this device: the core client (one per process), lock state,
 * sync, auto-lock and the events socket. UI reads the Compose state here;
 * every core call runs on [Dispatchers.IO].
 */
class Vault(private val app: Application) {
    val prefs = Prefs(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var client: NpwClient? = null
    private val clientLock = Any()

    var lock by mutableStateOf(LockState())
        private set
    /** The lock state has been read from the core at least once. */
    var ready by mutableStateOf(false)
        private set
    var vaults by mutableStateOf<List<VaultView>>(emptyList())
        private set
    /** conflicts, pending edits, rejected edits */
    var attention by mutableStateOf(Triple(0, 0, 0))
        private set
    /** Bumps whenever items may have changed; screens reload when it moves. */
    var version by mutableIntStateOf(0)
        private set
    var syncing by mutableStateOf(false)
        private set
    var syncError by mutableStateOf("")
        private set
    /** Shown once after registration. */
    var newKit by mutableStateOf<EmergencyKit?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Short messages for a snackbar / toast. */
    val messages: SharedFlow<String> = _messages

    /** Biometric unlock is only offered after a password unlock in this process run. */
    var passwordUnlockedThisRun = false
        private set
    private var lastActivity = SystemClock.elapsedRealtime()
    private var foreground = false
    private var foregroundJob: Job? = null
    private val syncMutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { checkAutoLock() }
    private val events = EventsSocket()

    fun say(msg: String) {
        _messages.tryEmit(msg)
    }

    // ---------------------------------------------------------------- the core

    private fun client(): NpwClient = synchronized(clientLock) {
        client ?: open().also { client = it }
    }

    private fun open(): NpwClient {
        val db = File(app.noBackupFilesDir, "replica.sqlite3")
        val (key, fresh) = DeviceKey.load(app) { randomKey() }
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        fun make() = NpwClient(db.absolutePath, key, name, BuildConfig.VERSION_NAME, "zh-CN")
        return try {
            make()
        } catch (e: NpwException) {
            // A new device key cannot open what the old one sealed (Keystore reset):
            // the replica is only a cache of the server, start over.
            if (!fresh) throw e
            Log.w("npw", "replica unreadable with a new device key; starting fresh", e)
            for (suffix in listOf("", "-wal", "-shm")) File(db.path + suffix).delete()
            make()
        }
    }

    /** Runs a core call off the main thread, turning core errors into [CoreFailure]. */
    suspend fun <T> call(block: (NpwClient) -> T): T = withContext(Dispatchers.IO) { callNow(block) }

    /** The same, on the calling thread (background threads only). */
    fun <T> callNow(block: (NpwClient) -> T): T = try {
        block(client())
    } catch (e: NpwException.Core) {
        throw CoreFailure(e.code, e.detail)
    }

    // ---------------------------------------------------------------- state

    suspend fun refresh() {
        lock = decode(call { it.lockState() })
        ready = true
        if (lock.unlocked) {
            vaults = decode(call { it.vaults() })
            val a = PlainJson.parseToJsonElement(call { it.attention() }).jsonArray
            attention = Triple(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, a[2].jsonPrimitive.int)
        } else {
            vaults = emptyList()
            attention = Triple(0, 0, 0)
        }
        version++
    }

    val unlocked: Boolean get() = lock.unlocked

    /** Whether the lock screen may offer biometrics (design doc §4.5). */
    fun quickUnlockOffered(): Boolean =
        prefs.biometric && QuickUnlock.stored(app) && passwordUnlockedThisRun &&
            System.currentTimeMillis() - prefs.lastPasswordUnlockAt < QUICK_UNLOCK_MAX_AGE_MS

    suspend fun register(server: String, login: String, password: String, invite: String?): EmergencyKit {
        val kit: EmergencyKit = decode(call { it.register(server, login, password, invite) })
        prefs.lastServer = server
        afterPasswordUnlock()
        newKit = kit
        refresh()
        onUnlocked()
        return kit
    }

    suspend fun signIn(server: String, login: String, password: String, secretKey: String) {
        call { it.signIn(server, login, password, secretKey) }
        prefs.lastServer = server
        afterPasswordUnlock()
        refresh()
        onUnlocked()
    }

    suspend fun unlock(password: String) {
        call { it.unlock(password) }
        afterPasswordUnlock()
        refresh()
        onUnlocked()
    }

    /** User verification for passkeys and credential requests: unlocks when locked, else checks the password. */
    suspend fun verifyPassword(password: String) {
        if (call { it.isUnlocked() }) {
            call { it.verifyPassword(password) }
            afterPasswordUnlock()
        } else {
            unlock(password)
        }
    }

    suspend fun unlockWithKey(key: ByteArray) {
        try {
            call { it.unlockWithKey(key) }
        } finally {
            key.fill(0)
        }
        refresh()
        onUnlocked()
    }

    private fun afterPasswordUnlock() {
        passwordUnlockedThisRun = true
        prefs.lastPasswordUnlockAt = System.currentTimeMillis()
        touch()
    }

    private fun onUnlocked() {
        touch()
        if (foreground) startForegroundWork()
    }

    fun lock() {
        runCatching { callNow { it.lock() } }
        events.stop()
        foregroundJob?.cancel()
        foregroundJob = null
        main.removeCallbacks(lockRunnable)
        scope.launch { runCatching { refresh() } }
    }

    suspend fun signOut(force: Boolean) {
        call { it.signOut(force) }
        events.stop()
        QuickUnlock.clear(app)
        prefs.biometric = false
        passwordUnlockedThisRun = false
        refresh()
    }

    // ---------------------------------------------------------------- sync

    /** Pull, merge, push. Quiet unless [silent] is false; concurrent calls are skipped. */
    fun syncSoon(silent: Boolean = true, delayMs: Long = 0) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            sync(silent)
        }
    }

    suspend fun sync(silent: Boolean = true) {
        if (!lock.unlocked || syncMutex.isLocked) return
        syncMutex.withLock {
            syncing = true
            try {
                val r: SyncReport = decode(call { it.sync() })
                syncError = ""
                if (r.conflicts > 0) say("同步时有 ${r.conflicts} 处冲突，两个值都已保留，请到“待处理冲突”查看")
                if (r.restoredToServer > 0) say("服务器似乎从备份恢复过：已重新上传 ${r.restoredToServer} 个更新的条目")
                if (r.rejected > 0) say("${r.rejected} 处修改被服务器拒绝（已保留在本地）")
                if (!silent) say("已同步")
                refresh()
            } catch (e: CoreFailure) {
                syncError = errorText(e)
                when (e.code) {
                    "device_revoked" -> {
                        say(syncError)
                        runCatching { signOut(true) }
                    }
                    "session_expired" -> {
                        lock()
                        say(syncError)
                    }
                    "locked" -> Unit
                    else -> if (!silent) say(syncError)
                }
            } catch (e: Exception) {
                syncError = e.message ?: e.toString()
            } finally {
                syncing = false
            }
        }
    }

    /** After a local edit: refresh now, push soon. */
    fun edited() {
        version++
        scope.launch { runCatching { refresh() } }
        syncSoon(delayMs = 800)
    }

    // ---------------------------------------------------------------- foreground, auto-lock

    /** Any use of the app (touches, fills) postpones the auto-lock. */
    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
        main.removeCallbacks(lockRunnable)
        val m = prefs.autoLockMinutes
        if (m > 0) main.postDelayed(lockRunnable, m * 60_000L + 1_000)
    }

    /** Locks when the vault was not used for the configured time. Call before using the vault. */
    fun checkAutoLock() {
        val m = prefs.autoLockMinutes
        if (m <= 0 || !lock.unlocked) return
        if (SystemClock.elapsedRealtime() - lastActivity >= m * 60_000L) {
            lock()
        } else {
            main.removeCallbacks(lockRunnable)
            main.postDelayed(lockRunnable, m * 60_000L - (SystemClock.elapsedRealtime() - lastActivity) + 1_000)
        }
    }

    fun onScreenOff() {
        if (prefs.lockOnScreenOff && lock.unlocked) lock()
    }

    fun onForeground() {
        foreground = true
        checkAutoLock()
        scope.launch {
            runCatching { refresh() }
            if (lock.unlocked) startForegroundWork()
        }
    }

    fun onBackground() {
        foreground = false
        foregroundJob?.cancel()
        foregroundJob = null
        events.stop()
    }

    /** While in the foreground and unlocked: sync now and every 5 minutes, and listen for server events. */
    private fun startForegroundWork() {
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            events.start()
            while (true) {
                sync()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    /** `GET /v1/events` WebSocket (OkHttp): a message means "something changed, sync". */
    private inner class EventsSocket {
        private val http = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        private var ws: WebSocket? = null
        private var retry = 0
        private var job: Job? = null

        fun start() {
            if (ws != null || job?.isActive == true) return
            job = scope.launch { connect() }
        }

        private suspend fun connect() {
            if (!foreground || !lock.unlocked) return
            try {
                val token = call { it.eventsToken() }
                val base = lock.serverUrl.trimEnd('/')
                val url = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/events?token=" + token
                ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
            } catch (e: Exception) {
                reconnectLater()
            }
        }

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retry = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val revoked = runCatching {
                    val o = PlainJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                    (o?.get("kind") as? JsonPrimitive)?.content == "device_revoked" &&
                        (o["device_id"] as? JsonPrimitive)?.content == lock.deviceId
                }.getOrDefault(false)
                syncSoon(silent = !revoked)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
        }

        private fun dropped(w: WebSocket) {
            scope.launch {
                if (ws !== w) return@launch
                ws = null
                reconnectLater()
            }
        }

        private fun reconnectLater() {
            if (!foreground || !lock.unlocked) return
            retry = (retry + 1).coerceAtMost(6)
            job = scope.launch {
                delay((1L shl retry) * 1000)
                connect()
            }
        }

        fun stop() {
            job?.cancel()
            job = null
            ws?.close(1000, null)
            ws = null
        }
    }

    // ---------------------------------------------------------------- helpers for screens

    suspend fun items(filter: String): List<ItemView> = decode(call { it.listItems(filter) })
    suspend fun item(vaultId: String, itemId: String): ItemView = decode(call { it.item(vaultId, itemId) })

    fun templatesNow(): List<TemplateInfo> = decode(templates("zh-CN"))

    fun otp(uri: String): OtpCode? = runCatching {
        decode<OtpCode>(otpCode(uri, System.currentTimeMillis() / 1000))
    }.getOrNull()

    companion object {
        const val QUICK_UNLOCK_MAX_AGE_MS = 14L * 24 * 3600 * 1000
        const val SYNC_INTERVAL_MS = 5L * 60 * 1000
    }
}
