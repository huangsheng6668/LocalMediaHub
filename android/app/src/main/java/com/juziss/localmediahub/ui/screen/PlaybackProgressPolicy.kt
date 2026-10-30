package com.juziss.localmediahub.ui.screen

import androidx.compose.runtime.saveable.Saver
import kotlin.math.abs

/**
 * Playback position carried across configuration changes / process death.
 *
 * Deliberately NOT a snapshot state: the progress loop writes it every tick,
 * and an observable value would invalidate the whole player composition each
 * time while the position is only read once, when the player is rebuilt.
 */
internal class PlaybackPositionHolder(var value: Long)

internal val PlaybackPositionHolderSaver: Saver<PlaybackPositionHolder, Long> = Saver(
    save = { it.value },
    restore = { PlaybackPositionHolder(it) },
)

/** Two persisted positions closer than this count as "no progress". */
internal const val PROGRESS_PERSIST_MIN_DELTA_MS: Long = 5_000L

/** Tick that refreshes the in-memory holder and (gated) the store. */
internal const val PROGRESS_PERSIST_INTERVAL_MS: Long = 10_000L

/**
 * Whether [positionMs] is worth writing to RecentActivityStore.
 *
 * Guards a destructive property of the store: [RecentActivityStore.savePlaybackProgress]
 * DELETES the stored entry when `isValidProgress` rejects the pair (position
 * < 10 s or duration <= 0). Flushing while the player is still preparing
 * (duration unknown) or before the resume seek is applied (position 0) would
 * therefore wipe the resume point the user is currently resuming from.
 */
internal fun shouldPersistPlaybackProgress(
    positionMs: Long,
    durationMs: Long,
    lastPersistedPositionMs: Long,
): Boolean {
    if (durationMs <= 0L) return false
    if (positionMs <= 0L) return false
    return abs(positionMs - lastPersistedPositionMs) >= PROGRESS_PERSIST_MIN_DELTA_MS
}
