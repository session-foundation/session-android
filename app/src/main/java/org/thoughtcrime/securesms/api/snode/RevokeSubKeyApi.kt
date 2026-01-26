package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.Base64

class RevokeSubKeyApi @AssistedInject constructor(
    @Assisted private val auth: SwarmAuth,
    @Assisted private val subAccountTokens: List<ByteArray>,
    errorManager: SnodeApiErrorManager,
    private val snodeClock: SnodeClock,
) : AbstractSnodeApi<Unit>(errorManager) {
    override fun deserializeSuccessResponse(requestParams: JsonElement, body: JsonElement) = Unit
    override val methodName: String get() = "revoke_subaccount"

    override fun buildParams(): JsonElement {
        return buildAuthenticatedParameters(
            auth = auth,
            timestamp = snodeClock.currentTimeMillis(),
            namespace = null,
            verificationData = { _, t ->
                subAccountTokens.fold(
                    "$methodName$t".toByteArray()
                ) { acc, subAccount -> acc + subAccount }
            }
        ) {
            put("revoke", JsonArray(subAccountTokens.map {
                JsonPrimitive(Base64.encodeBytes(it))
            }))
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            auth: SwarmAuth,
            subAccountTokens: List<ByteArray>,
        ): RevokeSubKeyApi
    }
}