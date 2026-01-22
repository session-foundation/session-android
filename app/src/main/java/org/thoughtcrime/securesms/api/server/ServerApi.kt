package org.thoughtcrime.securesms.api.server

import okhttp3.Request
import okhttp3.Response
import org.session.libsession.network.ServerClientErrorManager
import org.session.libsession.network.ServerClientFailureContext
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision

abstract class ServerApi<ResponseType>(
    private val errorManager: ServerClientErrorManager,
) {
    abstract fun buildRequest(baseUrl: String, x25519PubKeyHex: String): Request

    suspend fun processResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: Response
    ): ResponseType {
        if (response.code !in 200..299) {
            val failureContext = executorContext.getOrPut(ServerClientFailureContextKey) {
                ServerClientFailureContext(
                    url = baseUrl,
                    previousErrorCode = null
                )
            }

            val failureDecision = errorManager.onFailure(
                errorCode = response.code,
                ctx = failureContext,
            )

            executorContext.set(
                key = ServerClientFailureContextKey,
                value = failureContext.copy(previousErrorCode = response.code)
            )

            val exception =  ServerApiError.UnknownStatusCode(
                code = response.code,
                bodyText = null // TODO: extract body text if needed
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
        response: Response
    ): ResponseType

    private object ServerClientFailureContextKey :
        ApiExecutorContext.Key<ServerClientFailureContext>
}