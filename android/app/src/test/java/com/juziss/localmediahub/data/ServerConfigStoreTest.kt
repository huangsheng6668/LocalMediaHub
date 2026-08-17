package com.juziss.localmediahub.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ServerConfigStoreTest {

    private lateinit var context: Context
    private lateinit var store: ServerConfigStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDatastoreFiles(context)
        store = ServerConfigStore(context)
        runBlocking {
            withContext(Dispatchers.IO) {
                store.clearConfig()
            }
        }
    }

    @Test
    fun lastConnectedBleAddress_saveAndClear_flowEmitsCorrectValues() = runTest {
        val store = ServerConfigStore(context)
        store.saveLastConnectedBleAddress("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", store.lastConnectedBleAddress.first())
        store.clearLastConnectedBleAddress()
        assertNull(store.lastConnectedBleAddress.first())
    }

    private fun deleteDatastoreFiles(context: Context) {
        try {
            val datastoreDir = context.filesDir.resolve("datastore")
            if (datastoreDir.exists()) {
                datastoreDir.deleteRecursively()
            }
            TimeUnit.MILLISECONDS.sleep(50)
        } catch (_: Exception) {}
    }
}
