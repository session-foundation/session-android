package org.thoughtcrime.securesms.jobmanager.impl

import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.utilities.Util.readFullyAsString
import java.io.IOException

class JsonDataSerializerTest {
    @Test
    fun deserialize_dataMatchesExpected() {
        val data: Data = Json.decodeFromString(
            readFullyAsString(
                ClassLoader.getSystemClassLoader().getResourceAsStream("data/data_serialized.json")
            )
        )

        Assert.assertEquals("s1 value", data.getString("s1"))
        Assert.assertEquals("s2 value", data.getString("s2"))
        Assert.assertArrayEquals(arrayOf<String>("a", "b", "c"), data.getStringArray("s_array_1"))

        Assert.assertEquals(1, data.getInt("i1").toLong())
        Assert.assertEquals(2, data.getInt("i2").toLong())
        Assert.assertEquals(Int.Companion.MAX_VALUE.toLong(), data.getInt("max").toLong())
        Assert.assertEquals(Int.Companion.MIN_VALUE.toLong(), data.getInt("min").toLong())
        Assert.assertArrayEquals(
            intArrayOf(
                1,
                2,
                3,
                Int.Companion.MAX_VALUE,
                Int.Companion.MIN_VALUE
            ), data.getIntegerArray("i_array_1")
        )

        Assert.assertEquals(10, data.getLong("l1"))
        Assert.assertEquals(20, data.getLong("l2"))
        Assert.assertEquals(Long.Companion.MAX_VALUE, data.getLong("max"))
        Assert.assertEquals(Long.Companion.MIN_VALUE, data.getLong("min"))
        Assert.assertArrayEquals(
            longArrayOf(
                1,
                2,
                3,
                Long.Companion.MAX_VALUE,
                Long.Companion.MIN_VALUE
            ), data.getLongArray("l_array_1")
        )

        Assert.assertEquals(1.2f, data.getFloat("f1"), FloatDelta)
        Assert.assertEquals(3.4f, data.getFloat("f2"), FloatDelta)
        Assert.assertArrayEquals(
            floatArrayOf(5.6f, 7.8f),
            data.getFloatArray("f_array_1"),
            FloatDelta
        )

        Assert.assertEquals(10.2, data.getDouble("d1"), FloatDelta.toDouble())
        Assert.assertEquals(30.4, data.getDouble("d2"), FloatDelta.toDouble())
        Assert.assertArrayEquals(
            doubleArrayOf(50.6, 70.8),
            data.getDoubleArray("d_array_1"),
            FloatDelta.toDouble()
        )

        Assert.assertTrue(data.getBoolean("b1"))
        Assert.assertFalse(data.getBoolean("b2"))
        Assert.assertArrayEquals(booleanArrayOf(false, true), data.getBooleanArray("b_array_1"))
    }

    companion object {
        private const val FloatDelta = 0.00001f
    }
}