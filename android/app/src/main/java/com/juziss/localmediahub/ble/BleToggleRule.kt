package com.juziss.localmediahub.ble

/**
 * Pure rule for whether the experimental BLE toggle in the settings UI should
 * be interactive (clickable). It must be greyed out on devices that have no
 * usable Bluetooth adapter, so users see an honest "此设备不支持" state instead
 * of a switch they can flip but that never does anything.
 *
 * Keeping this logic in a tiny rule object lets the gating decision be unit
 * -tested without Robolectric or a real Bluetooth stack — see
 * `BleToggleRuleTest`.
 */
object BleToggleRule {
    fun canToggle(hardwareAvailable: Boolean): Boolean = hardwareAvailable
}
