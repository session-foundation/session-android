package org.thoughtcrime.securesms.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.session.libsignal.utilities.Log
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BatchApiExecutor<Req, Res>(
    private val actualExecutor: ApiExecutor<Req, Res>,
    private val batcher: Batcher<Req, Res>,
    private val scope: CoroutineScope,
) : ApiExecutor<Req, Res> {
    private val batchCommandSender: SendChannel<BatchCommand<Req, Res>>

    init {
        val channel = Channel<BatchCommand<Req, Res>>(capacity = 100)
        batchCommandSender = channel

        scope.launch {
            val pendingRequests = linkedMapOf<Any, BatchInfo<Req, Res>>()
            var nextDeadline: Instant? = null

            while (true) {
                val command: BatchCommand<Req, Res>? = if (nextDeadline == null) {
                    channel.receive()
                } else {
                    val now = Instant.now()
                    if (nextDeadline > now) {
                        withTimeoutOrNull(nextDeadline.toEpochMilli() - now.toEpochMilli()) {
                            channel.receive()
                        }
                    } else {
                        // Deadline already reached
                        null
                    }
                }

                var calculateNextDeadline = false

                when (command) {
                    is BatchCommand.Send<Req, Res> -> {
                        val existingBatch = pendingRequests[command.batchKey]
                        if (existingBatch == null) {
                            pendingRequests[command.batchKey] = BatchInfo(
                                requests = arrayListOf(command),
                                deadline = Instant.now().plusMillis(100L),
                            )

                            calculateNextDeadline = true
                        } else {
                            existingBatch.requests.add(command)
                        }
                    }

                    is BatchCommand.Cancel<Req, Res> -> {
                        val existingBatch = pendingRequests[command.batchKey]
                        if (existingBatch != null) {
                            existingBatch.requests.removeIf { it.req == command.req }
                            if (existingBatch.requests.isEmpty()) {
                                pendingRequests.remove(command.batchKey)
                                calculateNextDeadline = true
                            }
                        }
                    }

                    null -> {
                        // Deadline reached: it will be the first batch in the queue
                        executeBatch(pendingRequests.removeFirst())
                        calculateNextDeadline = true
                    }
                }

                if (calculateNextDeadline) {
                    nextDeadline = if (pendingRequests.isEmpty()) {
                        null
                    } else {
                        pendingRequests.values.first().deadline
                    }
                }
            }
        }
    }

    private class BatchInfo<Req, Res>(
        val requests: ArrayList<BatchCommand.Send<Req, Res>>,
        val deadline: Instant,
    ) {
        init {
            check(requests.isNotEmpty()) {
                "BatchInfo must be initialized with at least one request"
            }
        }
    }

    private fun executeBatch(batch: BatchInfo<Req, Res>) {
        scope.launch {
            val requests = batch.requests.map { it.ctx to it.req }

            Log.d(TAG, "Sending ${requests.size} batched requests")

            try {
                val resp = actualExecutor.send(
                    ctx = ApiExecutorContext(), // Blank context for a batched request (each sub-request has its own context)
                    req = batcher.constructBatchRequest(requests)
                )

                val responses = batcher.deconstructBatchResponse(
                    requests = requests,
                    response = resp
                )

                check(responses.size == batch.requests.size) {
                    "Batch response size ${responses.size} does not match request size ${batch.requests.size}"
                }

                for (i in batch.requests.indices) {
                    val request = batch.requests[i]
                    val response = responses[i]
                    request.callback.send(response)
                }

            } catch (e: Throwable) {
                if (e is CancellationException) throw e

                // Notify all requests of the failure
                for (request in batch.requests) {
                    request.callback.send(Result.failure(e))
                }
            }
        }
    }

    private fun <K, V> LinkedHashMap<K, V>.removeFirst(): V {
        val iterator = this.entries.iterator()
        val first = iterator.next()
        iterator.remove()
        return first.value
    }

    override suspend fun send(ctx: ApiExecutorContext, req: Req): Res {
        val batchKey = batcher.batchKey(req)
            ?: return actualExecutor.send(ctx, req)

        val callback = Channel<Result<*>>(1)
        batchCommandSender.send(BatchCommand.Send(
            ctx = ctx,
            batchKey = batchKey,
            req = req,
            callback = callback
        ))

        try {
            @Suppress("UNCHECKED_CAST")
            return callback.receive().getOrThrow() as Res
        } catch (e: CancellationException) {
            // Best effort cancellation
            batchCommandSender.trySend(BatchCommand.Cancel(batchKey, req))

            throw e
        }
    }

    private interface BatchCommand<Req, Res> {
        class Send<Req, Res>(
            val ctx: ApiExecutorContext,
            val batchKey: Any,
            val req: Req,
            val callback: SendChannel<Result<Res>>,
        ) : BatchCommand<Req, Res>
        class Cancel<Req, Res>(val batchKey: Any, val req: Req) : BatchCommand<Req, Res>
    }

    interface Batcher<Req, Res> {
        fun batchKey(req: Req): Any?
        fun constructBatchRequest(requests: List<Pair<ApiExecutorContext, Req>>): Req
        suspend fun deconstructBatchResponse(
            requests: List<Pair<ApiExecutorContext, Req>>,
            response: Res
        ): List<Result<Res>>
    }

    companion object {
        private const val TAG = "BatchRPCExecutor"
    }
}