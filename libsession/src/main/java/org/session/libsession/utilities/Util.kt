package org.session.libsession.utilities

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Util.SECURE_RANDOM
import java.io.*
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.collections.LinkedHashMap
import kotlin.math.min

object Util {
    @Volatile
    private var handler: Handler? = null

    fun isMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    @JvmStatic
    fun assertMainThread() {
        if (!isMainThread()) {
            throw java.lang.AssertionError("Main-thread assertion failed.")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copy(`in`: InputStream, out: OutputStream?): Long {
        val buffer = ByteArray(8192)
        var read: Int
        var total: Long = 0
        while (`in`.read(buffer).also { read = it } != -1) {
            out?.write(buffer, 0, read)
            total += read.toLong()
        }
        `in`.close()
        out?.close()
        return total
    }

    @JvmStatic
    fun uri(uri: String?): Uri? {
        return if (uri == null) null else Uri.parse(uri)
    }

    @JvmStatic
    fun runOnMain(runnable: Runnable) {
        if (isMainThread()) runnable.run()
        else getHandler()?.post(runnable)
    }

    @JvmStatic
    fun runOnMainDelayed(runnable: Runnable, delayMillis: Long) {
        getHandler()?.postDelayed(runnable, delayMillis)
    }

    @JvmStatic
    fun runOnMainSync(runnable: Runnable) {
        if (isMainThread()) {
            runnable.run()
        } else {
            val sync = CountDownLatch(1)
            runOnMain(Runnable {
                try {
                    runnable.run()
                } finally {
                    sync.countDown()
                }
            })
            try {
                sync.await()
            } catch (ie: InterruptedException) {
                throw java.lang.AssertionError(ie)
            }
        }
    }

    @JvmStatic
    fun cancelRunnableOnMain(runnable: Runnable) {
        getHandler()?.removeCallbacks(runnable)
    }

    private fun getHandler(): Handler? {
        if (handler == null) {
            synchronized(Util::class.java) {
                if (handler == null) {
                    handler = Handler(Looper.getMainLooper())
                }
            }
        }
        return handler
    }

    @JvmStatic
    fun wait(lock: Object, timeout: Long) {
        try {
            lock.wait(timeout)
        } catch (ie: InterruptedException) {
            throw AssertionError(ie)
        }
    }

    @JvmStatic
    fun newSingleThreadedLifoExecutor(): ExecutorService {
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingLifoQueue<Runnable>())
        executor.execute {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
        }
        return executor
    }

    @JvmStatic
    fun join(list: Array<String?>, delimiter: String?): String {
        return join(Arrays.asList(*list), delimiter)
    }

    @JvmStatic
    fun join(list: Collection<String?>, delimiter: String?): String {
        val result = StringBuilder()
        var i = 0
        for (item in list) {
            result.append(item)
            if (++i < list.size) result.append(delimiter)
        }
        return result.toString()
    }

    @JvmStatic
    fun join(list: LongArray, delimeter: String?): String {
        val sb = java.lang.StringBuilder()
        for (j in list.indices) {
            if (j != 0) sb.append(delimeter)
            sb.append(list[j])
        }
        return sb.toString()
    }

    @JvmStatic
    fun <E> chunk(list: List<E>, chunkSize: Int): List<List<E>> {
        val chunks: MutableList<List<E>> = ArrayList(list.size / chunkSize)
        var i = 0
        while (i < list.size) {
            val chunk = list.subList(i, Math.min(list.size, i + chunkSize))
            chunks.add(chunk)
            i += chunkSize
        }
        return chunks
    }

    @JvmStatic
    fun equals(a: Any?, b: Any?): Boolean {
        return a === b || a != null && a == b
    }

    @JvmStatic
    fun hashCode(vararg objects: Any?): Int {
        return Arrays.hashCode(objects)
    }

    @JvmStatic
    fun <K, V> getOrDefault(map: Map<K, V>, key: K, defaultValue: V): V? {
        return if (map.containsKey(key)) map[key] else defaultValue
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getStreamLength(`in`: InputStream): Long {
        val buffer = ByteArray(4096)
        var totalSize = 0
        var read: Int
        while (`in`.read(buffer).also { read = it } != -1) {
            totalSize += read
        }
        return totalSize.toLong()
    }

    @JvmStatic
    fun toIntExact(value: Long): Int {
        if (value.toInt().compareTo(value) != 0){
            throw ArithmeticException("integer overflow")
        }
        return value.toInt()
    }

    @JvmStatic
    fun close(closeable: Closeable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            Log.w("Loki", e)
        }
    }

    @JvmStatic
    fun isOwnNumber(context: Context, number: String): Boolean {
        return TextSecurePreferences.getLocalNumber(context).equals(number)
    }

    @JvmStatic
    fun <T> partition(list: List<T>, partitionSize: Int): List<List<T>> {
        val results: MutableList<List<T>> = LinkedList()
        var index = 0
        while (index < list.size) {
            val subListSize = min(partitionSize, list.size - index)
            results.add(list.subList(index, index + subListSize))
            index += partitionSize
        }
        return results
    }

    @JvmStatic
    fun toIsoString(bytes: ByteArray?): String {
        return String(bytes!!, StandardCharsets.ISO_8859_1)
    }

    @JvmStatic
    fun toIsoBytes(isoString: String): ByteArray {
        return isoString.toByteArray(StandardCharsets.ISO_8859_1)
    }

    @JvmStatic
    fun toUtf8Bytes(utf8String: String): ByteArray {
        return utf8String.toByteArray(StandardCharsets.UTF_8)
    }

    @JvmStatic
    @SuppressLint("NewApi")
    fun isDefaultSmsProvider(context: Context): Boolean {
        return context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(`in`: InputStream?, buffer: ByteArray) {
        if (`in` == null) return
        readFully(`in`, buffer, buffer.size)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(`in`: InputStream, buffer: ByteArray?, len: Int) {
        var offset = 0
        while (true) {
            val read = `in`.read(buffer, offset, len - offset)
            if (read == -1) throw EOFException("Stream ended early")
            offset += if (read + offset < len) read else return
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(`in`: InputStream): ByteArray? {
        val bout = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var read: Int
        while (`in`.read(buffer).also { read = it } != -1) {
            bout.write(buffer, 0, read)
        }
        `in`.close()
        return bout.toByteArray()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFullyAsString(`in`: InputStream): String? {
        return String(readFully(`in`)!!)
    }

    @JvmStatic
    fun getSecret(size: Int): String? {
        val secret = getSecretBytes(size)
        return Base64.encodeBytes(secret)
    }

    @JvmStatic
    fun getSecretBytes(size: Int): ByteArray {
        val secret = ByteArray(size)
        SECURE_RANDOM.nextBytes(secret)
        return secret
    }

    @JvmStatic
    fun getFirstNonEmpty(vararg values: String?): String? {
        for (value in values) {
            if (!TextUtils.isEmpty(value)) {
                return value
            }
        }
        return ""
    }

    @JvmStatic
    fun isEmpty(collection: Collection<*>?): Boolean {
        return collection == null || collection.isEmpty()
    }

    @JvmStatic
    fun <T> getRandomElement(elements: Array<T>): T = elements[SECURE_RANDOM.nextInt(elements.size)]

    @JvmStatic
    fun getBoldedString(value: String?): CharSequence {
        if (value.isNullOrEmpty()) { return "" }
        return SpannableString(value).also {
            it.setSpan(StyleSpan(Typeface.BOLD), 0, it.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.min(Math.max(value, min), max)
    }

    @JvmStatic
    fun combine(vararg elements: ByteArray?): ByteArray? {
        return try {
            val baos = ByteArrayOutputStream()
            for (element in elements) {
                baos.write(element)
            }
            baos.toByteArray()
        } catch (e: IOException) {
            throw java.lang.AssertionError(e)
        }
    }

    @JvmStatic
    fun split(input: ByteArray?, firstLength: Int, secondLength: Int): Array<ByteArray?> {
        val parts = arrayOfNulls<ByteArray>(2)
        parts[0] = ByteArray(firstLength)
        System.arraycopy(input, 0, parts[0], 0, firstLength)
        parts[1] = ByteArray(secondLength)
        System.arraycopy(input, firstLength, parts[1], 0, secondLength)
        return parts
    }

    @JvmStatic
    fun getPrettyFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0"
        val units = arrayOf("B", "kB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(sizeBytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}

fun <T, R> T.runIf(condition: Boolean, block: T.() -> R): R where T: R = if (condition) block() else this

/**
 * Returns a [Map] containing the elements from the given array indexed by the key
 * returned from [keySelector] function applied to each element.
 *
 * If any two elements would have the same key returned by [keySelector] the last one gets added to the map.
 *
 * If any element is null or the key is null, it will not be added to the [Map].
 *
 * @see associateBy
 */
fun <T, K: Any> Iterable<T>.associateByNotNull(
    keySelector: (T) -> K?
) = associateByNotNull(keySelector) { it }

/**
 * Returns a [Map] containing the values provided by [valueTransform] and indexed by [keySelector]
 * functions applied to elements of the given collection.
 *
 * If any two elements would have the same key returned by [keySelector] the last one gets added to
 * the map.
 *
 * If any element produces a null key or value it will not be added to the [Map].
 *
 * @see associateBy
 */
fun <E, K: Any, V: Any> Iterable<E>.associateByNotNull(
    keySelector: (E) -> K?,
    valueTransform: (E) -> V?,
): Map<K, V> = mutableMapOf<K, V>().also {
    for(e in this) { it[keySelector(e) ?: continue] = valueTransform(e) ?: continue }
}

fun <K: Any, V: Any, W : Any> Map<K, V>.mapValuesNotNull(
    valueTransform: (Map.Entry<K, V>) -> W?
): Map<K, W> = mutableMapOf<K, W>().also {
    for(e in this) { it[e.key] = valueTransform(e) ?: continue }
}

/**
 * Groups elements of the original collection by the key returned by the given [keySelector] function
 * applied to each element and returns a map where each group key is associated with a list of
 * corresponding elements, omitting elements with a null key.
 *
 * @see groupBy
 */
inline fun <E, K> Iterable<E>.groupByNotNull(keySelector: (E) -> K?): Map<K, List<E>> = LinkedHashMap<K, MutableList<E>>().also {
    forEach { e -> keySelector(e)?.let { k -> it.getOrPut(k) { mutableListOf() } += e } }
}

/**
 * Analogous to [buildMap], this function creates a [MutableMap] and populates it using the given [action].
 */
inline fun <K, V> buildMutableMap(action: MutableMap<K, V>.() -> Unit): MutableMap<K, V> =
    mutableMapOf<K, V>().apply(action)

/**
 * Converts a list of Pairs into a Map, filtering out any Pairs where the value is null.
 *
 * @param pairs The list of Pairs to convert.
 * @return A Map with non-null values.
 */
fun <K : Any, V : Any> Iterable<Pair<K, V?>>.toMapNotNull(): Map<K, V> =
    associateByNotNull(Pair<K, V?>::first, Pair<K, V?>::second)

fun Sequence<String>.toByteArray(): ByteArray = ByteArrayOutputStream().use { output ->
    forEach { it.byteInputStream().use { input -> input.copyTo(output) } }
    output.toByteArray()
}
