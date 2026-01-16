package org.thoughtcrime.securesms.rpc.storage

import kotlinx.serialization.json.JsonElement
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.rpc.error.ClockOutOfSyncException

abstract class AbstractStorageServiceRequest<RespType : StorageServiceResponse> :
    StorageServiceRequest<RespType> {
    final override fun deserializeResponse(snode: Snode, code: Int, body: JsonElement?): RespType {
        when (code) {
            in 200..299 -> {
                return deserializeSuccessResponse(checkNotNull(body) {
                    "Expected non-null body for successful response"
                })
            }

            406 -> {
                errorManager.handleError()
                throw ExceptionWithFailureDecission(
                    shouldRetry = true
                )
            }

            401 -> throw SnodeNoLongerPartOfSwarmException(snode)

            else -> error("Error performing ${this.javaClass.simpleName} on $snode: code = $code, body = $body")
        }
    }

    abstract fun deserializeSuccessResponse(body: JsonElement): RespType
}