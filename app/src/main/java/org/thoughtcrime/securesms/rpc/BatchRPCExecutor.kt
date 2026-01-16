package org.thoughtcrime.securesms.rpc

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
class BatchRPCExecutor<Dest: BatchRPCExecutor.Batchable, Req, Res>(
    private val realExecutor: RPCExecutor<Dest, Req, Res>,
    private val batcher: Batcher<Req, Res>,
    private val scope: CoroutineScope,
) : RPCExecutor<Dest, Req, Res> {
    private val batchCommandSender: SendChannel<BatchCommand<Dest, Req, Res>>

    init {
        val channel = Channel<BatchCommand<Dest, Req, Res>>(capacity = 100)
        batchCommandSender = channel

        scope.launch {
            val pendingRequests = linkedMapOf<Any, BatchInfo<Dest, Req, Res>>()
            var nextDeadline: Instant? = null

            while (true) {
                val command: BatchCommand<Dest, Req, Res>? = if (nextDeadline == null) {
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
                    is BatchCommand.Send<Dest, Req, Res> -> {
                        val batchKey = command.dest.batchKey!!
                        val existingBatch = pendingRequests[batchKey]
                        if (existingBatch == null) {
                            pendingRequests[batchKey] = BatchInfo(
                                requests = arrayListOf(command),
                                deadline = Instant.now().plusMillis(100L),
                            )

                            calculateNextDeadline = true
                        } else {
                            existingBatch.requests.add(command)
                        }
                    }

                    is BatchCommand.Cancel<Dest, Req, Res> -> {
                        val existingBatch = pendingRequests[command.dest.batchKey!!]
                        if (existingBatch != null) {
                            existingBatch.requests.removeIf { it.req == command.req }
                            if (existingBatch.requests.isEmpty()) {
                                pendingRequests.remove(command.dest.batchKey!!)
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

    private class BatchInfo<Dest: Batchable, Req, Res>(
        val requests: ArrayList<BatchCommand.Send<Dest, Req, Res>>,
        val deadline: Instant,
    ) {
        init {
            check(requests.isNotEmpty()) {
                "BatchInfo must be initialized with at least one request"
            }
        }
    }

    private fun executeBatch(batch: BatchInfo<Dest, Req, Res>) {
        scope.launch {
            val requests = batch.requests.map { it.req }
            val dest = batch.requests.first().dest

            Log.d(TAG, "Sending ${requests.size} batched requests to $dest")

            try {
                val resp = realExecutor.send(
                    dest = dest,
                    req = batcher.constructBatchRequest(requests)
                )

                val responses = batcher.deconstructBatchResponse(requests, resp)
                check(responses.size == batch.requests.size) {
                    "Batch response size ${responses.size} does not match request size ${batch.requests.size}"
                }

                for (i in batch.requests.indices) {
                    val request = batch.requests[i]
                    val response = responses[i]
                    request.callback.send(Result.success(response))
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

    override suspend fun send(dest: Dest, req: Req): Res {
        if (dest.batchKey == null) {
            // No batching key == no batching possible
            return realExecutor.send(dest, req)
        }

        val callback = Channel<Result<*>>(1)
        batchCommandSender.send(BatchCommand.Send(dest, req, callback))
        try {
            @Suppress("UNCHECKED_CAST")
            return callback.receive().getOrThrow() as Res
        } catch (e: CancellationException) {
            // Best effort cancellation
            batchCommandSender.trySend(BatchCommand.Cancel(dest, req))

            throw e
        }
    }

    private interface BatchCommand<Dest, Req, Res> {
        class Send<Dest: Batchable, Req, Res>(val dest: Dest, val req: Req, val callback: SendChannel<Result<Res>>) : BatchCommand<Dest, Req, Res>
        class Cancel<Dest, Req, Res>(val dest: Dest, val req: Req) : BatchCommand<Dest, Req, Res>
    }

    interface Batchable {
        val batchKey: Any?
    }

    interface Batcher<Req, Res> {
        fun constructBatchRequest(requests: List<Req>): Req
        fun deconstructBatchResponse(requests: List<Req>, response: Res): List<Res>
    }

    companion object {
        private const val TAG = "BatchRPCExecutor"
    }
}