package com.juziss.localmediahub.data

import com.juziss.localmediahub.viewmodel.ConnectionState
import com.juziss.localmediahub.viewmodel.shouldAttemptAutoConnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePathTest {

    @Test
    fun `normalizeRoutePath keeps windows absolute paths stable`() {
        val path = """F:\Media\Anime\episode01.mp4"""

        assertEquals("F:/Media/Anime/episode01.mp4", normalizeRoutePath(path))
    }

    @Test
    fun `normalizeRoutePath trims leading slash from non-windows paths`() {
        val path = "/relative/path/to/file.jpg"

        assertEquals("relative/path/to/file.jpg", normalizeRoutePath(path))
    }

    @Test
    fun `shouldAttemptAutoConnect stays off on launch even when a saved server exists`() {
        assertFalse(
            shouldAttemptAutoConnect(
                savedIp = "192.168.1.10",
                autoConnectAttempted = false,
                connectionState = ConnectionState.Idle,
            )
        )

        assertFalse(
            shouldAttemptAutoConnect(
                savedIp = "",
                autoConnectAttempted = false,
                connectionState = ConnectionState.Idle,
            )
        )

        assertFalse(
            shouldAttemptAutoConnect(
                savedIp = "192.168.1.10",
                autoConnectAttempted = true,
                connectionState = ConnectionState.Idle,
            )
        )

        assertFalse(
            shouldAttemptAutoConnect(
                savedIp = "192.168.1.10",
                autoConnectAttempted = false,
                connectionState = ConnectionState.Testing,
            )
        )
    }
}
