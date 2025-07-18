package org.thoughtcrime.securesms.preferences

import android.app.Dialog
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsignal.utilities.ExternalStorageUtil
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.createSessionDialog
import org.thoughtcrime.securesms.logging.PersistentLogger
import org.thoughtcrime.securesms.util.FileProviderUtil
import javax.inject.Inject

@AndroidEntryPoint
class ShareLogsDialog(private val updateCallback: (Boolean)->Unit): DialogFragment() {

    private val TAG = "ShareLogsDialog"
    private var shareJob: Job? = null

    @Inject
    lateinit var persistentLogger: PersistentLogger

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = createSessionDialog {
        title(R.string.helpReportABugExportLogs)
        val appName = context.getString(R.string.app_name)
        val txt = Phrase.from(context, R.string.helpReportABugDescription)
            .put(APP_NAME_KEY, appName)
            .format().toString()
        text(txt)
        button(R.string.share, dismiss = false) { runShareLogsJob() }
        cancelButton { updateCallback(false) }
    }

    // If the share logs dialog loses focus the job gets cancelled so we'll update the UI state
    override fun onPause() {
        super.onPause()
        updateCallback(false)
    }

    private fun runShareLogsJob() {
        // Cancel any existing share job that might already be running to start anew
        shareJob?.cancel()

        updateCallback(true)

        shareJob = lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting share logs job...")
                val mediaUri = withContext(Dispatchers.IO) {
                    withExternalFile(persistentLogger::readAllLogsCompressed)
                } ?: return@launch

                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, mediaUri)
                    type = "application/zip"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e("Loki", "Error saving logs", e)
                    Toast.makeText(context, getString(R.string.errorUnknown), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }.also { shareJob ->
            shareJob.invokeOnCompletion { handler ->
                // Note: Don't show Toasts here directly - use `withContext(Main)` or such if req'd
                handler?.message.let { msg ->
                    if (shareJob.isCancelled) {
                        if (msg.isNullOrBlank()) {
                            Log.w(TAG, "Share logs job was cancelled.")
                        } else {
                            Log.d(TAG, "Share logs job was cancelled. Reason: $msg")
                        }

                    }
                    else if (shareJob.isCompleted) {
                        Log.d(TAG, "Share logs job completed. Msg: $msg")
                    }
                    else {
                        Log.w(TAG, "Share logs job finished while still Active. Msg: $msg")
                    }
                }

                // Regardless of the job's success it has now completed so update the UI
                updateCallback(false)
                
                dismiss()
            }
        }
    }

    private fun withExternalFile(action: (Uri) -> Unit): Uri? {
        val context = requireContext()
        val base = "${Build.MANUFACTURER}-${Build.DEVICE}-API${Build.VERSION.SDK_INT}-v${BuildConfig.VERSION_NAME}-${System.currentTimeMillis()}"
        val extension = "zip"
        val fileName = "$base.$extension"
        val outputUri: Uri = ExternalStorageUtil.getDownloadUri()
        if (outputUri.scheme == ContentResolver.SCHEME_FILE) {
            val outputDirectory = File(outputUri.path)
            var outputFile = File(outputDirectory, "$base.$extension")
            var i = 0
            while (outputFile.exists()) {
                outputFile = File(outputDirectory, base + "-" + ++i + "." + extension)
            }
            if (outputFile.isHidden) {
                throw IOException("Specified name would not be visible")
            }
            try {
                return FileProviderUtil.getUriFor(requireContext(), outputFile).also(action)
            } catch (e: Exception) {
                outputFile.delete()
                throw e
            }
        } else {
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            contentValues.put(MediaStore.MediaColumns.DATE_ADDED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            contentValues.put(MediaStore.MediaColumns.DATE_MODIFIED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri = context.contentResolver.insert(outputUri, contentValues) ?: return null
            try {
                action(uri)

                // Remove the pending flag to make the file available
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }

            return uri
        }
    }

}