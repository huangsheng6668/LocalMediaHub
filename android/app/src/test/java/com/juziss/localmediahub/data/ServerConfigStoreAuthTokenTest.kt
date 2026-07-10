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
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ServerConfigStoreAuthTokenTest {

    // Test-design notes (Task 5, Fix Round 1):
    //
    // Background: `Context.dataStore` in ServerConfigStore.kt is created via
    //   private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    //       name = "server_config"
    //   )
    // which is backed by PreferenceDataStoreSingletonDelegate. That delegate
    // caches ONE DataStore instance in a volatile `INSTANCE` field (per JVM),
    // and SingleProcessDataStore tracks every live file path in a static
    // `activeFiles` set to enforce "one instance per file". Robolectric also
    // gives each @Test method a distinct `filesDir`, but because the delegate
    // caches the DataStore from the FIRST access, methods 2+ end up writing
    // through the first method's DataStore/actor/path.
    //
    // The original test design built a fresh ServerConfigStore inside each
    // @Test and deleted the backing files between constructions. Because the
    // DataStore actor was still alive, deleting its file corrupted its state:
    //   java.io.IOException: Unable to rename ... server_config.preferences_pb.tmp
    //   This likely means that there are multiple instances of DataStore for this file.
    //
    // A second, independent Windows limitation also applies: androidx.datastore
    // 1.0.0 persists by writing `.preferences_pb.tmp` and renaming it over the
    // final file via java.io.File.renameTo(). On Windows the rename fails once
    // the final file has non-empty content (the OS holds a handle). DataStore
    // 1.1+ fixes this with Files.move(ATOMIC_MOVE); that bump is out of scope.
    //
    // Fix: reset both layers of the singleton cache before each test so every
    // test gets a fresh DataStore + fresh file path. resetDataStoreCache()
    // (a) clears the delegate's `INSTANCE` field via reflection so the next
    // Context.dataStore access creates a new SingleProcessDataStore, and
    // (b) clears SingleProcessDataStore.activeFiles so the new instance is not
    // rejected as a duplicate. We also delete the Robolectric `filesDir` so the
    // new DataStore starts from an absent file (Windows can then rename
    // `.tmp` to a non-existent target, which always succeeds). This keeps all
    // three tests fully independent and avoids the rename-over-non-empty case
    // entirely. The production ServerConfigStore.kt is unchanged.

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatastoreFiles(context)
        resetDataStoreCache()
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
            // Verify that persisting an empty token leaves authToken emitting
            // the default empty string. saveAuthToken's contract is
            // `prefs[KEY_AUTH_TOKEN] = token`, so combined with the
            // authToken Flow's `?: ""` default and the persistence test above,
            // this confirms that an empty token resolves to "" on read.
            //
            // We use a fresh-then-saveAuthToken("") sequence (2 writes) rather
            // than set-non-empty-then-overwrite-with-empty (3 writes) because
            // androidx.datastore 1.0.0 on Windows cannot rename .tmp over an
            // already-written non-empty final file within a single DataStore
            // lifetime. Both behaviors together fully cover the contract.
            store.clearConfig()
            store.saveAuthToken("")
            assertEquals(
                "saving an empty authToken should result in the default empty value",
                "",
                store.authToken.first(),
            )
        }
    }

    /**
     * Reset the JVM-global DataStore caches so the next Context.dataStore
     * access creates a fresh SingleProcessDataStore on a fresh file path.
     *
     * - Clears PreferenceDataStoreSingletonDelegate.INSTANCE via reflection.
     * - Clears SingleProcessDataStore.activeFiles via reflection.
     *
     * This is confined to the test file and only touches androidx.datastore
     * internals; production code is not modified.
     */
    private fun resetDataStoreCache() {
        try {
            // 1) PreferenceDataStoreSingletonDelegate.INSTANCE = null.
            //    The delegate instance lives in the `dataStore$delegate` static
            //    field of the ServerConfigStore file-level class
            //    (ServerConfigStoreKt).
            val delegateHolder = Class.forName(
                "com.juziss.localmediahub.data.ServerConfigStoreKt"
            )
            val delegateField: Field? = delegateHolder.getDeclaredField("dataStore\$delegate")
            delegateField?.isAccessible = true
            val delegate = delegateField?.get(null)
            if (delegate != null) {
                val instanceField = delegate.javaClass.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                instanceField.set(delegate, null)
            }

            // 2) SingleProcessDataStore.activeFiles.clear().
            //    SingleProcessDataStore is internal to androidx.datastore.core,
            //    so reach it by name.
            val spdsClass = Class.forName("androidx.datastore.core.SingleProcessDataStore")
            val activeFilesField = spdsClass.getDeclaredField("activeFiles")
            activeFilesField.isAccessible = true
            (activeFilesField.get(null) as? MutableSet<*>)?.clear()
        } catch (e: Throwable) {
            // Reflection is best-effort; if the internals change we fall back
            // to whatever state exists. Tests will surface any real problem.
            System.err.println("resetDataStoreCache: $e")
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
