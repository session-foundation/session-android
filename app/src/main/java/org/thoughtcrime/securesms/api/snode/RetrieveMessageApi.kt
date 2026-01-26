package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.model.RetrieveMessageResponse

class RetrieveMessageApi @AssistedInject constructor(
    @Assisted private val namespace: Int,
    @Assisted private val auth: SwarmAuth,
    @Assisted private val lastHash: String?,
    @Assisted private val maxSize: Int?,
    private val snodeClock: SnodeClock,
    private val json: Json,
    snodeApiErrorManager: SnodeApiErrorManager,
) : AbstractSnodeApi<RetrieveMessageResponse>(
    snodeApiErrorManager = snodeApiErrorManager,
) {
    override val methodName: String
        get() = "retrieve"

    override fun buildParams(): JsonElement {
        return buildAuthenticatedParameters(
            auth = auth,
            namespace = namespace,
            timestamp = snodeClock.currentTimeMillis(),
            verificationData = { namespaceText, timestamp ->
                "${methodName}${namespaceText}$timestamp"
            }
        ) {
            lastHash?.let { put("last_hash", JsonPrimitive(it)) }
            maxSize?.let { put("max_size", JsonPrimitive(it)) }
        }
    }

    override fun deserializeSuccessResponse(requestParams: JsonElement, body: JsonElement): RetrieveMessageResponse {
        return json.decodeFromJsonElement(body)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            namespace: Int,
            auth: SwarmAuth,
            lastHash: String?,
            maxSize: Int? = null
        ): RetrieveMessageApi
    }
}