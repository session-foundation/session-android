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
    data class Registration(val accountId: String, val input: Input)

    /**
     * Ensure that the provided registrations exist in the database. If input changes for an existing
     * registration, reset its state to NONE. Any registrations not in the provided list will be
     * marked as pending unregistration.
     *
     * @return The number of database rows that were changed.
     */
    fun ensureRegistrations(registrations: Collection<Registration>): Int {
        val registrationsAsText = json.encodeToString(registrations)

        // It's important to specify the base RegistrationState so that the discriminator is correct
        val noneStateAsText = json.encodeToString<RegistrationState>(RegistrationState.None)
        val pendingUnregisterAsText = json.encodeToString<RegistrationState>(RegistrationState.PendingUnregister)

        return writableDatabase.transaction {
            var numChanges = 0

            if (registrations.isNotEmpty()) {
                // Insert the provided registrations with NONE state
                // If they already exist with a "pending unregister" state, flip them back to NONE,
                // otherwise keep their existing state.
                compileStatement(
                    """
                INSERT INTO push_registration_state (account_id, input, state)
                SELECT 
                    value->>'$.accountId',
                    value->>'$.input',
                    :none_state
                FROM json_each(:registrations)
                WHERE TRUE
                ON CONFLICT DO UPDATE
                    SET state = :none_state
                    WHERE state_type = '$TYPE_PENDING_UNREGISTER'
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
                WHERE (account_id, input) NOT IN (SELECT value->>'$.accountId', value->>'$.input' FROM json_each(?))
            """).use { stmt ->
                stmt.bindString(1, pendingUnregisterAsText)
                stmt.bindString(2, registrationsAsText)
                numChanges += stmt.executeUpdateDelete()
            }

            // Delete all registrations that were previously marked as PermanentError and
            // are no longer desired.
            // Note: the changes here do not count towards numChanges since they won't affect
            // the scheduling
            compileStatement("""
                DELETE FROM push_registration_state
                WHERE state_type = '$TYPE_PERMANENT_ERROR'
                    AND (account_id, input) NOT IN (SELECT value->>'$.accountId', value->>'$.input' FROM json_each(?))
            """).use { stmt ->
                stmt.bindString(1, registrationsAsText)
                stmt.execute()
            }

            numChanges
        }
    }

    fun updateRegistrations(registrationWithStates: Collection<RegistrationWithState>) {
        writableDatabase.compileStatement(
            """
            UPDATE push_registration_state 
            SET state = ?
            WHERE account_id = ? AND input = ?
            """
        ).use { stmt ->
            for (r in registrationWithStates) {
                stmt.clearBindings()
                stmt.bindString(1, json.encodeToString(r.state))
                stmt.bindString(2, r.accountId)
                stmt.bindString(3, json.encodeToString(r.input))
                stmt.execute()
            }
        }
    }

    data class PendingRegistrationWork(
        val register: List<RegistrationWithState>,
        val unregister: List<RegistrationWithState>,
    )

    fun getPendingRegistrationWork(now: Instant = Instant.now(), limit: Int): PendingRegistrationWork {
        // This query needs to consider two type of data:
        // - Registrations that need to be registered (due REGISTER, due ERROR or NONE)
        // - Registrations that need to be unregistered (PENDING_UNREGISTER)
        // The query does not directly map to these two groups, so we partition the results in code.
        return readableDatabase.rawQuery(
            """
            SELECT account_id, input, state, CAST(state->>'$.due' AS INTEGER) AS due_time 
            FROM push_registration_state
            WHERE state_type IN ('$TYPE_ERROR', '$TYPE_REGISTERED')
                AND CAST(state->>'$.due' AS INTEGER) <= ?
            
            UNION ALL
            
            SELECT account_id, input, state, 0 AS due_time
            FROM push_registration_state
            WHERE state_type IN ('$TYPE_NONE', '$TYPE_PENDING_UNREGISTER')
            
            ORDER BY due_time ASC
            LIMIT ?
        """, now.toEpochMilli(), limit
        ).use { cursor ->
            val (unregister, register) = cursor.asSequence()
                .map {
                    RegistrationWithState(
                        accountId = cursor.getString(0),
                        input = json.decodeFromString(cursor.getString(1)),
                        state = json.decodeFromString(cursor.getString(2)),
                    )
                }
                .partition { it.state is RegistrationState.PendingUnregister }

            PendingRegistrationWork(register, unregister)
        }
    }

    fun removeRegistrations(registrations: Collection<Registration>) {
        if (registrations.isEmpty()) return

        writableDatabase.rawExecSQL(
            """
            DELETE FROM push_registration_state
            WHERE (account_id, input) IN (SELECT value->>'$.accountId', value->>'$.input' FROM json_each(?))
            """, json.encodeToString(registrations)
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
    data class RegistrationWithState(
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
                account_id TEXT NOT NULL,
                input TEXT NOT NULL,
                state TEXT NOT NULL,
                state_type TEXT GENERATED ALWAYS AS (state->>'$.$STATE_TYPE_DISCRIMINATOR') VIRTUAL,
                PRIMARY KEY (account_id, input)
            ) WITHOUT ROWID""",
            "CREATE INDEX idx_push_state_type ON push_registration_state(state_type)",
            "CREATE INDEX idx_push_due ON push_registration_state(CAST(state->>'$.due' AS INTEGER))"
        )
    }
}