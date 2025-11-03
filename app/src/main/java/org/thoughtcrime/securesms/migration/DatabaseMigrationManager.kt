package org.thoughtcrime.securesms.migration

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class DatabaseMigrationManager @Inject constructor(
    private val application: Application,
    private val prefs: TextSecurePreferences,
    private val databaseSecretProvider: DatabaseSecretProvider,
    jsonProvider: Provider<Json>,
    @param:ManagerScope private val scope: CoroutineScope,
) : OnAppStartupComponent {
    private val dbSecret by lazy {
        databaseSecretProvider.getOrCreateDatabaseSecret()
    }

    // Access to openHelper: it's guaranteed to be created after the migration is done.
    val openHelper: SQLCipherOpenHelper by lazy {
        requestMigration(false)

        // First perform a cheap check to see if the migration is done, if so we can skip the wait.
        if (mutableMigrationState.value != MigrationState.Completed) {
            // Wait until the migration is done. This is a semi-expensive call but it's necessary
            // to block the callers from accessing the database until we have sorted out the migration
            // process. Note that we don't pass in errors here as the callers of this function
            // don't expect the exceptions at all so we have no choice but to block them.
            runBlocking {
                migrationState.first { it == MigrationState.Completed }
            }
        }

        SQLCipherOpenHelper(application, dbSecret, jsonProvider)
    }

    private val mutableMigrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)

    val migrationState: StateFlow<MigrationState>
        get() = mutableMigrationState

    @Synchronized
    private fun migrateDatabaseIfNeeded(fromRetry: Boolean) {
        val currState = mutableMigrationState.value
        if (currState == MigrationState.Completed || currState is MigrationState.Migrating) {
            Log.w(TAG, "Already completed or in progress")
            return
        }

        if (!fromRetry && currState is MigrationState.Error) {
            Log.w(TAG, "Migration failed before, aborting as it's not an explicit retry")
            return
        }

        // List steps to be performed: pair of action name string res id and the action lambda
        val stepDescriptors: List<ProgressStepDescriptor> = listOf(
            ProgressStepDescriptor("Migrate cipher settings", R.string.databaseOptimizing, R.string.waitFewMinutes, ::migrateCipherSettings)
        )

        // Accumulated progress steps
        val steps = MutableList(stepDescriptors.size) {
            ProgressStep(
                title = application.getString(stepDescriptors[it].title),
                subtitle = application.getString(stepDescriptors[it].subtitle),
                percentage = 0
            )
        }

        mutableMigrationState.value = MigrationState.Migrating(steps.toList())

        try {
            for ((index, desc) in stepDescriptors.withIndex()) {
                Log.d(TAG, "Starting migration step: ${desc.name}")
                val stepStartedAt = SystemClock.elapsedRealtime()
                try {
                    (desc.action)(fromRetry)
                } catch (e: Exception) {
                    Log.d(TAG, "Error performing migration step: ${desc.name}", e)
                    throw e
                }

                steps[index] = steps[index].copy(percentage = 100)
                Log.d(TAG, "Completed migration step: ${desc.name}, time taken = ${SystemClock.elapsedRealtime() - stepStartedAt}ms")

                mutableMigrationState.value = MigrationState.Migrating(steps.toList())
            }

            mutableMigrationState.value = MigrationState.Completed
        } catch (ec: Exception) {
            mutableMigrationState.value = MigrationState.Error(ec)
            return
        }
    }

    private fun migrateCipherSettings(fromRetry: Boolean) {
        if (prefs.migratedToDisablingKDF) {
            Log.i(TAG, "Already migrated to latest cipher settings")
            return
        }

        // List of the possible old databases and their settings. The order
        // is important: we start from the latest version and go to the oldest.
        // This is because we only migrate the latest version, since they have the
        // latest user data.
        val oldDatabasesAndSettings = listOf(
            CIPHER4_DB_NAME to mapOf(
                "kdf_iter" to "256000",
                "cipher_page_size" to "4096",
            ),

            CIPHER3_DB_NAME to mapOf(
                "kdf_iter" to "1",
                "cipher_page_size" to "4096",
                "cipher_compatibility" to "3",
            )
        )

        val newDb = application.getDatabasePath(SQLCipherOpenHelper.DATABASE_NAME)
        val newDbSettings = mapOf(
            "kdf_iter" to "1",
            "cipher_page_size" to "4096",
        )

        // Try to find an old db to update, if not, bail.
        val (oldDb, oldDbSettings) = oldDatabasesAndSettings.firstNotNullOfOrNull { (db, settings) ->
            val dbFile = application.getDatabasePath(db)
            if (dbFile.exists()) {
                dbFile to settings
            } else {
                null
            }
        } ?: run {
            Log.i(TAG, "No database to migrate")
            prefs.migratedToDisablingKDF = true
            return
        }

        Log.d(TAG, "Start migrating ${oldDb.path}")

        val hook = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) {
                // Set the new settings
                oldDbSettings.forEach { (key, value) ->
                    connection.executeRaw("PRAGMA $key = '$value';", null, null)
                }
            }

            override fun postKey(connection: SQLiteConnection) = preKey(connection)
        }

        if (newDb.exists()) {
            Log.d(
                TAG,
                "New database exists but we haven't done our migration, it's likely corrupted. Deleting."
            )
            application.deleteDatabase(SQLCipherOpenHelper.DATABASE_NAME)
        }

        SQLiteDatabase.openDatabase(
            oldDb.absolutePath,
            dbSecret.asString(),
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            hook
        ).use { db ->
            db.rawExecSQL(
                "ATTACH DATABASE ? AS new_db KEY ?",
                newDb.absolutePath,
                dbSecret.asString()
            )

            // Apply new cipher settings
            for ((key, value) in newDbSettings) {
                db.rawExecSQL("PRAGMA new_db.$key = '$value'")
            }

            // Apply the same user version as the old database
            db.rawExecSQL("PRAGMA new_db.user_version = ${db.version}")

            // Export the old database to the new one
            db.rawExecSQL("SELECT sqlcipher_export('new_db')")

            // Detach the new database
            db.rawExecSQL("DETACH DATABASE new_db")

//            // Delay and fail at first
//            if (BuildConfig.DEBUG && !fromRetry) {
//                Thread.sleep(2000)
//                throw RuntimeException("Fail")
//            }
        }

        check(newDb.exists()) { "New database was not created" }
        prefs.migratedToDisablingKDF = true
    }

    fun requestMigration(fromRetry: Boolean) {
        scope.launch(Dispatchers.IO) {
            migrateDatabaseIfNeeded(fromRetry)
        }
    }

    private data class ProgressStepDescriptor(
        val name: String,

        @StringRes
        val title: Int,

        @StringRes
        val subtitle: Int,

        val action: (fromRetry: Boolean) -> Unit,
    )

    override fun onPostAppStarted() {
        requestMigration(fromRetry = false)
    }

    data class ProgressStep(
        val title: String,
        val subtitle: String,
        val percentage: Int,
    )

    sealed interface MigrationState {
        data object Idle : MigrationState
        data class Migrating(val steps: List<ProgressStep>) : MigrationState {
            init {
                check(steps.isNotEmpty()) { "Steps must not be empty" }
            }
        }
        data class Error(val throwable: Throwable) : MigrationState
        data object Completed : MigrationState
    }

    companion object {
        const val CIPHER3_DB_NAME = "signal.db"
        const val CIPHER4_DB_NAME = "signal_v4.db"

        private const val TAG = "DatabaseMigrationManager"
    }
}