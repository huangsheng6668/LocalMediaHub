package com.juziss.localmediahub.di

import android.bluetooth.BluetoothManager
import android.content.Context
import com.juziss.localmediahub.ble.AndroidBlePeripheralManager
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BlePeripheralManager
import com.juziss.localmediahub.data.ServerConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun providePeripheralManager(
        @ApplicationContext context: Context,
    ): AndroidBlePeripheralManager = AndroidBlePeripheralManager(context)

    @Provides
    fun providePeripheralManagerInterface(
        impl: AndroidBlePeripheralManager,
    ): BlePeripheralManager = impl

    @Provides
    @Singleton
    fun provideBleController(
        peripheralManager: AndroidBlePeripheralManager,
        store: ServerConfigStore,
        @ApplicationContext context: Context,
        @ApplicationScope appScope: kotlinx.coroutines.CoroutineScope,
    ): BleController {
        val hardwareAvailable: () -> Boolean = {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter?.isEnabled == true
        }
        val controller = BleController(
            peripheralManager = peripheralManager,
            bleEnabledFlow = store.bleEnabled,
            bleHardwareAvailable = hardwareAvailable,
            saveBleEnabled = { enabled -> store.saveBleEnabled(enabled) },
        )
        // Drive the controller from the persisted setting. Each emission
        // re-evaluates whether BLE should be advertising/disabled. Without
        // this the controller stays in IDLE forever at runtime — the
        // BleController itself does not observe the flow.
        //
        // MVP scope note: BluetoothAdapter.isEnabled (the hardwareAvailable
        // lambda) can also change at runtime (user toggles Bluetooth in system
        // settings). We do NOT register a BluetoothAdapter state listener
        // here — re-evaluation happens on the next setting emission, app
        // restart, or when the user re-toggles the experimental switch. This
        // is intentional YAGNI for the MVP and matches the task instructions.
        appScope.launch {
            store.bleEnabled.collect { enabled ->
                controller.evaluateAvailability(enabled = enabled)
            }
        }
        return controller
    }
}
