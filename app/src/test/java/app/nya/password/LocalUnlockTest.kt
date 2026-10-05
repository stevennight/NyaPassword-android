package app.nya.password

import app.nya.password.core.CoreFailure
import app.nya.password.core.Guard
import app.nya.password.core.GuardStore
import app.nya.password.core.LocalUnlock
import app.nya.password.core.LocalUnlock.Companion.PIN_MAX_TRIES
import app.nya.password.core.PinFailure
import app.nya.password.core.Stale
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 14-day rule and the PIN try counter (the same rules as desktop/src-tauri/src/local_unlock.rs). */
class LocalUnlockTest {
    private val a = "0192f0c0-0000-7000-8000-000000000001"
    private val day = 24L * 3600 * 1000
    private val blob = """{"version":1,"account_id":"$a"}"""

    /** An in-memory guard store that can fail on purpose. */
    private class Mem : GuardStore {
        var data: ByteArray? = null
        var failSave = false
        override fun load() = data
        override fun save(data: ByteArray) {
            if (failSave) throw IllegalStateException("store unavailable")
            this.data = data
        }
        override fun delete() {
            data = null
        }
        fun stored(): Guard = Json { ignoreUnknownKeys = true }.decodeFromString(Guard.serializer(), data!!.decodeToString())
    }

    private fun wrong(): Nothing = throw CoreFailure("wrong_password", "wrong master password or Secret Key")

    @Test
    fun fourteenDaysAndTheClock() {
        val now = 100 * day
        fun g(at: Long, seen: Long) = Guard(accountId = a, passwordUnlockAt = at, seenAt = seen)
        assertEquals(Stale.NO_PASSWORD, LocalUnlock.fresh(Guard(), now))
        assertNull(LocalUnlock.fresh(g(now - day, now - day), now))
        assertNull(LocalUnlock.fresh(g(now - 14 * day + 1, now), now))
        assertEquals(Stale.EXPIRED, LocalUnlock.fresh(g(now - 14 * day, now), now))
        assertEquals(Stale.CLOCK_BACK, LocalUnlock.fresh(g(now - day, now + day), now), "clock set back")
        assertEquals(Stale.CLOCK_BACK, LocalUnlock.fresh(g(now + day, now + day), now))
        assertNull(LocalUnlock.fresh(g(now - day, now + 60_000), now), "time sync of a minute is fine")
    }

    @Test
    fun biometricsAtStartFollowTheSetting() {
        val l = LocalUnlock(Mem())
        val now = 50 * day
        assertEquals(Stale.NO_PASSWORD, l.quickAllowed(a, now, true, true))
        l.recordPasswordUnlock(a, now - day)
        assertNull(l.quickAllowed(a, now, passwordThisRun = true, atStart = false))
        assertNull(l.quickAllowed(a, now, passwordThisRun = false, atStart = true))
        assertEquals(Stale.RESTARTED, l.quickAllowed(a, now, passwordThisRun = false, atStart = false))
        assertEquals(Stale.EXPIRED, l.quickAllowed(a, now + 13 * day, false, true))
        assertEquals(Stale.NO_PASSWORD, l.quickAllowed("other", now, true, true))
    }

    @Test
    fun theRecordSurvivesARestartAndAClockSetBack() {
        val m = Mem()
        val now = 10 * day
        LocalUnlock(m).apply {
            recordPasswordUnlock(a, now)
            touch(a, now + 3 * day)
        }
        val again = LocalUnlock(m)
        assertEquals(now, again.get(a).passwordUnlockAt)
        assertEquals(Stale.CLOCK_BACK, again.quickAllowed(a, now + day, false, true))
        assertNull(again.quickAllowed(a, now + 3 * day, false, true))
        // a damaged (edited) file reads as empty: the master password is needed
        m.data = "garbage".toByteArray()
        assertEquals(Stale.NO_PASSWORD, LocalUnlock(m).quickAllowed(a, now, true, true))
    }

    @Test
    fun pinNeedsARecentPasswordAndTheRightAccount() {
        val l = LocalUnlock(Mem())
        val now = 30 * day
        assertFailsWith<IllegalStateException> { l.setPin(a, blob, now) }
        l.recordPasswordUnlock(a, now)
        l.setPin(a, blob, now)
        assertTrue(l.pinStatus(a, now).usable)
        assertEquals(PIN_MAX_TRIES, l.pinStatus(a, now).triesLeft)
        // suspended after 14 days, still set
        val later = l.pinStatus(a, now + 14 * day)
        assertTrue(later.set && !later.usable)
        val stale = assertFailsWith<PinFailure> { l.tryPin(a, now + 14 * day) { } }
        assertEquals(PinFailure.Reason.STALE, stale.reason)
        l.recordPasswordUnlock(a, now + 14 * day)
        assertEquals("ok", l.tryPin(a, now + 14 * day) { "ok" })
        // another account's password unlock drops this PIN
        l.recordPasswordUnlock("other", now + 15 * day)
        assertFalse(l.pinStatus(a, now + 15 * day).set)
    }

    @Test
    fun fiveWrongTriesDeleteThePinAndSuccessResetsTheCount() {
        val m = Mem()
        val l = LocalUnlock(m)
        val now = 3 * day
        l.recordPasswordUnlock(a, now)
        l.setPin(a, blob, now)
        assertEquals(4, assertFailsWith<PinFailure> { l.tryPin(a, now) { wrong() } }.left)
        assertEquals(3, assertFailsWith<PinFailure> { l.tryPin(a, now) { wrong() } }.left)
        assertEquals(2, m.stored().pinTries)
        l.tryPin(a, now) { }
        assertEquals(0, m.stored().pinTries)
        for (left in PIN_MAX_TRIES - 1 downTo 1) {
            val f = assertFailsWith<PinFailure> { l.tryPin(a, now) { wrong() } }
            assertEquals(PinFailure.Reason.WRONG, f.reason)
            assertEquals(left, f.left)
        }
        assertEquals(PinFailure.Reason.WIPED, assertFailsWith<PinFailure> { l.tryPin(a, now) { wrong() } }.reason)
        assertNull(m.stored().pinBlob)
        assertFalse(l.pinStatus(a, now).set)
        assertEquals(PinFailure.Reason.NOT_SET, assertFailsWith<PinFailure> { l.tryPin(a, now) { } }.reason)
        assertEquals(now, m.stored().passwordUnlockAt, "the password record stays")
    }

    @Test
    fun aTryIsSavedBeforeTheCheck() {
        val m = Mem()
        val l = LocalUnlock(m)
        val now = 3 * day
        l.recordPasswordUnlock(a, now)
        l.setPin(a, blob, now)
        // the app is killed during the check: the try is already counted
        assertFailsWith<IllegalStateException> {
            l.tryPin(a, now) {
                assertEquals(1, m.stored().pinTries)
                throw IllegalStateException("killed")
            }
        }
        val restarted = LocalUnlock(m)
        assertEquals(PIN_MAX_TRIES - 1, restarted.pinStatus(a, now).triesLeft)

        // the count cannot be saved: no try at all
        m.failSave = true
        var checked = false
        val f = assertFailsWith<PinFailure> { restarted.tryPin(a, now) { checked = true } }
        assertEquals(PinFailure.Reason.STORE, f.reason)
        assertFalse(checked)
        m.failSave = false

        // other core errors (locked, a damaged blob) are not counted
        val e = assertFailsWith<CoreFailure> { restarted.tryPin(a, now) { throw CoreFailure("locked", "locked") } }
        assertEquals("locked", e.code)
        assertEquals(PIN_MAX_TRIES - 1, restarted.pinStatus(a, now).triesLeft)
    }

    @Test
    fun aStoredCountAtTheLimitWipesWithoutTrying() {
        val m = Mem()
        LocalUnlock(m).apply {
            recordPasswordUnlock(a, day)
            setPin(a, blob, day)
        }
        val g = m.stored().copy(pinTries = PIN_MAX_TRIES)
        m.data = Json.encodeToString(Guard.serializer(), g).encodeToByteArray()
        var checked = false
        val f = assertFailsWith<PinFailure> { LocalUnlock(m).tryPin(a, day) { checked = true } }
        assertEquals(PinFailure.Reason.WIPED, f.reason)
        assertFalse(checked)
        assertNull(m.stored().pinBlob)
    }

    @Test
    fun removeForgetAndPinRules() {
        val m = Mem()
        val l = LocalUnlock(m)
        l.recordPasswordUnlock(a, day)
        l.setPin(a, blob, day)
        l.removePin()
        assertNull(m.stored().pinBlob)
        assertEquals(day, m.stored().passwordUnlockAt)
        l.forget()
        assertNull(m.data)
        assertEquals(Guard(), l.get(a))
        assertTrue(LocalUnlock.pinValid("1234"))
        assertTrue(LocalUnlock.pinValid("猫猫猫猫"))
        assertTrue(LocalUnlock.pinValid("😺😺😺😺"), "characters, not UTF-16 units")
        assertFalse(LocalUnlock.pinValid("😺😺"))
        assertFalse(LocalUnlock.pinValid("123"))
    }
}
