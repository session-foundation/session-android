package org.thoughtcrime.securesms.util


import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.util.NumberUtil.formatAbbreviated
import java.math.BigDecimal
import java.util.Locale

class NumberAbbreviatorTests {

    @Test
    fun `formatAbbreviated should format thousands correctly`() {
        assertEquals("1.0K", 1000.formatAbbreviated())
        assertEquals("1.5K", 1500.formatAbbreviated())
        assertEquals("999.9K", 999900.formatAbbreviated())
    }

    @Test
    fun `formatAbbreviated should format millions correctly`() {
        assertEquals("1.0M", 1000000.formatAbbreviated())
        assertEquals("1.5M", 1500000.formatAbbreviated())
        assertEquals("999.9M", 999900000.formatAbbreviated())
    }

    @Test
    fun `formatAbbreviated should format billions correctly`() {
        assertEquals("1.0B", 1000000000.formatAbbreviated())
        assertEquals("1.5B", 1500000000.formatAbbreviated())
        assertEquals("999.9B", BigDecimal("999900000000").formatAbbreviated())
    }

    @Test
    fun `formatAbbreviated should format trillions correctly`() {
        assertEquals("1.0T", BigDecimal("1000000000000").formatAbbreviated())
        assertEquals("1.5T", BigDecimal("1500000000000").formatAbbreviated())
        assertEquals("999.9T", BigDecimal("999900000000000").formatAbbreviated())
    }

    @Test
    fun `formatAbbreviated should format small numbers without suffix`() {
        assertEquals("100.0", 100.formatAbbreviated())
        assertEquals("500.0", 500.formatAbbreviated())
        assertEquals("999.0", 999.formatAbbreviated())
        assertEquals("0.0", 0.formatAbbreviated())
    }

    @Test
    fun `formatAbbreviated should handle negative numbers`() {
        assertEquals("-1.0K", (-1000).formatAbbreviated())
        assertEquals("-1.0M", (-1000000).formatAbbreviated())
        assertEquals("-500.0", (-500).formatAbbreviated())
    }

    @Test
    fun `formatAbbreviated should respect fraction digit parameters`() {
        assertEquals("1.00K", 1000.formatAbbreviated(minFractionDigits = 2, maxFractionDigits = 2))
        assertEquals("1K", 1000.formatAbbreviated(minFractionDigits = 0, maxFractionDigits = 0))
        assertEquals("1.234K", 1234.formatAbbreviated(minFractionDigits = 3, maxFractionDigits = 3))
        assertEquals("123", 123.formatAbbreviated(minFractionDigits = 0, maxFractionDigits = 0))
        assertEquals("123.00", 123.formatAbbreviated(minFractionDigits = 2, maxFractionDigits = 2))
    }

    @Test
    fun `formatAbbreviated should respect locale settings`() {
        assertEquals("1,0K", 1000.formatAbbreviated(locale = Locale.GERMANY))
        assertEquals("1,5M", 1500000.formatAbbreviated(locale = Locale.FRANCE))
        assertEquals("123,5", 123.5.formatAbbreviated(locale = Locale.GERMANY))
    }

    @Test
    fun `formatAbbreviated should handle different Number types`() {
        assertEquals("1.5K", 1500L.formatAbbreviated())
        assertEquals("1.5K", 1500.0f.formatAbbreviated())
        assertEquals("1.5K", 1500.0.formatAbbreviated())
        assertEquals("1.5K", BigDecimal("1500").formatAbbreviated())
        assertEquals("123.0", 123L.formatAbbreviated())
        assertEquals("123.0", 123.0.formatAbbreviated())
    }

    @Test
    fun `formatAbbreviated should handle boundary values correctly`() {
        // Just below thresholds
        assertEquals("999.0", 999.formatAbbreviated())
        assertEquals("999.9K", 999900.formatAbbreviated())
        assertEquals("999.9M", 999900000.formatAbbreviated())
        assertEquals("999.9B", BigDecimal("999900000000").formatAbbreviated())

        // Just at thresholds
        assertEquals("1.0K", 1000.formatAbbreviated())
        assertEquals("1.0M", 1000000.formatAbbreviated())
        assertEquals("1.0B", 1000000000.formatAbbreviated())
        assertEquals("1.0T", BigDecimal("1000000000000").formatAbbreviated())
    }
}