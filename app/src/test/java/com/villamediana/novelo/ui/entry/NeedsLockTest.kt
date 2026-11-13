package com.villamediana.novelo.ui.entry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedsLockTest {
    /** Taking something out of sensitive asks the lock even when it is open (it unlocks it for good). */
    @Test fun unmarkingAlwaysAsks() {
        assertTrue(needsLock(sensitive = true, always = true, revealed = true, lockOn = true, phoneHasLock = true))
        assertTrue(needsLock(sensitive = true, always = true, revealed = false, lockOn = true, phoneHasLock = true))
    }

    /** Sharing or deleting asks while it is still hidden; open (the lock passed), it doesn't again. */
    @Test fun hiddenAsksOpenDoesNot() {
        assertTrue(needsLock(sensitive = true, always = false, revealed = false, lockOn = true, phoneHasLock = true))
        assertFalse(needsLock(sensitive = true, always = false, revealed = true, lockOn = true, phoneHasLock = true))
    }

    /** Nothing sensitive, the lock not asked for, or a phone without one: nothing to ask. */
    @Test fun nothingToAsk() {
        assertFalse(needsLock(sensitive = false, always = true, revealed = false, lockOn = true, phoneHasLock = true))
        assertFalse(needsLock(sensitive = true, always = true, revealed = false, lockOn = false, phoneHasLock = true))
        assertFalse(needsLock(sensitive = true, always = true, revealed = false, lockOn = true, phoneHasLock = false))
    }
}
