package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Task 5: process-wide holder for the BLE-degraded flag, so the non-Hilt
 * Coil [coil3.ImageLoader] (built by `LocalMediaHubApplication.newImageLoader`,
 * which runs BEFORE Hilt has finished injecting `MediaRepository`) can read
 * the latest value.
 *
 * The flag's source of truth remains [com.juziss.localmediahub.data.MediaRepository];
 * the repository mirrors its `_isBleDegraded` value into this holder every
 * time it flips (see `setBleDegraded`). That keeps the read-site (Coil's
 * network interceptor) decoupled from Hilt's dependency graph while still
 * observing the same transitions the rest of the UI reads from the repository
 * StateFlow.
 *
 * Why a separate holder instead of Hilt-injecting MediaRepository into the
 * ImageLoader: `SingletonImageLoader.Factory.newImageLoader` is invoked lazily
 * the first time any `AsyncImage` composes — but it is invoked from Coil's own
 * initialization path which has no Hilt entry point, and wiring one would
 * require an `@EntryPoint` on the Application plus a manual Component lookup.
 * A `MutableStateFlow` in a package-level `object` is the minimal, race-free
 * bridge.
 *
 * Thread-safety: [MutableStateFlow] is thread-safe; reads via [isBleDegraded]
 * are non-blocking and always return the most recent write (StateFlow's
 * value-read is atomic).
 */
object BleDegradedState {

    private val _isBleDegraded = MutableStateFlow(false)

    /** Latest BLE-degraded flag. Read by the Coil short-circuit interceptor. */
    val isBleDegraded: StateFlow<Boolean> = _isBleDegraded.asStateFlow()

    /**
     * Mirror the repository's degraded flag here. Safe to call from any thread;
     * StateFlow only re-emits on a value change, so repeated calls with the
     * same value are no-ops.
     */
    fun setBleDegraded(value: Boolean) {
        _isBleDegraded.value = value
    }

    // ════════════════════════════════════════════════════════════════════
    //  Global transport health (real-device degraded-reading finding):
    //  with Wi-Fi off, every request burned the full HTTP connect timeout
    //  before the BLE failover could start. Transport selection is now
    //  decided from THIS process-wide state instead of per-request
    //  trial-and-error:
    //
    //  - [wifiOnline]: maintained by the ConnectivityManager callback the
    //    Application registers at startup — true while a validated Wi-Fi
    //    network is the active transport.
    //  - [httpBlockedUntilMs]: circuit breaker. A transport-level HTTP
    //    failure (Wi-Fi associated but the server is unreachable) blocks
    //    the HTTP fast path for a backoff window so a degraded session
    //    never re-pays the connect timeout per request; any HTTP success
    //    re-arms it.
    // ════════════════════════════════════════════════════════════════════

    /**
     * How long the HTTP fast path stays blocked after a transport-level
     * failure while BLE is connected: long enough that a degraded reading
     * session never re-pays the connect timeout per request, short enough
     * that Wi-Fi recovery is picked up promptly.
     */
    const val HTTP_BACKOFF_MS: Long = 30_000L

    @Volatile
    var wifiOnline: Boolean = true
        internal set

    @Volatile
    var httpBlockedUntilMs: Long = 0L
        internal set

    /**
     * Whether the HTTP fast path may be attempted at `nowMs`. False when the
     * OS reports no Wi-Fi or when the breaker is open. Callers must only
     * SKIP HTTP when this is false AND the BLE channel is connected —
     * otherwise HTTP remains the last resort.
     */
    fun httpAllowed(nowMs: Long): Boolean = wifiOnline && nowMs >= httpBlockedUntilMs
}
