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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ServerConfigStoreAuthTokenTest {

    // Test-design notes (Task 5, Fix Round 2):
    //
    // DataStore was bumped from 1.0.0 to 1.1.1. Two problems in 1.0.0 that
    // required elaborate workarounds are now resolved:
    //
    // 1. Windows rename bug: 1.0.0 persisted by writing
    //    `.preferences_pb.tmp` and renaming it over the final file via
    //    java.io.File.renameTo(). On Windows the rename fails once the final
    //    file has non-empty content (the OS holds a handle), so a second write
    //    within the same DataStore lifetime failed with:
    //      java.io.IOException: Unable to rename ... server_config.preferences_pb.tmp
    //    DataStore 1.1+ fixes this with Files.move(ATOMIC_MOVE).
    //
    // 2. Reflection-based cache reset: Fix Round 1 used reflection to clear
    //    PreferenceDataStoreSingletonDelegate.INSTANCE and
    //    SingleProcessDataStore.activeFiles. On 1.1.1 the internal class
    //    `androidx.datastore.core.SingleProcessDataStore` moved/renamed, so
    //    the reflection silently failed (ClassNotFoundException) and the tests
    //    passed anyway — proving the reflection was dead weight. It has been
    //    removed entirely.
    //
    // The only inter-test cleanup still needed is deleting the Robolectric
    // `filesDir/datastore` directory in @Before, which gives each @Test a
    // clean slate. The three tests below are fully independent and the third
    // test now exercises the full spec-correct sequence:
    //   clearConfig → saveAuthToken("temp") → assert "temp" → saveAuthToken("") → assert "".

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatastoreFiles(context)
    }

    @Test
    fun authTokenDefaultsToEmpty() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServerConfigStore(context)
        withContext(Dispatchers.IO) {
            store.clearConfig()
            assertEquals("authToken should default to empty", "", store.authToken.first())
        }
    }

    @Test
    fun saveAuthTokenPersists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServerConfigStore(context)
        withContext(Dispatchers.IO) {
            store.clearConfig()
            store.saveAuthToken("my-test-token")
            assertEquals(
                "authToken should return the saved token",
                "my-test-token",
                store.authToken.first(),
            )
        }
    }

    @Test
    fun saveEmptyAuthTokenClearsValue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServerConfigStore(context)
        withContext(Dispatchers.IO) {
            // Spec: saveAuthToken("temp") → saveAuthToken("") → assert "".
            // This is a 3-write sequence (clearConfig, save "temp", save "")
            // which validates that overwriting a non-empty value with an
            // empty string actually clears it. DataStore 1.1.1 uses
            // Files.move(ATOMIC_MOVE), which works on Windows when renaming
            // .tmp over an already-written non-empty final file.
            store.clearConfig()
            store.saveAuthToken("temp")
            assertEquals("temp", store.authToken.first())  // verify "temp" persisted
            store.saveAuthToken("")
            assertEquals(
                "saving an empty authToken should clear the previous value",
                "",
                store.authToken.first(),
            )
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
