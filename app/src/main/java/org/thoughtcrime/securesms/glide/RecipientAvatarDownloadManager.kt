package org.thoughtcrime.securesms.glide

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import network.loki.messenger.libsession_util.util.GroupInfo
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.attachments.AvatarDownloadManager
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("OPT_IN_USAGE")
@OptIn(FlowPreview::class)
@Singleton
class RecipientAvatarDownloadManager @Inject constructor(
    private val configFactory: ConfigFactory,
    @ManagerScope scope: CoroutineScope,
    private val avatarDownloadManager: AvatarDownloadManager,
    private val loginStateRepository: LoginStateRepository,
) {
    private val avatarBulkDownloadSemaphore = Semaphore(5)

    init {
        scope.launch {
            loginStateRepository.flowWithLoggedInState {
                (configFactory.configUpdateNotifications as Flow<*>)
                    .debounce(500)
                    .onStart { emit(Unit) }
                    .map { getAllAvatars() }
            }
                .scan(State()) { acc, newSet ->
                    val toDownload = newSet - acc.downloadedAvatar
                    val coroutineJobs = acc.downloadingJob.toMutableMap()
                    for (file in toDownload) {
                        Log.d(TAG, "Downloading $file")
                        coroutineJobs[file] = scope.launch {
                            enqueueDownload(file)
                        }
                    }

                    val toRemove = acc.downloadedAvatar - newSet
                    for (file in toRemove) {
                        Log.d(TAG, "Cancelling downloading of $file")
                        coroutineJobs.remove(file)?.cancel()
                    }

                    acc.copy(downloadedAvatar = newSet, downloadingJob = coroutineJobs)
                }
                .collect()
            // Look at all the avatar URLs stored in the config and download them if necessary
        }
    }

    private suspend fun enqueueDownload(file: RemoteFile) {
        try {
            avatarBulkDownloadSemaphore.withPermit {
                avatarDownloadManager.download(file)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.w(TAG, "Error downloading avatar $file", e)
            }
        }
    }

    fun getAllAvatars(): Set<RemoteFile> {
        val (contacts, groups) = configFactory.withUserConfigs { configs ->
            configs.contacts.all() to configs.userGroups.all()
        }

        val contactAvatars = contacts.asSequence()
            .mapNotNull { it.profilePicture.toRemoteFile() }

        val groupsAvatars = groups.asSequence()
            .filterIsInstance<GroupInfo.ClosedGroupInfo>()
            .flatMap { it.getGroupAvatarUrls() }

        return buildSet {
            // Note that for contacts + groups avatars, contacts ones take precedence over groups,
            // so their order in the set is important.
            addAll(groupsAvatars)
            addAll(contactAvatars)

            addAll(groups.asSequence()
                .filterIsInstance<GroupInfo.CommunityGroupInfo>()
                .flatMap { it.getCommunityAvatarFile() })
        }
    }

    private fun GroupInfo.ClosedGroupInfo.getGroupAvatarUrls(): List<RemoteFile.Encrypted> {
        if (destroyed) {
            return emptyList()
        }

        return configFactory.withGroupConfigs(AccountId(groupAccountId)) { configs ->
            buildList {
                configs.groupInfo.getProfilePic().toRemoteFile()?.let(::add)

                configs.groupMembers.all()
                    .asSequence()
                    .mapNotNullTo(this) { it.profilePic()?.toRemoteFile() }
            }
        }
    }

    private fun GroupInfo.CommunityGroupInfo.getCommunityAvatarFile(): Sequence<RemoteFile> {
        // Don't download avatars from community servers yet, future improvement
        return emptySequence()
    }

    private data class State(
        val downloadedAvatar: Set<RemoteFile> = emptySet(),
        val downloadingJob: Map<RemoteFile, Job> = emptyMap()
    )

    companion object {

        private const val TAG = "RecipientAvatarDownloadManager"
    }
}