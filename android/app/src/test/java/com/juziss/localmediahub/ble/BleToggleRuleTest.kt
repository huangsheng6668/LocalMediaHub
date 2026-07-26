package com.juziss.localmediahub.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic test for the BLE experimental-toggle gating rule.
 *
 * The UI toggle must be non-interactive (greyed, not clickable) when the
 * device has no usable Bluetooth adapter. The rule itself is hardware-free
 * and framework-free so it can be unit-tested without Robolectric.
 */
class BleToggleRuleTest {

    @Test
    fun toggleEnabled_whenHardwareAvailable() {
        assertTrue(BleToggleRule.canToggle(hardwareAvailable = true))
    }

    @Test
    fun toggleDisabled_whenNoHardware() {
        assertFalse(BleToggleRule.canToggle(hardwareAvailable = false))
    }
}
