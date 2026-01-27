package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.thoughtcrime.securesms.api.ApiExecutorContext

class AlterTtlApi @AssistedInject constructor(
    @Assisted private val messageHashes: Collection<String>,
    @Assisted private val auth: SwarmAuth,
    @Assisted private val alterType: AlterType,
    @Assisted private val newExpiry: Long,
    errorManager: SnodeApiErrorManager,
    private val snodeClock: SnodeClock,
) : AbstractSnodeApi<Unit>(errorManager) {
    override fun deserializeSuccessResponse(ctx: ApiExecutorContext, body: JsonElement) {}

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

    enum class AlterType(val value: String) {
        Extend("extend"),
        Shorten("shorten"),
        Unspecified("")
    }

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