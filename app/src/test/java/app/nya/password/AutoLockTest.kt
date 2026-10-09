package app.nya.password

import app.nya.password.core.AutoLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The same choices and labels as the web client (common/web/src/lib/autolock.test.ts). */
class AutoLockTest {
    @Test
    fun range() {
        assertTrue(AutoLock.valid(0))
        assertTrue(AutoLock.valid(10080))
        assertFalse(AutoLock.valid(-1))
        assertFalse(AutoLock.valid(10081))
    }

    @Test
    fun labels() {
        assertEquals("从不", AutoLock.label(0))
        assertEquals("45 分钟", AutoLock.label(45))
        assertEquals("1 小时 30 分钟", AutoLock.label(90))
        assertEquals("4 小时", AutoLock.label(240))
        assertEquals("2 天", AutoLock.label(2880))
    }

    @Test
    fun options() {
        assertEquals(listOf(1, 5, 10, 15, 30, 60, 240, 0), AutoLock.options(10))
        assertEquals(listOf(1, 5, 10, 15, 30, 45, 60, 240, 0), AutoLock.options(45))
        assertEquals(listOf(1, 5, 10, 15, 30, 60, 240, 0), AutoLock.options(0))
    }
}
