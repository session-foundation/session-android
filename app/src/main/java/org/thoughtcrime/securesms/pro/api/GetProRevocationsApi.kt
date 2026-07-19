package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.GetProRevocationsResponse
import network.loki.messenger.libsession_util.pro.ProRequest

class GetProRevocationApi @AssistedInject constructor(
    @Assisted private val ticket: Long?,
    deps: ProApiDependencies,
) : ProApi<Int, GetProRevocationsResponse>(deps) {
    override fun buildProRequest(): ProRequest =
        BackendRequests.buildRevocationsRequest(ticket ?: 0L)

    override fun parseResponse(json: String): GetProRevocationsResponse =
        BackendRequests.parseRevocationsResponse(json)

    override fun convertErrorStatus(status: Int): Int = status

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
