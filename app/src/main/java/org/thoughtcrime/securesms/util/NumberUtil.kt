package org.thoughtcrime.securesms.util

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

object NumberUtil {

    // Method to format a number so that 1000 becomes 1k, 1200 becomes 1.2k etc. - typically used to display
    // the count of emoji reactions.
    // Note: This method is only designed to handle values in the scale of 0..999_999 - it will return "1000k"
    // etc. for values greater than a million.
    @JvmStatic
    fun getFormattedNumber(value: Long): String {

        // Values less than 1000 get returned as just that
        // Note: While we abs the value for range, we actually return the original value with the sign
        val absoluteCount = abs(value)
        if (absoluteCount < 1000) return value.toString()

        // Otherwise we work out the thousands and hundreds values to use in "1.2 etc.
        val thousands = absoluteCount / 1000
        val hundreds = (absoluteCount - thousands * 1000) / 100

        // Set a negative prefix to be either a minus sign or nothing
        val negativePrefix = if (value < 0) "-" else ""

        // Finally, return the formatted string
        return if (hundreds == 0L) {
            String.format(Locale.ROOT, "$negativePrefix%dk", thousands)
        } else {
            String.format(Locale.ROOT, "$negativePrefix%d.%dk", thousands, hundreds)
        }
    }

    /**
     * Extension function on Number to format it with specified decimal places using locale settings.
     *
     * @param decimalPlaces Number of decimal places to display
     * @param locale Locale for formatting (defaults to system locale)
     * @return Formatted string representation of the number
     */
    fun Number.formatWithDecimalPlaces(decimalPlaces: Int, locale: Locale = Locale.getDefault()): String {
        val pattern = if (decimalPlaces > 0) {
            "#,##0.${"0".repeat(decimalPlaces)}"
        } else {
            "#,##0"
        }

        // Create locale-specific decimal format symbols
        val symbols = DecimalFormatSymbols(locale)

        return DecimalFormat(pattern, symbols).apply {
            this.isDecimalSeparatorAlwaysShown = decimalPlaces > 0
            this.maximumFractionDigits = decimalPlaces
            this.minimumFractionDigits = decimalPlaces
        }.format(this)
    }

    /**
     * Extension function on Number to format it with abbreviated suffixes (K, M, B, T)
     * in a locale-aware way.
     *
     * @param locale Locale for formatting (defaults to system locale)
     * @param minFractionDigits Minimum fraction digits to display
     * @param maxFractionDigits Maximum fraction digits to display
     * @return Formatted string with appropriate suffix, or full number if below 1,000.
     */
    fun Number.formatAbbreviated(
        locale: Locale = Locale.getDefault(),
        minFractionDigits: Int = 1,
        maxFractionDigits: Int = 1
    ): String {
        // Convert to BigDecimal for precise arithmetic
        val bd = when (this) {
            is BigDecimal -> this
            is Long, is Int, is Short, is Byte -> BigDecimal(this.toLong())
            is Double, is Float -> BigDecimal.valueOf(this.toDouble())
            else -> throw IllegalArgumentException("Unsupported number type: ${this::class.java.name}")
        }
        // Create a locale-aware formatter
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            this.minimumFractionDigits = minFractionDigits
            this.maximumFractionDigits = maxFractionDigits
            this.isGroupingUsed = false
        }
        val absValue = bd.abs()
        return when {
            absValue >= BigDecimal(1_000_000_000_000) ->
                "${formatter.format(bd.divide(BigDecimal(1_000_000_000_000)))}T"
            absValue >= BigDecimal(1_000_000_000) ->
                "${formatter.format(bd.divide(BigDecimal(1_000_000_000)))}B"
            absValue >= BigDecimal(1_000_000) ->
                "${formatter.format(bd.divide(BigDecimal(1_000_000)))}M"
            absValue >= BigDecimal(1_000) ->
                "${formatter.format(bd.divide(BigDecimal(1_000)))}K"
            else -> formatter.format(bd)
        }
    }

}