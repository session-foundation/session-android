package org.session.libsignal.utilities

import org.junit.Assert.*

import org.junit.Before
import org.junit.Test
import org.session.libsignal.utilities.ByteArrayView.Companion.view

class ByteArrayViewTest {

    @Before
    fun setUp() {
    }

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
}