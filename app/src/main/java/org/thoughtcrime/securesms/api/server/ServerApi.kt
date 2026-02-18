package org.thoughtcrime.securesms.api.server

import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse

abstract class ServerApi<ResponseType>(
    private val errorManager: ServerApiErrorManager,
) {
    abstract fun buildRequest(baseUrl: String, x25519PubKeyHex: String): HttpRequest

    open suspend fun processResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): ResponseType {
        if (response.statusCode !in 200..299) {
            handleErrorResponse(
                executorContext = executorContext,
                baseUrl = baseUrl,
                response = response
            )
        } else {
            return handleSuccessResponse(executorContext, baseUrl, response)
        }
    }

    protected open suspend fun handleErrorResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): Nothing {
        val failureContext = executorContext.getOrPut(ServerClientFailureContextKey) {
            ServerClientFailureContext(
                previousErrorCode = null
            )
        }

        val (error, decision) = errorManager.onFailure(
            errorCode = response.statusCode,
            serverBaseUrl = baseUrl,
            bodyAsText = response.body.toText(),
            ctx = failureContext,
        )

        Log.d("ServerApi", "Network error from ${this.javaClass.simpleName} for a Server endpoint ($baseUrl), with status:${response.statusCode} - error: $error")

        executorContext.set(
            key = ServerClientFailureContextKey,
            value = failureContext.copy(previousErrorCode = response.statusCode)
        )

        if (decision != null) {
            throw ErrorWithFailureDecision(
                cause = error,
                failureDecision = decision
            )
        } else {
            throw error
        }
    }


    abstract suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): ResponseType

    private object ServerClientFailureContextKey :
        ApiExecutorContext.Key<ServerClientFailureContext>
}