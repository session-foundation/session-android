package org.session.libsession.snode

import android.os.SystemClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import org.session.libsession.snode.endpoint.Batch
import org.session.libsession.snode.endpoint.Endpoint
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.dependencies.ManagerScope

@OptIn(ExperimentalCoroutinesApi::class)
class AutoBatchRPCService @AssistedInject constructor(
    private val json: Json,
    @Assisted private val realService: StorageRPCService,
    @ManagerScope scope: CoroutineScope,
) : StorageRPCService {

    private data class BatchKey(val snodeAddress: String, val batchKey: String)

    private data class BatchRequestInfo<T, R>(
        val key: BatchKey,
        val snode: Snode,
        val endpoint: Endpoint<T, R>,
        val request: T,
        val resultCallback: SendChannel<Result<R>>,
        val requestTime: Long = SystemClock.elapsedRealtime(),
    )

    private val batchChannel: SendChannel<BatchRequestInfo<*, *>>

    init {
        val channel = Channel<BatchRequestInfo<*, *>>()
        batchChannel = channel

        scope.launch {
            val batches = hashMapOf<BatchKey, MutableList<BatchRequestInfo<*, *>>>()

            while (true) {
                val batch = select<List<BatchRequestInfo<*, *>>?> {
                    // If we receive a request, add it to the batch
                    channel.onReceive { req ->
                        batches.getOrPut(req.key) { mutableListOf() }.add(req)
                        null
                    }

                    // If we have anything in the batch, look for the one that is about to expire
                    // and wait for it to expire, remove it from the batches and send it for
                    // processing.
                    if (batches.isNotEmpty()) {
                        val earliestBatch = batches.minBy { it.value.first().requestTime }
                        val deadline = earliestBatch.value.first().requestTime + BATCH_WINDOW_MILLS
                        onTimeout(
                            timeMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                        ) {
                            batches.remove(earliestBatch.key)
                        }
                    }
                }

                if (batch != null) {
                    launch batch@{
                        val snode = batch.first().snode
                        @Suppress("UNCHECKED_CAST") val responses = try {
                            realService.call(snode, Batch(), Batch.Request(batch.map {
                                Batch.Request.Item(
                                    method = it.endpoint.methodName,
                                    params = (it.endpoint as Endpoint<Any?, Any?>).serializeRequest(json, it.request)                                )
                            }))
                        } catch (e: Exception) {
                            for (req in batch) {
                                runCatching {
                                    req.resultCallback.send(Result.failure(e))
                                }
                            }
                            return@batch
                        }

                        // For each response, parse the result, match it with the request then send
                        // back through the request's callback.
                        for ((req, resp) in batch.zip(responses.results)) {
                            val result = runCatching {
                                if (!resp.isSuccessful) {
                                    throw Batch.Response.Error(resp)
                                }

                                req.endpoint.deserializeResponse(json, resp.body)
                            }

                            runCatching {
                                @Suppress("UNCHECKED_CAST")
                                (req.resultCallback as SendChannel<Any?>).send(result)
                            }
                        }

                        // Close all channels in the requests just in case we don't have paired up
                        // responses.
                        for (req in batch) {
                            req.resultCallback.close()
                        }
                    }
                }
            }

        }
    }

    override suspend fun <Req, Res> call(
        snode: Snode,
        endpoint: Endpoint<Req, Res>,
        req: Req
    ): Res {
        if (endpoint is Batch) {
            return realService.call(snode, endpoint, req)
        }

        val batchKey = endpoint.batchKey(req)

        if (batchKey == null) {
            // No batch key provided, we won't know how to group the requests together,
            // so just call this request directly
            return realService.call(snode, endpoint, req)
        }

        val callback = Channel<Result<Res>>()

        batchChannel.send(
            BatchRequestInfo(
                snode = snode,
                endpoint = endpoint,
                request = req,
                resultCallback = callback,
                key = BatchKey(snode.address, batchKey)
            )
        )

        return callback.receive().getOrThrow()
    }

    @AssistedFactory
    interface Factory {
        fun create(realService: StorageRPCService): AutoBatchRPCService
    }

    companion object {
        const val BATCH_WINDOW_MILLS = 100L
    }
}