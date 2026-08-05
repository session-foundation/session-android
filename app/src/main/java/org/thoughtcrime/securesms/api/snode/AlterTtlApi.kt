package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.ApiExecutorContext

class AlterTtlApi @AssistedInject constructor(
    @Assisted private val messageHashes: Collection<String>,
    @Assisted private val auth: SwarmAuth,
    @Assisted private val alterType: AlterType,
    @Assisted private val newExpiry: Long,
    errorManager: SnodeApiErrorManager,
    private val snodeClock: SnodeClock,
    private val json: Json,
) : AbstractSnodeApi<AlterTtlApi.Result>(errorManager) {
    override fun deserializeSuccessResponse(ctx: ApiExecutorContext, body: JsonElement): Result {
        // By the time we're reading this the expiries have already been altered, which is this
        // request's actual job. Reading the response is only how we notice configs going missing, so a
        // surprise in its shape must not turn a successful alteration into a failed request.
        val report = runCatching {
            val response: Response = json.decodeFromJsonElement(body)

            detectMissingConfigHashes(
                requestedHashes = messageHashes,
                extendRequested = alterType == AlterType.Extend,
                swarm = response.swarm,
            )
        }.getOrElse { e ->
            Log.w("AlterTtlApi", "Unable to read the expire response for missing configs", e)
            ConfigExpiryReport.Inconclusive
        }

        return Result(expiry = report)
    }

    override val methodName: String
        get() = "expire"

    override fun buildParams(ctx: ApiExecutorContext): JsonElement {
        return buildAuthenticatedParameters(
            auth = auth,
            namespace = null,
            timestamp = snodeClock.currentTimeMillis(),
            verificationData = { _, _ ->
                buildString {
                    append(methodName)
                    append(alterType.value)
                    append(newExpiry.toString())
                    messageHashes.forEach(this::append)
                }
            }
        ) {
            put("expiry", JsonPrimitive(newExpiry))
            put("messages", JsonArray(messageHashes.map(::JsonPrimitive)))
            when (alterType) {
                AlterType.Extend -> put("extend", JsonPrimitive(true))
                AlterType.Shorten -> put("shorten", JsonPrimitive(true))
                AlterType.Unspecified -> {}
            }
        }

    }

    /**
     * @property expiry Which of the requested hashes the swarm turned out to have lost. Only an
     *  extend request can tell us this — see [detectMissingConfigHashes].
     */
    data class Result(
        val expiry: ConfigExpiryReport,
    )

    enum class AlterType(val value: String) {
        Extend("extend"),
        Shorten("shorten"),
        Unspecified("")
    }

    @Serializable
    private class Response(
        val swarm: Map<String, SnodeExpiryState> = emptyMap()
    )

    @AssistedFactory
    interface Factory {
        fun create(
            messageHashes: Collection<String>,
            auth: SwarmAuth,
            alterType: AlterType,
            newExpiry: Long
        ): AlterTtlApi
    }
}
