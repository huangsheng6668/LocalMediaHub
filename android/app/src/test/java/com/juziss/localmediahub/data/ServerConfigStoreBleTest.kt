package com.juziss.localmediahub.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerConfigStoreBleTest {

    @Test
    fun bleEnabled_defaultsToFalse() = runBlocking {
        val store = ServerConfigStore(ApplicationProvider.getApplicationContext())
        assertFalse(store.bleEnabled.first())
    }

    @Test
    fun saveBleEnabled_persistsAndReadsBack() = runBlocking {
        val store = ServerConfigStore(ApplicationProvider.getApplicationContext())
        store.saveBleEnabled(true)
        assertTrue(store.bleEnabled.first())
    }
}
