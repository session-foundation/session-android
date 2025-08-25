package org.thoughtcrime.securesms.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.util.NumberUtil.formatWithDecimalPlaces
import java.util.Locale

class NumberFormatterTests {

    @Test
    fun `formatWithDecimalPlaces should format integers correctly with zero decimal places`() {
        assertEquals("1,234", 1234.formatWithDecimalPlaces(0))
        assertEquals("0", 0.formatWithDecimalPlaces(0))
        assertEquals("-5,678", (-5678).formatWithDecimalPlaces(0))
    }

    @Test
    fun `formatWithDecimalPlaces should format integers correctly with decimal places`() {
        assertEquals("1,234.00", 1234.formatWithDecimalPlaces(2))
        assertEquals("0.000", 0.formatWithDecimalPlaces(3))
        assertEquals("-5,678.0", (-5678).formatWithDecimalPlaces(1))
    }

    @Test
    fun `formatWithDecimalPlaces should format decimals correctly`() {
        assertEquals("1,234.56", 1234.56.formatWithDecimalPlaces(2))
        assertEquals("1,234.6", 1234.56.formatWithDecimalPlaces(1))
        assertEquals("1,234.560", 1234.56.formatWithDecimalPlaces(3))
    }

    @Test
    fun `formatWithDecimalPlaces should handle rounding correctly`() {
        assertEquals("1,234.57", 1234.567.formatWithDecimalPlaces(2))
        assertEquals("1,234.57", 1234.566.formatWithDecimalPlaces(2))
        assertEquals("1,235", 1234.6.formatWithDecimalPlaces(0))
    }

    @Test
    fun `formatWithDecimalPlaces should format large numbers correctly`() {
        assertEquals("1,234,567.89", 1234567.89.formatWithDecimalPlaces(2))
        assertEquals("123,456,789", 123456789.formatWithDecimalPlaces(0))
    }

    @Test
    fun `formatWithDecimalPlaces should format small decimals correctly`() {
        assertEquals("0.05", 0.05.formatWithDecimalPlaces(2))
        assertEquals("0.1", 0.1.formatWithDecimalPlaces(1))
        assertEquals("0.00", 0.001.formatWithDecimalPlaces(2))
    }

    @Test
    fun `formatWithDecimalPlaces should respect different locales`() {
        // Using French locale which uses space as grouping separator and comma as decimal separator
        assertEquals("1\u202F234,56", 1234.56.formatWithDecimalPlaces(2, Locale.FRANCE))
        assertEquals("1\u202F234,6", 1234.56.formatWithDecimalPlaces(1, Locale.FRANCE))

        // Using German locale
        assertEquals("1.234,56", 1234.56.formatWithDecimalPlaces(2, Locale.GERMANY))
    }

    @Test
    fun `formatWithDecimalPlaces should work with different Number types`() {
        assertEquals("123.40", (123.4f).formatWithDecimalPlaces(2))
        assertEquals("123.40", (123.4).formatWithDecimalPlaces(2))
        assertEquals("123.00", (123L).formatWithDecimalPlaces(2))
        assertEquals("123.00", (123.toBigDecimal()).formatWithDecimalPlaces(2))
    }
}