package org.thoughtcrime.securesms.logging

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.libsession_util.encrypt.DecryptionStream
import network.loki.messenger.libsession_util.encrypt.EncryptionStream
import org.session.libsignal.utilities.Log.Logger
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds


/**
 * A [Logger] that writes logs to encrypted files in the app's cache directory.
 */
@Singleton
class PersistentLogger @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @ManagerScope scope: CoroutineScope,
    logSecretProvider: LogSecretProvider,
) : Logger(), OnAppStartupComponent {
    private val freeLogEntryPool = LogEntryPool()
    private val logEntryChannel: SendChannel<LogEntry>
    private val logChannelIdleSignal = MutableSharedFlow<Unit>()

    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS zzz", Locale.ENGLISH)

    private val secret by lazy {
        logSecretProvider.getOrCreateAttachmentSecret()
    }

    private val logFolder by lazy {
        File(context.cacheDir, "logs").apply {
            mkdirs()
        }
    }

    init {
        val channel = Channel<LogEntry>(capacity = MAX_PENDING_LOG_ENTRIES)
        logEntryChannel = channel

        scope.launch {
            val bulk = ArrayList<LogEntry>()
            var writer: OutputStreamWriter? = null
            var currentFile: File? = null
            val entryBuilder = StringBuilder()

            while (true) {
                try {
                    channel.receiveBulkLogs(bulk)

                    if (bulk.isNotEmpty()) {
                        if (writer == null) {
                            // Can't append to a stream cipher, so rotate existing v2 file
                            val existingV2 = File(logFolder, CURRENT_LOG_FILE_NAME_V2)
                            if (existingV2.exists() && existingV2.length() > 0) {
                                rotateAndTrimLogFiles(existingV2)
                            }

                            // One-time migration: rotate old v1 current log if present
                            val existingV1 = File(logFolder, CURRENT_LOG_FILE_NAME)
                            if (existingV1.exists() && existingV1.length() > 0) {
                                rotateAndTrimLogFiles(existingV1)
                            }

                            currentFile = File(logFolder, CURRENT_LOG_FILE_NAME_V2)
                            writer = OutputStreamWriter(
                                BufferedOutputStream(
                                    EncryptionStream(FileOutputStream(currentFile), secret)
                                ),
                                Charsets.UTF_8
                            )
                        }

                        bulkWrite(entryBuilder, writer, bulk)

                        // Release entries back to the pool
                        freeLogEntryPool.release(bulk)
                        bulk.clear()

                        // Rotate the log file if necessary
                        if (currentFile!!.length() > MAX_SINGLE_LOG_FILE_SIZE) {
                            writer.close()
                            rotateAndTrimLogFiles(currentFile)
                            writer = null
                            currentFile = null
                        }
                    }
                } catch (e: Throwable) {
                    writer?.close()
                    writer = null
                    currentFile = null

                    // Release entries back to the pool on error
                    freeLogEntryPool.release(bulk)
                    bulk.clear()

                    android.util.Log.e(
                        TAG,
                        "Error while processing log entries: ${e.message}",
                        e
                    )
                }

                // Notify that the log channel is idle
                logChannelIdleSignal.tryEmit(Unit)
            }
        }
    }

    fun deleteAllLogs() {
        logFolder.deleteRecursively()
    }

    private fun rotateAndTrimLogFiles(currentFile: File) {
        val suffix = if (currentFile.isV2LogFile()) PERM_LOG_FILE_SUFFIX_V2 else PERM_LOG_FILE_SUFFIX
        val permLogFile = File(logFolder, "${System.currentTimeMillis()}$suffix")
        if (currentFile.renameTo(permLogFile)) {
            android.util.Log.d(TAG, "Rotated log file: $currentFile to $permLogFile")
        } else {
            android.util.Log.e(TAG, "Failed to rotate log file: $currentFile")
        }

        val logFilesNewToOld = getLogFilesSorted(includeActiveLogFile = false)

        // Keep the last N log files, delete the rest
        while (logFilesNewToOld.size > MAX_LOG_FILE_COUNT) {
            val last = logFilesNewToOld.removeLastOrNull()!!
            if (last.delete()) {
                android.util.Log.d(TAG, "Deleted old log file: $last")
            } else {
                android.util.Log.e(TAG, "Failed to delete log file: $last")
            }
        }
    }

    private fun bulkWrite(sb: StringBuilder, writer: OutputStreamWriter, bulk: List<LogEntry>) {
        for (entry in bulk) {
            sb.clear()
            sb.append(logDateFormat.format(entry.timestampMills))
                .append(' ')
                .append(entry.logLevel)
                .append(' ')
                .append(entry.tag.orEmpty())
                .append(": ")
                .append(entry.message.orEmpty())
                .append('\n')
            entry.err?.let {
                sb.append('\n')
                sb.append(it.stackTraceToString())
            }
            writer.append(sb)
        }

        writer.flush()
    }

    private suspend fun ReceiveChannel<LogEntry>.receiveBulkLogs(out: MutableList<LogEntry>) {
        out += receive()

        while (out.size < MAX_BULK_DRAIN_SIZE) {
            out += tryReceive().getOrNull() ?: break
        }
    }

    private fun sendLogEntry(
        level: String,
        tag: String?,
        message: String?,
        t: Throwable? = null
    ) {
        val entry = freeLogEntryPool.createLogEntry(level, tag, message, t)
        if (logEntryChannel.trySend(entry).isFailure) {
            android.util.Log.e(TAG, "Failed to send log entry, buffer is full")
        }
    }

    override fun v(tag: String?, message: String?, t: Throwable?) =
        sendLogEntry(LOG_V, tag, message, t)

    override fun d(tag: String?, message: String?, t: Throwable?) =
        sendLogEntry(LOG_D, tag, message, t)

    override fun i(tag: String?, message: String?, t: Throwable?) =
        sendLogEntry(LOG_I, tag, message, t)

    override fun w(tag: String?, message: String?, t: Throwable?) =
        sendLogEntry(LOG_W, tag, message, t)

    override fun e(tag: String?, message: String?, t: Throwable?) =
        sendLogEntry(LOG_E, tag, message, t)

    override fun wtf(tag: String?, message: String?, t: Throwable?) =
        sendLogEntry(LOG_WTF, tag, message, t)

    override fun blockUntilAllWritesFinished() {
        runBlocking {
            withTimeoutOrNull(1000) {
                logChannelIdleSignal.first()
            }
        }
    }

    private fun getLogFilesSorted(includeActiveLogFile: Boolean): MutableList<File> {
        val files = (logFolder.listFiles()?.asSequence() ?: emptySequence())
            .mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null

                val v2Match = PERM_LOG_FILE_PATTERN_V2.matcher(file.name)
                if (v2Match.matches()) {
                    return@mapNotNull v2Match.group(1)?.toLongOrNull()
                        ?.let { timestamp -> file to timestamp }
                }

                val v1Match = PERM_LOG_FILE_PATTERN.matcher(file.name)
                if (v1Match.matches()) {
                    return@mapNotNull v1Match.group(1)?.toLongOrNull()
                        ?.let { timestamp -> file to timestamp }
                }

                null
            }
            .sortedByDescending { (_, timestamp) -> timestamp }
            .mapTo(arrayListOf()) { it.first }

        if (includeActiveLogFile) {
            // v2 current file is newest (index 0), v1 current file next (index 1)
            val currentV1 = File(logFolder, CURRENT_LOG_FILE_NAME)
            if (currentV1.exists() && currentV1.length() > 0) {
                files.add(0, currentV1)
            }

            val currentV2 = File(logFolder, CURRENT_LOG_FILE_NAME_V2)
            if (currentV2.exists() && currentV2.length() > 0) {
                files.add(0, currentV2)
            }
        }

        return files
    }

    /**
     * Reads all log entries from the log files and writes them as a ZIP file at the specified URI.
     *
     * This method will block until all log entries are read and written.
     */
    fun readAllLogsCompressed(output: Uri) {
        val logs = getLogFilesSorted(includeActiveLogFile = true).apply { reverse() }

        if (logs.isEmpty()) {
            android.util.Log.w(TAG, "No log files found to read.")
            return
        }

        requireNotNull(context.contentResolver.openOutputStream(output, "w")?.buffered()) {
            "Failed to open output stream for URI: $output"
        }.use { outStream ->
            ZipOutputStream(outStream).use { zipOut ->
                zipOut.putNextEntry(ZipEntry("log.txt"))

                for (log in logs) {
                    try {
                        if (log.isV2LogFile()) {
                            DecryptionStream(log.inputStream(), secret).use { it.copyTo(zipOut) }
                        } else {
                            LogFile.Reader(secret, log).use { reader ->
                                generateSequence { reader.readEntryBytes() }
                                    .forEach { entry ->
                                        zipOut.write(entry)

                                        if (entry.isEmpty() || entry.last().toInt() != '\n'.code) {
                                            zipOut.write('\n'.code)
                                        }
                                    }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error reading log file: ${log.name}", e)
                    }
                }
                zipOut.closeEntry()
            }
        }
    }

    private class LogEntry(
        var logLevel: String,
        var tag: String?,
        var message: String?,
        var err: Throwable?,
        var timestampMills: Long,
    )

    /**
     * A pool for reusing [LogEntry] objects to reduce memory allocations.
     */
    private class LogEntryPool {
        private val pool = ArrayList<LogEntry>(MAX_LOG_ENTRIES_POOL_SIZE)

        fun createLogEntry(level: String, tag: String?, message: String?, t: Throwable?): LogEntry {
            val fromPool = synchronized(pool) { pool.removeLastOrNull() }

            val now = System.currentTimeMillis()

            if (fromPool != null) {
                fromPool.logLevel = level
                fromPool.tag = tag
                fromPool.message = message
                fromPool.err = t
                fromPool.timestampMills = now
                return fromPool
            }

            return LogEntry(
                logLevel = level,
                tag = tag,
                message = message,
                err = t,
                timestampMills = now
            )
        }

        fun release(entry: Iterable<LogEntry>) {
            val iterator = entry.iterator()
            synchronized(pool) {
                while (pool.size < MAX_LOG_ENTRIES_POOL_SIZE && iterator.hasNext()) {
                    pool.add(iterator.next())
                }
            }
        }
    }

    companion object {
        private const val TAG = "PersistentLoggingV2"

        private const val LOG_V: String = "V"
        private const val LOG_D: String = "D"
        private const val LOG_I: String = "I"
        private const val LOG_W: String = "W"
        private const val LOG_E: String = "E"
        private const val LOG_WTF: String = "A"

        // v1 format (AES-CBC per-entry encryption)
        private const val PERM_LOG_FILE_SUFFIX = ".permlog"
        private const val CURRENT_LOG_FILE_NAME = "current.log"
        private val PERM_LOG_FILE_PATTERN by lazy { Pattern.compile("^(\\d+?)\\.permlog$") }

        // v2 format (ChaCha20 stream encryption)
        private const val PERM_LOG_FILE_SUFFIX_V2 = ".v2.permlog"
        private const val CURRENT_LOG_FILE_NAME_V2 = "current.v2.log"
        private val PERM_LOG_FILE_PATTERN_V2 by lazy { Pattern.compile("^(\\d+?)\\.v2\\.permlog$") }

        // Maximum size of a single log file
        private const val MAX_SINGLE_LOG_FILE_SIZE = 2 * 1024 * 1024

        // Maximum number of log files to keep
        private const val MAX_LOG_FILE_COUNT = 10

        private const val MAX_LOG_ENTRIES_POOL_SIZE = 64
        private const val MAX_PENDING_LOG_ENTRIES = 65536
        private const val MAX_BULK_DRAIN_SIZE = 512

        private fun File.isV2LogFile(): Boolean =
            name.endsWith(PERM_LOG_FILE_SUFFIX_V2) || name == CURRENT_LOG_FILE_NAME_V2
    }
}