package org.session.libsession.messaging.groups

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GroupScope"

/**
 * A coroutine utility that limit the tasks into a group to be executed sequentially.
 *
 * This is useful for tasks that are related to group management, where the order of execution is important.
 * It's probably harmful if you apply the scope on message retrieval, as normally the message retriveal
 * doesn't have any order requirement and it will likly slow down usual group operations.
 */
@Singleton
class GroupScope @Inject constructor(
    @param:ManagerScope private val scope: CoroutineScope
) {
    private val groupSemaphores = hashMapOf<AccountId, Semaphore>()

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
        return scope.async {
            val semaphore = synchronized(groupSemaphores) {
                groupSemaphores.getOrPut(groupId) { Semaphore(1) }
            }

            semaphore.withPermit {
                Log.d(TAG, "Starting group-scoped task '$debugName' for group $groupId")
                block()
            }
        }
    }
}