package org.thoughtcrime.securesms.api.http

import okio.utf8Size
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import java.io.InputStream

sealed interface HttpBody {
    val byteLength: Int

    fun asInputStream(): InputStream
    fun toBytes(): ByteArray
    fun toByteArraySlice(): ByteArraySlice {
        return toBytes().view()
    }

    fun toText(): String?

    class Text(val text: String): HttpBody {
        override fun toBytes(): ByteArray {
            return text.toByteArray()
        }

        override fun toString(): String {
            return "Text(${text.take(50)}..., length=${text.length})"
        }

        override fun asInputStream(): InputStream {
            return text.byteInputStream()
        }

        override val byteLength: Int
            get() = text.utf8Size().toInt()

        override fun toText(): String {
            return text
        }
    }

    class Bytes(val bytes: ByteArray): HttpBody {
        override fun toString(): String {
            return "Bytes(length=${bytes.size}, asText=${toText()?.take(50)})"
        }

        override fun toBytes(): ByteArray {
            return bytes
        }

        override fun asInputStream(): InputStream {
            return bytes.inputStream()
        }

        override val byteLength: Int
            get() = bytes.size

        override fun toText(): String? {
            return runCatching {
                bytes.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        }
    }

    class ByteSlice(val slice: ByteArraySlice): HttpBody {
        override fun toBytes(): ByteArray {
            return slice.copyToBytes()
        }

        override fun toString(): String {
            return "ByteSlice(length=${slice.len}, asText=${toText()?.take(50)})"
        }

        override fun asInputStream(): InputStream {
            return slice.inputStream()
        }

        override val byteLength: Int
            get() = slice.len

        override fun toByteArraySlice(): ByteArraySlice {
            return slice
        }

        override fun toText(): String? {
            return runCatching {
                slice.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        }
    }

    companion object {
        fun empty(): HttpBody = Bytes(byteArrayOf())
    }
}