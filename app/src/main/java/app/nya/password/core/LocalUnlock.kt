package app.nya.password.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Device-local unlock rules and PIN material (design doc §4.5, 加密规格.md
 * §4.4), the same rules as the desktop app's `local_unlock.rs`. Pure Kotlin
 * (JVM-tested); [GuardFile] keeps the record sealed by a Keystore key.
 *
 * - When the master password was last entered on this device. Biometrics and
 *   the PIN stop working 14 days after it, and while the clock reads earlier
 *   than the latest time seen (a clock set back must not extend the 14 days).
 *   The record is encrypted and authenticated by a non-exportable Keystore
 *   key, so editing the file cannot extend it (a damaged file reads as empty:
 *   the master password is needed).
 * - The PIN blob (the account key under Argon2id(PIN), from the core) and the
 *   wrong tries in a row. A try is counted and saved before the PIN is
 *   checked, so killing the app during a try gives no free try;
 *   [PIN_MAX_TRIES] wrong tries delete the PIN.
 */
@Serializable
data class Guard(
    @SerialName("account_id") val accountId: String = "",
    /** Unix ms of the last master-password unlock on this device. */
    @SerialName("password_unlock_at") val passwordUnlockAt: Long = 0,
    /** The latest clock reading seen. */
    @SerialName("seen_at") val seenAt: Long = 0,
    /** The core's `PinBlob` JSON; null without a PIN. */
    @SerialName("pin_blob") val pinBlob: String? = null,
    /** Wrong PIN tries in a row, counted before each try. */
    @SerialName("pin_tries") val pinTries: Int = 0,
)

/** Why biometrics and the PIN cannot be used now. */
enum class Stale(val message: String) {
    NO_PASSWORD("请先在这台设备上用主密码解锁一次"),
    RESTARTED("应用重启后第一次解锁需要主密码（设置里可开启“启动时可直接用生物识别解锁”）"),
    EXPIRED("距上次输入主密码已超过 14 天，生物识别和 PIN 已暂停，请输入主密码"),
    CLOCK_BACK("系统时间早于上次使用的时间，请输入主密码"),
}

/** Where the guard is kept ([GuardFile]; a byte array in tests). Failures throw. */
interface GuardStore {
    fun load(): ByteArray?
    fun save(data: ByteArray)
    fun delete()
}

data class PinStatus(val set: Boolean, val usable: Boolean, val triesLeft: Int)

/** A PIN try that did not unlock. [left]: tries left after a wrong PIN. */
class PinFailure(val reason: Reason, val left: Int = 0, message: String) : Exception(message) {
    enum class Reason { NOT_SET, STALE, WRONG, WIPED, STORE }
}

class LocalUnlock(private val store: GuardStore, private val log: (String) -> Unit = {}) {
    private var cached: Guard? = null

    companion object {
        const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
        const val PIN_MAX_TRIES = 5
        /** How far the clock may go back (time sync) before quick unlock is suspended. */
        const val CLOCK_SLACK_MS = 10L * 60 * 1000
        const val MIN_PIN_CHARS = 4

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** The 14-day rule and the clock check; null = biometrics / PIN may be used. */
        fun fresh(g: Guard, now: Long): Stale? = when {
            g.passwordUnlockAt <= 0 -> Stale.NO_PASSWORD
            now + CLOCK_SLACK_MS < maxOf(g.seenAt, g.passwordUnlockAt) -> Stale.CLOCK_BACK
            now - g.passwordUnlockAt >= MAX_AGE_MS -> Stale.EXPIRED
            else -> null
        }

        /** A PIN has at least [MIN_PIN_CHARS] characters (code points), any characters. */
        fun pinValid(pin: String): Boolean = pin.codePointCount(0, pin.length) >= MIN_PIN_CHARS
    }

    private fun current(): Guard {
        cached?.let { return it }
        val g = try {
            store.load()?.let { json.decodeFromString(Guard.serializer(), it.decodeToString()) }
        } catch (e: Exception) {
            log("unlock guard unreadable: $e")
            null
        } ?: Guard()
        cached = g
        return g
    }

    /** Saves; the cached copy changes only when that worked. */
    private fun put(g: Guard) {
        store.save(json.encodeToString(Guard.serializer(), g).encodeToByteArray())
        cached = g
    }

    /** Saves, or at least keeps it for this process. */
    private fun putOrKeep(g: Guard) {
        try {
            put(g)
        } catch (e: Exception) {
            log("cannot save the unlock guard: $e")
            cached = g
        }
    }

    /** The guard for [account] (empty when it belongs to another account). */
    @Synchronized
    fun get(account: String): Guard = current().takeIf { it.accountId == account && account.isNotEmpty() } ?: Guard()

    /** The master password was entered (unlock, sign-in, registration, verification). */
    @Synchronized
    fun recordPasswordUnlock(account: String, now: Long) {
        var g = current()
        if (g.accountId != account) g = Guard(accountId = account)
        putOrKeep(g.copy(passwordUnlockAt = now, seenAt = maxOf(g.seenAt, now)))
    }

    /** Remembers the latest clock reading (after any unlock). */
    @Synchronized
    fun touch(account: String, now: Long) {
        val g = current()
        if (g.accountId != account || now <= g.seenAt) return
        putOrKeep(g.copy(seenAt = now))
    }

    /**
     * May biometrics unlock now? [passwordThisRun]: the master password was
     * entered in this process; [atStart]: "启动时可直接用生物识别解锁".
     */
    fun quickAllowed(account: String, now: Long, passwordThisRun: Boolean, atStart: Boolean): Stale? =
        fresh(get(account), now) ?: if (!passwordThisRun && !atStart) Stale.RESTARTED else null

    fun pinStatus(account: String, now: Long): PinStatus {
        val g = get(account)
        val set = g.pinBlob != null
        return PinStatus(set, set && fresh(g, now) == null, if (set) (PIN_MAX_TRIES - g.pinTries).coerceAtLeast(0) else 0)
    }

    /** Stores a new PIN blob (replacing any): needs a recent master-password unlock. Throws with a message. */
    @Synchronized
    fun setPin(account: String, blobJson: String, now: Long) {
        val g = current()
        check(g.accountId == account && account.isNotEmpty()) { Stale.NO_PASSWORD.message }
        fresh(g, now)?.let { throw IllegalStateException(it.message) }
        put(g.copy(pinBlob = blobJson, pinTries = 0))
    }

    /** Deletes the PIN (settings, a master-password change). */
    @Synchronized
    fun removePin() {
        val g = current()
        if (g.pinBlob == null) return
        put(g.copy(pinBlob = null, pinTries = 0))
    }

    /** Signing out: everything goes. */
    @Synchronized
    fun forget() {
        cached = Guard()
        runCatching { store.delete() }.onFailure { log("cannot delete the unlock guard: $it") }
    }

    /**
     * One PIN try: counts it (and saves the count) first, then runs [check]
     * (the core opens the blob; a wrong PIN throws [CoreFailure] with code
     * `wrong_password`); resets the count on success; deletes the PIN after
     * the last allowed wrong try. Other failures are not counted.
     */
    @Synchronized
    fun <T> tryPin(account: String, now: Long, check: (String) -> T): T {
        val g = current()
        val blob = g.pinBlob
        if (g.accountId != account || blob == null) {
            throw PinFailure(PinFailure.Reason.NOT_SET, message = "没有设置 PIN，请输入主密码")
        }
        fresh(g, now)?.let { throw PinFailure(PinFailure.Reason.STALE, message = it.message) }
        if (g.pinTries >= PIN_MAX_TRIES) {
            runCatching { put(g.copy(pinBlob = null, pinTries = 0)) }
            throw wiped()
        }
        val counted = g.copy(pinTries = g.pinTries + 1)
        try {
            put(counted)
        } catch (e: Exception) {
            throw PinFailure(PinFailure.Reason.STORE, message = "无法记录 PIN 尝试次数（${e.message}），这次没有验证")
        }
        val result = try {
            check(blob)
        } catch (e: CoreFailure) {
            if (e.code != "wrong_password") {
                // not a wrong PIN (locked, a damaged blob): do not count it
                runCatching { put(g) }
                throw e
            }
            if (counted.pinTries >= PIN_MAX_TRIES) {
                runCatching { put(counted.copy(pinBlob = null, pinTries = 0)) }
                throw wiped()
            }
            val left = PIN_MAX_TRIES - counted.pinTries
            throw PinFailure(PinFailure.Reason.WRONG, left, "PIN 不正确，还可以再试 $left 次")
        }
        putOrKeep(counted.copy(pinTries = 0, seenAt = maxOf(counted.seenAt, now)))
        return result
    }

    private fun wiped() = PinFailure(
        PinFailure.Reason.WIPED,
        message = "PIN 连续输错 $PIN_MAX_TRIES 次，已作废。请输入主密码，然后在设置里重新设置 PIN",
    )
}
