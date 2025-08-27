package org.thoughtcrime.securesms.glide

import android.app.Application
import androidx.work.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.util.GroupInfo
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.attachments.RemoteFileDownloadWorker
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("OPT_IN_USAGE")
@OptIn(FlowPreview::class)
@Singleton
class RecipientAvatarDownloadManager @Inject constructor(
    private val application: Application,
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    @ManagerScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            prefs.watchLocalNumber()
                .map { it != null }
                .flatMapLatest { isLoggedIn ->
                    if (isLoggedIn) {
                        (configFactory.configUpdateNotifications as Flow<*>)
                            .debounce(500)
                            .onStart { emit(Unit) }
                            .map { getAllAvatars() }
                    } else {
                        flowOf(emptySet())
                    }
                }
                .scan(State(emptySet())) { acc, newSet ->
                    val toDownload = newSet - acc.downloadedAvatar
                    for (file in toDownload) {
                        Log.d(TAG, "Downloading $file")
                        RemoteFileDownloadWorker.enqueue(application, file)
                    }

                    val toRemove = acc.downloadedAvatar - newSet
                    for (file in toRemove) {
                        Log.d(TAG, "Cancelling downloading of $file")
                        RemoteFileDownloadWorker.cancel(application, file)
                    }

                    acc.copy(downloadedAvatar = newSet)
                }
                .collect()
            // Look at all the avatar URLs stored in the config and download them if necessary
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
        val downloadedAvatar: Set<RemoteFile>
    )

    companion object {

        private const val TAG = "RecipientAvatarDownloadManager"
    }
}