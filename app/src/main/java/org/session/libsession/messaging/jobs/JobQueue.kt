package org.session.libsession.messaging.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsignal.utilities.Log
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.schedule
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

class JobQueue : JobDelegate {
    private var hasResumedPendingJobs = false // Just for debugging
    private val jobTimestampMap = ConcurrentHashMap<Long, AtomicInteger>()

    private val scope: CoroutineScope = GlobalScope
    private val queue = Channel<Job>(UNLIMITED)

    // Track the send message jobs that are pending or in progress. This doesn't take the
    // first launch of the send message job into account
    private val pendingSendMessageJobIDs = hashSetOf<String>()

    private val openGroupChannels = mutableMapOf<String, Channel<Job>>()

    val timer = Timer()

    private fun CoroutineScope.processWithOpenGroupDispatcher(
        channel: Channel<Job>,
        name: String
    ) = launch {
        for (job in channel) {
            if (!isActive) break
            val openGroupId = when (job) {
                is BatchMessageReceiveJob -> job.openGroupID
                is OpenGroupDeleteJob -> job.openGroupId
                is TrimThreadJob -> job.openGroupId
                is BackgroundGroupAddJob -> job.openGroupId
                is GroupAvatarDownloadJob -> "${job.server}.${job.room}"
                else -> null
            }
            if (openGroupId.isNullOrEmpty()) {
                Log.e("OpenGroupDispatcher", "Open Group ID was null on ${job.javaClass.simpleName}")
                handleJobFailedPermanently(job, name, NullPointerException("Open Group ID was null"))
            } else {
                val groupChannel = if (!openGroupChannels.containsKey(openGroupId)) {
                    Log.d("OpenGroupDispatcher", "Creating ${openGroupId.hashCode()} channel")
                    val newGroupChannel = Channel<Job>(UNLIMITED)
                    launch {
                        for (groupJob in newGroupChannel) {
                            if (!isActive) break
                            groupJob.process(name)
                        }
                    }
                    openGroupChannels[openGroupId] = newGroupChannel
                    newGroupChannel
                } else {
                    Log.d("OpenGroupDispatcher", "Re-using channel")
                    openGroupChannels[openGroupId]!!
                }
                Log.d("OpenGroupDispatcher", "Sending to channel $groupChannel")
                groupChannel.send(job)
            }
        }
    }

    private fun CoroutineScope.processWithDispatcher(
        channel: Channel<Job>,
        name: String,
        asynchronous: Boolean = true
    ) = launch {
        for (job in channel) {
            if (!isActive) break
            if (asynchronous) {
                launch {
                    job.process(name)
                }
            } else {
                job.process(name)
            }
        }
    }

    private suspend fun Job.process(dispatcherName: String) {
        Log.d(dispatcherName,"processJob: ${javaClass.simpleName} (id: $id)")
        delegate = this@JobQueue

        val runResult = runCatching {
            execute(dispatcherName)
        }

        // Remove the job from the pending "send message job" list, regardless of whether
        // we are a send message job, as IDs are unique across all job types
        synchronized(pendingSendMessageJobIDs) {
            pendingSendMessageJobIDs.remove(id)
        }

        runResult.onFailure { e ->
            Log.d(dispatcherName, "unhandledJobException: ${javaClass.simpleName} (id: $id)", e)
            this@JobQueue.handleJobFailed(this, dispatcherName, e as? Exception ?: RuntimeException(e))
        }
    }

    init {
        // Process jobs
        scope.launch {
            val rxQueue = Channel<Job>(capacity = UNLIMITED)
            val txQueue = Channel<Job>(capacity = UNLIMITED)
            val mediaQueue = Channel<Job>(capacity = UNLIMITED)
            val openGroupQueue = Channel<Job>(capacity = UNLIMITED)

            val receiveJob = processWithDispatcher(rxQueue, "rx", asynchronous = false)
            val txJob = processWithDispatcher(txQueue, "tx")
            val mediaJob = processWithDispatcher(mediaQueue, "media")
            val openGroupJob = processWithOpenGroupDispatcher(openGroupQueue, "openGroup")

            while (isActive) {
                when (val job = queue.receive()) {
                    is InviteContactsJob,
                    is NotifyPNServerJob,
                    is AttachmentUploadJob,
                    is MessageSendJob -> {
                        txQueue.send(job)
                    }
                    is RetrieveProfileAvatarJob,
                    is AttachmentDownloadJob -> {
                        mediaQueue.send(job)
                    }
                    is GroupAvatarDownloadJob,
                    is BackgroundGroupAddJob,
                    is OpenGroupDeleteJob -> {
                        openGroupQueue.send(job)
                    }
                    is MessageReceiveJob, is TrimThreadJob,
                    is BatchMessageReceiveJob -> {
                        if ((job is BatchMessageReceiveJob && !job.openGroupID.isNullOrEmpty())
                            || (job is TrimThreadJob && !job.openGroupId.isNullOrEmpty())) {
                            openGroupQueue.send(job)
                        } else {
                            rxQueue.send(job)
                        }
                    }
                    else -> {
                        throw IllegalStateException("Unexpected job type: ${job.getFactoryKey()}")
                    }
                }
            }

            // The job has been cancelled
            receiveJob.cancel()
            txJob.cancel()
            mediaJob.cancel()
            openGroupJob.cancel()
        }
    }

    companion object {
        @JvmStatic
        val shared: JobQueue by lazy { JobQueue() }
    }

    fun add(job: Job) {
        addWithoutExecuting(job)
        queue.trySend(job) // offer always called on unlimited capacity
    }

    private fun addWithoutExecuting(job: Job) {
        // When adding multiple jobs in rapid succession, timestamps might not be good enough as a unique ID. To
        // deal with this we keep track of the number of jobs with a given timestamp and add that to the end of the
        // timestamp to make it a unique ID. We can't use a random number because we do still want to keep track
        // of the order in which the jobs were added.
        val currentTime = System.currentTimeMillis()
        jobTimestampMap.putIfAbsent(currentTime, AtomicInteger())
        job.id = currentTime.toString() + jobTimestampMap[currentTime]!!.getAndIncrement().toString()
        MessagingModuleConfiguration.shared.storage.persistJob(job)
    }

    fun resumePendingSendMessage(job: Job) {
        val id = job.id ?: run {
            Log.e("Loki", "tried to resume pending send job with no ID")
            return
        }

        // Check if the job is already in progress and mark it as in progress if it is not
        val jobIsInProgress = synchronized(pendingSendMessageJobIDs) {
            !pendingSendMessageJobIDs.add(id)
        }

        if (jobIsInProgress) {
            Log.e("Loki","tried to re-queue pending/in-progress job (id: $id)")
            return
        }
        queue.trySend(job)
        Log.d("Loki", "resumed pending send message $id")
    }

    fun resumePendingJobs(typeKey: String) {
        val allPendingJobs = MessagingModuleConfiguration.shared.storage.getAllPendingJobs(typeKey)
        val pendingJobs = mutableListOf<Job>()
        for ((id, job) in allPendingJobs) {
            if (job == null) {
                // Job failed to deserialize, remove it from the DB
                handleJobFailedPermanently(id)
            } else {
                pendingJobs.add(job)
            }
        }
        pendingJobs.sortedBy { it.id }.forEach { job ->
            Log.i("Loki", "Resuming pending job of type: ${job::class.simpleName} (id: ${job.id}).")
            queue.trySend(job) // Offer always called on unlimited capacity
        }
    }

    fun resumePendingJobs() {
        if (hasResumedPendingJobs) {
            Log.d("Loki", "resumePendingJobs() should only be called once.")
            return
        }
        hasResumedPendingJobs = true
        val allJobTypes = listOf(
            AttachmentUploadJob.KEY,
            AttachmentDownloadJob.KEY,
            MessageReceiveJob.KEY,
            MessageSendJob.KEY,
            NotifyPNServerJob.KEY,
            BatchMessageReceiveJob.KEY,
            GroupAvatarDownloadJob.KEY,
            BackgroundGroupAddJob.KEY,
            OpenGroupDeleteJob.KEY,
            RetrieveProfileAvatarJob.KEY,
            InviteContactsJob.KEY,
        )
        allJobTypes.forEach { type ->
            resumePendingJobs(type)
        }
    }

    override fun handleJobSucceeded(job: Job, dispatcherName: String) {
        val jobId = job.id ?: return
        MessagingModuleConfiguration.shared.storage.markJobAsSucceeded(jobId)
    }

    override fun handleJobFailed(job: Job, dispatcherName: String, error: Exception) {
        // Canceled
        val storage = MessagingModuleConfiguration.shared.storage
        if (storage.isJobCanceled(job)) {
            return Log.i("Loki", "${job::class.simpleName} canceled (id: ${job.id}).")
        }
        // Message send jobs waiting for the attachment to upload
        if (job is MessageSendJob && error is MessageSendJob.AwaitingAttachmentUploadException) {
            Log.i("Loki", "Message send job waiting for attachment upload to finish (id: ${job.id}).")
            return
        }

        // Batch message receive job, re-queue non-permanently failed jobs
        if (job is BatchMessageReceiveJob && job.failureCount <= 0) {
            val replacementParameters = job.failures.toList()
            if (replacementParameters.isNotEmpty()) {
                val newJob = BatchMessageReceiveJob(replacementParameters, job.openGroupID)
                newJob.failureCount = job.failureCount + 1
                add(newJob)
            }
        }

        // Regular job failure
        job.failureCount += 1

        if (job.failureCount >= job.maxFailureCount) {
            handleJobFailedPermanently(job, dispatcherName, error)
        } else {
            storage.persistJob(job)
            val retryInterval = getRetryInterval(job)
            Log.i("Loki", "${job::class.simpleName} failed (id: ${job.id}); scheduling retry (failure count is ${job.failureCount}).")
            timer.schedule(delay = retryInterval) {
                Log.i("Loki", "Retrying ${job::class.simpleName} (id: ${job.id}).")
                queue.trySend(job)
            }
        }
    }

    override fun handleJobFailedPermanently(job: Job, dispatcherName: String, error: Exception) {
        val jobId = job.id ?: return
        handleJobFailedPermanently(jobId)
        Log.d(dispatcherName, "permanentlyFailedJob: ${javaClass.simpleName} (id: ${job.id})")
    }

    private fun handleJobFailedPermanently(jobId: String) {
        val storage = MessagingModuleConfiguration.shared.storage
        storage.markJobAsFailedPermanently(jobId)
    }

    private fun getRetryInterval(job: Job): Long {
        // Arbitrary backoff factor...
        // try  1 delay: 0.5s
        // try  2 delay: 1s
        // ...
        // try  5 delay: 16s
        // ...
        // try 11 delay: 512s
        val maxBackoff = (10 * 60).toDouble() // 10 minutes
        return (1000 * 0.25 * min(maxBackoff, (2.0).pow(job.failureCount))).roundToLong()
    }

    private fun Job.isSend() = this is MessageSendJob || this is AttachmentUploadJob

}