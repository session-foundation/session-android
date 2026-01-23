package org.thoughtcrime.securesms.api.server

import org.session.libsession.network.ServerClientErrorManager
import org.session.libsession.network.ServerClientFailureContext
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse

abstract class ServerApi<ResponseType>(
    private val errorManager: ServerClientErrorManager,
) {
    abstract fun buildRequest(baseUrl: String, x25519PubKeyHex: String): HttpRequest

    suspend fun processResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): ResponseType {
        if (response.statusCode !in 200..299) {
            val failureContext = executorContext.getOrPut(ServerClientFailureContextKey) {
                ServerClientFailureContext(
                    url = baseUrl,
                    previousErrorCode = null
                )
            }

            val failureDecision = errorManager.onFailure(
                errorCode = response.statusCode,
                ctx = failureContext,
            )

            executorContext.set(
                key = ServerClientFailureContextKey,
                value = failureContext.copy(previousErrorCode = response.statusCode)
            )

            val exception = ServerApiError.UnknownStatusCode(
                api = this::class.java,
                code = response.statusCode,
                body = response.body,
            )

            if (failureDecision != null) {
                throw ErrorWithFailureDecision(
                    cause = exception,
                    failureDecision = failureDecision
                )
            } else {
                throw exception
            }
        }

        return handleSuccessResponse(executorContext, baseUrl, response)
    }


    abstract suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): ResponseType

    private object ServerClientFailureContextKey :
        ApiExecutorContext.Key<ServerClientFailureContext>
}