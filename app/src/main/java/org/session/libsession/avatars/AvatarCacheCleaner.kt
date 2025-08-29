package org.session.libsession.avatars

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.attachments.RemoteFileDownloadWorker
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.glide.RecipientAvatarDownloadManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarCacheCleaner @Inject constructor(
    private val application: Application,
    private val recipientAvatarDownloadManager: RecipientAvatarDownloadManager,
    private val recipientSettingsDatabase: RecipientSettingsDatabase,
    @param:ManagerScope private val coroutineScope: CoroutineScope
) {

    companion object {
        const val TAG = "AvatarCacheCleaner"
    }

    /**
     * Deletes avatar files under cache/remote_files that are no longer referenced
     * in the current config. Returns number of files deleted.
     */
    private suspend fun cleanUpAvatars(): Int = withContext(Dispatchers.IO) {
        // 1) Build the set of still-wanted Avatars from:
        // -> Config
        // -> Recipient Settings DB

        // config
        val avatarsFromConfig: Set<RemoteFile> = recipientAvatarDownloadManager.getAllAvatars()
        // recipient_settings
        val recipientAvatars : Set<RemoteFile> = recipientSettingsDatabase.getAllReferencedAvatarFiles()

        // 3) Union of everything we want to keep
        val filesToKeep: Set<RemoteFile> =
            (avatarsFromConfig + recipientAvatars).toSet()

        // 4) Map to actual files (same hashing/location as downloader)
        val wantedFiles: Set<File> = filesToKeep
            .map { RemoteFileDownloadWorker.computeFileName(application, it) }
            .toSet()

        // 5) Delete everything not wanted in cache/remote_files
        val files = RemoteFileDownloadWorker.listDownloadedFiles(application)
        var deleted = 0
        for (file in files) {
            if (file !in wantedFiles && file.delete()) deleted++
        }

        deleted
    }

    fun launchAvatarCleanup() {
        coroutineScope.launch(Dispatchers.IO) {
            val deleted = cleanUpAvatars()
            Log.d(TAG, "Avatar cache removed: $deleted files")
        }
    }
}