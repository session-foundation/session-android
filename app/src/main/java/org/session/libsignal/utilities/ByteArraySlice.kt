package org.session.libsignal.utilities

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A view of a byte array with a range. This is useful for avoiding copying data when slicing a byte array.
 */
class ByteArraySlice private constructor(
    val data: ByteArray,
    val offset: Int,
    val len: Int,
) {
    init {
        check(offset in 0..data.size) { "Offset $offset is not within [0..${data.size}]" }
        check(len in 0..data.size) { "Length $len is not within [0..${data.size}]" }
    }

    fun view(range: IntRange): ByteArraySlice {
        val newOffset = offset + range.first
        val newLength = range.last + 1 - range.first
        return ByteArraySlice(
            data = data,
            offset = newOffset,
            len = newLength
        )
    }

    fun copyToBytes(): ByteArray {
        return data.copyOfRange(offset, offset + len)
    }

    operator fun get(index: Int): Byte {
        return data[offset + index]
    }

    fun asList(): List<Byte> {
        return object : AbstractList<Byte>() {
            override val size: Int
                get() = this@ByteArraySlice.len

            override fun get(index: Int) = this@ByteArraySlice[index]
        }
    }

    fun decodeToString(): String {
        return data.decodeToString(offset, offset + len)
    }

    fun inputStream(): InputStream {
        return ByteArrayInputStream(data, offset, len)
    }

    fun isEmpty(): Boolean = len == 0
    fun isNotEmpty(): Boolean = len != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteArraySlice) return false

        if (offset != other.offset) return false
        if (len != other.len) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset
        result = 31 * result + len
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        val EMPTY = ByteArraySlice(byteArrayOf(), 0, 0)

        /**
         * Create a view of a byte array
         */
        fun ByteArray.view(range: IntRange = indices): ByteArraySlice {
            return ByteArraySlice(
                data = this,
                offset = range.first,
                len = range.last + 1 - range.first
            )
        }

        fun OutputStream.write(view: ByteArraySlice) {
            write(view.data, view.offset, view.len)
        }
    }
}
