package org.thoughtcrime.securesms.glide

import android.app.Application
import androidx.work.await
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
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
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("OPT_IN_USAGE")
@OptIn(FlowPreview::class)
@Singleton
class RecipientAvatarDownloadManager @Inject constructor(
    private val application: Application,
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    private val groupDatabase: GroupDatabase,
) {
    init {
        GlobalScope.launch {
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
                        when (file) {
                            is AvatarFile.FileServer -> {
                                EncryptedFileDownloadWorker.enqueue(
                                    context = application,
                                    fileId = file.fileId,
                                    cacheFolderName = CACHE_FOLDER_NAME,
                                )
                            }
                            is AvatarFile.Community -> {
                                CommunityFileDownloadWorker.enqueue(
                                    context = application,
                                    file = file.avatar,
                                )
                            }
                        }
                    }

                    val toRemove = acc.downloadedAvatar - newSet
                    for (file in toRemove) {
                        Log.d(TAG, "Cancelling downloading of $file")
                        when (file) {
                            is AvatarFile.FileServer -> {
                                EncryptedFileDownloadWorker.cancel(
                                    context = application,
                                    fileId = file.fileId,
                                    cacheFolderName = CACHE_FOLDER_NAME,
                                ).await()
                            }

                            is AvatarFile.Community -> {
                                CommunityFileDownloadWorker.cancel(
                                    context = application,
                                    avatar = file.avatar,
                                )
                            }
                        }
                    }

                    acc.copy(downloadedAvatar = newSet)
                }
                .collect()
            // Look at all the avatar URLs stored in the config and download them if necessary
        }
    }

    fun getAllAvatars(): Set<AvatarFile> {
        val (contacts, groups) = configFactory.withUserConfigs { configs ->
            configs.contacts.all() to configs.userGroups.all()
        }

        val contactAvatars = contacts.asSequence()
            .map { it.profilePicture.url }

        val groupsAvatars = groups.asSequence()
            .filterIsInstance<GroupInfo.ClosedGroupInfo>()
            .flatMap { it.getGroupAvatarUrls() }

        val out = mutableSetOf<AvatarFile>()

        // Note that for contacts + groups avatars, contacts ones take precedence over groups,
        // so their order in the set is important.
        (groupsAvatars + contactAvatars)
            .mapNotNull { url -> FileServerApi.getFileIdFromUrl(url) }
            .mapTo(out) { AvatarFile.FileServer(it) }


        groups.asSequence()
            .filterIsInstance<GroupInfo.CommunityGroupInfo>()
            .flatMap { it.getCommunityAvatarFile() }
            .mapTo(out) { it }

        return out
    }

    private fun GroupInfo.ClosedGroupInfo.getGroupAvatarUrls(): List<String> {
        if (destroyed) {
            return emptyList()
        }

        return configFactory.withGroupConfigs(AccountId(groupAccountId)) {
            buildList {
                add(it.groupInfo.getProfilePic().url)
                it.groupMembers.all().forEach { m ->
                    m.profilePic()?.url?.let(::add)
                }
            }
        }
    }

    private fun GroupInfo.CommunityGroupInfo.getCommunityAvatarFile(): Sequence<AvatarFile> {
        // Don't download avatars from community servers yet, future improvement
        return emptySequence()
    }

    sealed interface AvatarFile {
        data class FileServer(val fileId: String) : AvatarFile
        data class Community(val avatar: RemoteFile.Community) : AvatarFile
    }

    private data class State(
        val downloadedAvatar: Set<AvatarFile>
    )

    companion object {
        const val CACHE_FOLDER_NAME = "recipient_avatars"

        private const val TAG = "RecipientAvatarDownloadManager"
    }
}