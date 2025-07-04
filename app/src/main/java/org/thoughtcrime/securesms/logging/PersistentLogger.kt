package org.thoughtcrime.securesms.logging

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.session.libsignal.utilities.Log.Logger
import java.io.File
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
    @ApplicationContext private val context: Context
) : Logger() {
    private val freeLogEntryPool = LogEntryPool()
    private val logEntryChannel: SendChannel<LogEntry>
    private val logChannelIdleSignal = MutableSharedFlow<Unit>()

    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS zzz", Locale.ENGLISH)

    private val secret by lazy {
        LogSecretProvider.getOrCreateAttachmentSecret(context)
    }

    private val logFolder by lazy {
        File(context.cacheDir, "logs").apply {
            mkdirs()
        }
    }

    init {
        val channel = Channel<LogEntry>(capacity = MAX_PENDING_LOG_ENTRIES)
        logEntryChannel = channel

        GlobalScope.launch {
            val bulk = ArrayList<LogEntry>()
            var logWriter: LogFile.Writer? = null
            val entryBuilder = StringBuilder()

            try {
                while (true) {
                    channel.receiveBulkLogs(bulk)

                    if (bulk.isNotEmpty()) {
                        if (logWriter == null) {
                            logWriter = LogFile.Writer(secret, File(logFolder, CURRENT_LOG_FILE_NAME))
                        }

                        bulkWrite(entryBuilder, logWriter, bulk)

                        // Release entries back to the pool
                        freeLogEntryPool.release(bulk)
                        bulk.clear()

                        // Rotate the log file if necessary
                        if (logWriter.logSize > MAX_SINGLE_LOG_FILE_SIZE) {
                            rotateAndTrimLogFiles(logWriter.file)
                            logWriter.close()
                            logWriter = null
                        }
                    }

                    // Notify that the log channel is idle
                    logChannelIdleSignal.tryEmit(Unit)
                }
            } catch (e: Exception) {
                logWriter?.close()

                android.util.Log.e(
                    TAG,
                    "Error while processing log entries: ${e.message}",
                    e
                )
            }
        }
    }

    private fun rotateAndTrimLogFiles(currentFile: File) {
        val permLogFile = File(logFolder, "${System.currentTimeMillis()}$PERM_LOG_FILE_SUFFIX")
        if (currentFile.renameTo(permLogFile)) {
            android.util.Log.d(TAG, "Rotated log file: $currentFile to $permLogFile")
            currentFile.createNewFile()
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

    private fun bulkWrite(sb: StringBuilder, writer: LogFile.Writer, bulk: List<LogEntry>) {
        for (entry in bulk) {
            sb.clear()
            sb.append(logDateFormat.format(entry.timestampMills))
                .append(' ')
                .append(entry.logLevel)
                .append(' ')
                .append(entry.tag.orEmpty())
                .append(": ")
                .append(entry.message.orEmpty())
            entry.err?.let {
                sb.append('\n')
                sb.append(it.stackTraceToString())
            }
            writer.writeEntry(sb.toString(), false)
        }

        writer.flush()
    }

    private suspend fun ReceiveChannel<LogEntry>.receiveBulkLogs(out: MutableList<LogEntry>) {
        out += receive()

        withTimeoutOrNull(500.milliseconds) {
            repeat(15) {
                out += receiveCatching().getOrNull() ?: return@repeat
            }
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
            .mapNotNull {
                if (!it.isFile) return@mapNotNull null
                PERM_LOG_FILE_PATTERN.matcher(it.name).takeIf { it.matches() }
                    ?.group(1)
                    ?.toLongOrNull()
                    ?.let { timestamp -> it to timestamp }
            }
            .toMutableList()
            .apply { sortByDescending { (_, timestamp) -> timestamp } }
            .mapTo(arrayListOf()) { it.first }

        if (includeActiveLogFile) {
            val currentLogFile = File(logFolder, CURRENT_LOG_FILE_NAME)
            if (currentLogFile.exists()) {
                files.add(0, currentLogFile)
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

        requireNotNull(context.contentResolver.openOutputStream(output, "w")) {
            "Failed to open output stream for URI: $output"
        }.use { outStream ->
            ZipOutputStream(outStream).use { zipOut ->
                zipOut.putNextEntry(ZipEntry("log.txt"))
                for (log in logs) {
                    LogFile.Reader(secret, log).use { reader ->
                        var entry = reader.readEntryBytes()
                        while (entry != null) {
                            zipOut.write(entry)
                            zipOut.write('\n'.code)
                            entry = reader.readEntryBytes()
                        }
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

        private const val PERM_LOG_FILE_SUFFIX = ".permlog"
        private const val CURRENT_LOG_FILE_NAME = "current.log"
        private val PERM_LOG_FILE_PATTERN by lazy { Pattern.compile("^(\\d+?)\\.permlog$") }

        // Maximum size of a single log file
        private const val MAX_SINGLE_LOG_FILE_SIZE = 2 * 1024 * 1024

        // Maximum number of log files to keep
        private const val MAX_LOG_FILE_COUNT = 10

        private const val MAX_LOG_ENTRIES_POOL_SIZE = 64
        private const val MAX_PENDING_LOG_ENTRIES = 65536
    }
}