package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.session.libsession.utilities.serializable.InstantAsSecondsSerializer
import java.time.Instant
import kotlin.time.Duration

class GetProRevocationApi @AssistedInject constructor(
    @Assisted private val ticket: Long?,
    private val json: Json,
    deps: ProApiDependencies,
) : ProApi<Int, ProRevocations>(deps) {
    override val responseDeserializer: DeserializationStrategy<ProRevocations>
        get() = ProRevocations.serializer()

    override fun convertErrorStatus(status: Int): Int = status

    override val endpoint: String
        get() = "get_pro_revocations"

    override fun buildJsonBody(): String {
        return json.encodeToString(
            mapOf(
                "ticket" to (ticket ?: 0L),
                "version" to 0
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(ticket: Long?): GetProRevocationApi
    }
}

@Serializable
class ProRevocations(
    val ticket: Long,
    val items: List<Item>,
    @SerialName("retry_in")
    val retryInSeconds: Long,
    @SerialName("retain_for")
    val retainForSeconds: Long,
) {
    @Serializable
    class Item(
        @Serializable(with = InstantAsSecondsSerializer::class)
        @SerialName("effective_ts")
        val effectiveFrom: Instant,

        @SerialName("revocation_tag")
        val revocationTag: String,
    )
}
