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
) : ProApi<GetProRevocationsResponse>(deps) {
    override fun buildProRequest(): ProRequest =
        BackendRequests.buildRevocationsRequest(ticket ?: 0L)

    override fun parseResponse(json: String): GetProRevocationsResponse =
        BackendRequests.parseRevocationsResponse(json)

    @AssistedFactory
    interface Factory {
        fun create(ticket: Long?): GetProRevocationApi
    }
}
