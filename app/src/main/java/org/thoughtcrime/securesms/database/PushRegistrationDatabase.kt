package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.sqlite.db.transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.util.asSequence
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class PushRegistrationDatabase @Inject constructor(
    @ApplicationContext context: Context,
    helper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(context, helper) {


    @Serializable
    data class EnsureRegistration(val accountId: String, val input: Input)

    /**
     * Ensure that the provided registrations exist in the database. If input changes for an existing
     * registration, reset its state to NONE. Any registrations not in the provided list will be
     * marked as pending unregistration.
     *
     * @return True if any database rows were inserted or updated.
     */
    fun ensureRegistrations(registrations: Collection<EnsureRegistration>): Boolean {
        val accountIDsAsText = json.encodeToString(registrations.map { it.accountId })
        val registrationsAsText = json.encodeToString(registrations)

        // It's important to specify the base RegistrationState so that the discriminator is correct
        val noneStateAsText = json.encodeToString<RegistrationState>(RegistrationState.None)
        val pendingUnregisterAsText = json.encodeToString<RegistrationState>(RegistrationState.PendingUnregister)

        return writableDatabase.transaction {
            var numChanges: Int = 0

            if (registrations.isNotEmpty()) {
                // Make sure we have all the registrations by
                // 1. Inserting new rows with NONE state
                // 2. Updating existing rows to NONE state if input has changed, or if they are in PENDING_UNREGISTER state
                compileStatement(
                    """
                INSERT INTO push_registration_state (account_id, input, state)
                SELECT 
                    value->>'$.accountId',
                    value->>'$.input',
                    :none_state
                FROM json_each(:registrations)
                WHERE true
                ON CONFLICT DO UPDATE 
                    SET input = excluded.input,
                       state = :none_state
                    WHERE input != excluded.input OR state_type = '$TYPE_PENDING_UNREGISTER'
            """
                ).use { stmt ->
                    stmt.bindString(1, noneStateAsText)
                    stmt.bindString(2, registrationsAsText)
                    numChanges += stmt.executeUpdateDelete()
                }
            }

            // Mark all other registrations as PENDING_UNREGISTER to be cleaned up
            compileStatement("""
                UPDATE push_registration_state
                SET state = ?
                WHERE account_id NOT IN (SELECT value FROM json_each(?))
            """).use { stmt ->
                stmt.bindString(1, pendingUnregisterAsText)
                stmt.bindString(2, accountIDsAsText)
                numChanges += stmt.executeUpdateDelete()
            }

            numChanges > 0
        }
    }

    fun updateRegistrationStates(accountIdAndStates: Collection<Pair<String, RegistrationState>>) {
        writableDatabase.compileStatement(
            """
            UPDATE push_registration_state 
            SET state = :state
            WHERE account_id = :account_id AND state != :state
            """
        ).use { stmt ->
            var numUpdated = 0
            for ((accountId, state) in accountIdAndStates) {
                stmt.clearBindings()
                stmt.bindString(1, json.encodeToString(state))
                stmt.bindString(2, accountId)
                numUpdated += stmt.executeUpdateDelete()
            }

            numUpdated > 0
        }
    }

    /**
     * Get registrations that are due for processing. This includes:
     * - Registrations in ERROR or REGISTERED state whose due time is <= now
     * - Registrations in NONE state (which should be processed immediately)
     */
    fun getDueRegistrations(now: Instant, limit: Int): List<Registration> {
        return readableDatabase.rawQuery(
            """
            SELECT account_id, input, state, CAST(state->>'$.due' AS INTEGER) AS due_time 
            FROM push_registration_state
            WHERE state_type IN ('$TYPE_ERROR', '$TYPE_REGISTERED')
                AND CAST(state->>'$.due' AS INTEGER) <= ?
            
            UNION ALL
            
            SELECT account_id, input, state, 0 AS due_time
            FROM push_registration_state
            WHERE state_type = '$TYPE_NONE'
                
            ORDER BY due_time ASC
            LIMIT ?
        """, now.toEpochMilli(), limit
        ).use { cursor ->
            cursor.asSequence()
                .map {
                    Registration(
                        accountId = cursor.getString(0),
                        input = json.decodeFromString(cursor.getString(1)),
                        state = json.decodeFromString(cursor.getString(2)),
                    )
                }
                .toList()
        }
    }

    fun getPendingRegistrations(limit: Int): List<Registration> {
        return readableDatabase.rawQuery(
            """
            SELECT account_id, input, state
            FROM push_registration_state
            WHERE state_type = '$TYPE_PENDING_UNREGISTER'
            LIMIT ?
        """, limit
        ).use { cursor ->
            cursor.asSequence()
                .map {
                    Registration(
                        accountId = cursor.getString(0),
                        input = json.decodeFromString(cursor.getString(1)),
                        state = json.decodeFromString(cursor.getString(2)),
                    )
                }
                .toList()
        }
    }

    fun removeRegistrations(accountIds: Collection<String>) {
        if (accountIds.isEmpty()) return

        val accountIDsAsText = json.encodeToString(accountIds)

        writableDatabase.execSQL(
            """
            DELETE FROM push_registration_state
            WHERE account_id IN (SELECT value FROM json_each(?))
            """, arrayOf(accountIDsAsText)
        )
    }

    /**
     * Get the next due time among all registrations. Null if there are no pending registrations.
     *
     * Note that the due time can be in the past or now, meaning there are som registrations
     * that must be processed immediately.
     */
    fun getNextProcessTime(now: Instant = Instant.now()): Instant? {
        // The NONE state means we should process immediately, so we'll look them up first
        readableDatabase.rawQuery(
            """
                SELECT 1 FROM push_registration_state
                WHERE state_type IN ('$TYPE_NONE', '$TYPE_PENDING_UNREGISTER')
            """
        ).use { cursor ->
            if (cursor.moveToNext()) {
                return now
            }
        }

        // Otherwise, find the minimum due time among ERROR and REGISTERED states
        readableDatabase.rawQuery(
            """
            SELECT MIN(CAST(state->>'$.due' AS INTEGER))
            FROM push_registration_state
            WHERE state_type IN ('$TYPE_ERROR', '$TYPE_REGISTERED')
        """,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val dueMillis = cursor.getLong(0)
                if (!cursor.isNull(0)) {
                    return Instant.ofEpochMilli(dueMillis)
                }
            }
        }

        return null
    }

    @Serializable
    data class Registration(
        val accountId: String,
        val input: Input,
        val state: RegistrationState
    )

    /**
     * The registration state that is saved in the db.
     *
     * Please note that any changes to this class must consider the backward compatibility
     * to the existing data in the database.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator(STATE_TYPE_DISCRIMINATOR)
    sealed interface RegistrationState {
        @Serializable
        @SerialName(TYPE_NONE)
        data object None : RegistrationState

        @Serializable
        @SerialName(TYPE_REGISTERED)
        data class Registered(
            @Serializable(with = InstantAsMillisSerializer::class)
            val due: Instant
        ) : RegistrationState

        @Serializable
        @SerialName(TYPE_ERROR)
        data class Error(
            @Serializable(with = InstantAsMillisSerializer::class)
            val due: Instant,
            val numRetried: Int,
        ) : RegistrationState

        @Serializable
        @SerialName(TYPE_PERMANENT_ERROR)
        data object PermanentError : RegistrationState

        @Serializable
        @SerialName(TYPE_PENDING_UNREGISTER)
        data object PendingUnregister : RegistrationState
    }

    /**
     * The input required to perform a push registration.
     */
    @Serializable
    data class Input(
        val pushToken: String
    )

    companion object {
        private const val STATE_TYPE_DISCRIMINATOR = "type"

        private const val TYPE_NONE = "NONE"
        private const val TYPE_REGISTERED = "REGISTERED"
        private const val TYPE_ERROR = "ERROR"
        private const val TYPE_PERMANENT_ERROR = "PERMANENT_ERROR"
        private const val TYPE_PENDING_UNREGISTER = "PENDING_UNREGISTER"

        fun createTableStatements() = arrayOf(
            """
            CREATE TABLE push_registration_state(
                account_id TEXT NOT NULL PRIMARY KEY,
                input TEXT NOT NULL,
                state TEXT NOT NULL,
                state_type TEXT GENERATED ALWAYS AS (state->>'$.$STATE_TYPE_DISCRIMINATOR') VIRTUAL
            ) WITHOUT ROWID""",
            "CREATE INDEX idx_push_state_type ON push_registration_state(state_type)",
            "CREATE INDEX idx_push_due ON push_registration_state(CAST(state->>'$.due' AS INTEGER))"
        )
    }
}