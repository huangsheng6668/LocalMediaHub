package com.juziss.localmediahub.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Dedicated BLE token storage — mirrors ServerConfigStoreAuthTokenTest.
 * The BLE key uses the same encrypt-at-rest path (TokenCrypto) as authToken;
 * the two fields must stay independent (no cross-pollution).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ServerConfigStoreBleTokenTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatastoreFiles(context)
    }

    @Test
    fun bleTokenDefaultsToEmpty() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServerConfigStore(context)
        withContext(Dispatchers.IO) {
            store.clearConfig()
            assertEquals("", store.bleToken.first())
        }
    }

    @Test
    fun saveBleTokenPersists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServerConfigStore(context)
        withContext(Dispatchers.IO) {
            store.clearConfig()
            store.saveBleToken("ble-test-key")
            assertEquals("ble-test-key", store.bleToken.first())
        }
    }

    @Test
    fun saveEmptyBleTokenClearsValue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServerConfigStore(context)
        withContext(Dispatchers.IO) {
            store.clearConfig()
            store.saveBleToken("temp")
            assertEquals("temp", store.bleToken.first())
            store.saveBleToken("")
            assertEquals("", store.bleToken.first())
        }
    }

    @Test
    fun bleTokenAndAuthTokenAreIndependent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServerConfigStore(context)
        withContext(Dispatchers.IO) {
            store.clearConfig()
            store.saveAuthToken("http-token")
            store.saveBleToken("ble-key")
            assertEquals("http-token", store.authToken.first())
            assertEquals("ble-key", store.bleToken.first())
            // 清除 BLE 密钥不得影响 authToken
            store.saveBleToken("")
            assertEquals("http-token", store.authToken.first())
            assertEquals("", store.bleToken.first())
        }
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
