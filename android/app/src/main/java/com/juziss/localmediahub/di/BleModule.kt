package com.juziss.localmediahub.di

import android.bluetooth.BluetoothManager
import android.content.Context
import com.juziss.localmediahub.ble.AndroidBlePeripheralManager
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BlePeripheralManager
import com.juziss.localmediahub.ble.BleTransportFallback
import com.juziss.localmediahub.data.ServerConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Named
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
        bleTransportFallback: BleTransportFallback,
        store: ServerConfigStore,
        @ApplicationContext context: Context,
        @ApplicationScope appScope: kotlinx.coroutines.CoroutineScope,
    ): BleController {
        val hardwareAvailable: () -> Boolean = {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter?.isEnabled == true
        }
        // Phase 9 (Task 9): cache the latest decrypted auth token for the
        // controller's synchronous authTokenProvider (DataStore is async-only;
        // the handshake needs the token the instant a challenge arrives). The
        // cache starts EMPTY — fail closed: no token observed yet means the
        // BLE handshake is refused rather than run with a wrong/empty key —
        // and updates on every token save/clear emission.
        val latestAuthToken = kotlinx.coroutines.flow.MutableStateFlow("")
        appScope.launch {
            store.authToken.collect { latestAuthToken.value = it }
        }
        val controller = BleController(
            peripheralManager = peripheralManager,
            bleTransportFallback = bleTransportFallback,
            bleEnabledFlow = store.bleEnabled,
            bleHardwareAvailable = hardwareAvailable,
            saveBleEnabled = { enabled -> store.saveBleEnabled(enabled) },
            authTokenProvider = { latestAuthToken.value },
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

    /**
     * Task 3: provides the BLE fallback reassembly engine as a Hilt singleton.
     *
     * [BleTransportFallback]'s constructor has all-default parameters so tests
     * can inject deterministic clocks; annotating that constructor `@Inject`
     * made Kotlin's synthetic no-arg constructor ALSO injectable, which Hilt
     * rejected ("may only contain one injected constructor"). Providing it
     * here keeps the test-friendly defaults while giving Hilt exactly one
     * binding to use in production.
     */
    @Provides
    @Singleton
    fun provideBleTransportFallback(): BleTransportFallback = BleTransportFallback()

    /**
     * Task 2: exposes the persisted `bleEnabled` flag as a qualified `Flow`
     * for [com.juziss.localmediahub.viewmodel.BleSettingsViewModel]'s
     * three-signal auto-connect trigger. Qualifier avoids colliding with
     * [provideBleController]'s unqualified `Flow<Boolean>` parameter.
     */
    @Provides
    @Named("bleEnabled")
    fun provideBleEnabledFlow(store: ServerConfigStore): Flow<Boolean> = store.bleEnabled
}
