package org.session.libsession.messaging.groups

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GroupScope"

/**
 * A coroutine utility that limit the tasks into a group to be executed sequentially.
 *
 * This is useful for tasks that are related to group management, where the order of execution is important.
 * It's probably harmful if you apply the scope on message retrieval, as normally the message retriveal
 * doesn't have any order requirement and it will likly slow down usual group operations.
 */
class GroupScope(private val scope: CoroutineScope = GlobalScope) {
    private val tasksByGroupId = hashMapOf<AccountId, ArrayDeque<Task<*>>>()

    /**
     * Launch a coroutine in a group context. The coroutine will be executed sequentially
     * in the order they are launched, and the next coroutine will not be started until the previous one is completed.
     * Each group has their own queue of tasks so they won't block each other.
     *
     * @groupId The group id that the coroutine belongs to.
     * @debugName A debug name for the coroutine.
     * @block The coroutine block.
     */
    fun launch(groupId: AccountId, debugName: String, block: suspend () -> Unit) : Job {
        return async(groupId, debugName) { block() }
    }

    /**
     * Launch a coroutine in the given group scope and wait for it to complete.
     *
     * See [launch] for more details.
     */
    suspend fun <T> launchAndWait(groupId: AccountId, debugName: String, block: suspend () -> T): T {
        return async(groupId, debugName, block).await()
    }

    /**
     * Launch a coroutine in the given group scope and return a deferred result.
     *
     * See [launch] for more details.
     */
    fun <T> async(groupId: AccountId, debugName: String, block: suspend () -> T) : Deferred<T> {
        val completion = CompletableDeferred<T>()

        synchronized(tasksByGroupId) {
            val tasks = tasksByGroupId.getOrPut(groupId) { ArrayDeque() }

            val task = Task(groupId, debugName, block, completion)
            tasks.addLast(task)

            Log.d(TAG, "Added $task to queue, queue size: ${tasks.size}")

            // If this is the first task in the queue, start it directly (otherwise the next will be started by the previous task)
            if (tasks.size == 1) {
                scope.launch {
                    task.run()
                }
            }
        }

        return completion
    }

    private val taskIdSeq = AtomicLong(0)

    private inner class Task<T>(val groupId: AccountId, val debugName: String, val block: suspend () -> T, val completion: CompletableDeferred<T>) {
        private val id = taskIdSeq.getAndIncrement()

        suspend fun run() {
            Log.d(TAG, "Task started: $this")
            try {
                completion.complete(block())
            } catch (e: Throwable) {
                completion.completeExceptionally(e)
            } finally {
                Log.d(TAG, "Task completed: $this")
                // Remove self from the queue and start the next task
                synchronized(tasksByGroupId) {
                    val tasks = tasksByGroupId[groupId]
                    require(tasks != null && tasks.firstOrNull() == this) { "Task is not the first in the queue: $this" }
                    tasks.removeFirst()

                    if (tasks.isEmpty()) {
                        tasksByGroupId.remove(groupId)
                    } else {
                        val nextTask = tasks.first()
                        scope.launch {
                            nextTask.run()
                        }
                    }
                }
            }
        }

        override fun toString(): String {
            return "Task($debugName, id=$id)"
        }
    }
}