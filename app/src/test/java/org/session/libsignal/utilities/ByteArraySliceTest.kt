package org.session.libsignal.utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.session.libsignal.utilities.ByteArraySlice.Companion.view

class ByteArraySliceTest {
    @Test
    fun `view works`() {
        val sliced = byteArrayOf(1, 2, 3, 4, 5).view(1..3)
        assertEquals(listOf<Byte>(2, 3, 4), sliced.asList())
    }

    @Test
    fun `re-view works`() {
        val sliced = byteArrayOf(1, 2, 3, 4, 5).view(1..3)
        val resliced = sliced.view(1..2)
        assertEquals(listOf<Byte>(3, 4), resliced.asList())
    }

    @Test
    fun `decodeToString works`() {
        assertEquals(
            "hel",
            "hello, world".toByteArray().view(0..2).decodeToString()
        )
    }

    @Test
    fun `inputStream works`() {
        assertArrayEquals(
            "hello, world".toByteArray(),
            "hello, world".toByteArray().inputStream().readBytes()
        )
    }

    @Test
    fun `able to view empty array`() {
        val sliced = byteArrayOf().view()
        assertEquals(0, sliced.len)
        assertEquals(0, sliced.offset)
    }
}