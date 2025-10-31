package org.thoughtcrime.securesms.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Utility for converting and formatting  prices
 * to correctly localized strings.
 *
 * - Only supports months/years for ISO 8601 billing periods (PXM, PXY, P1Y6M) - We can add more if needed in the future
 */
object CurrencyFormatter {

    /** Parse only Years/Months: P1M, P3M, P1Y, P1Y6M. (Weeks/Days intentionally ignored.) */
    fun monthsFromIso(iso: String): Int {
        val y = Regex("""(\d+)Y""").find(iso)?.groupValues?.get(1)?.toInt() ?: 0
        val m = Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toInt() ?: 0
        return (y * 12 + m).coerceAtLeast(1)
    }

    /** Currency fraction digits with sane default. */
    private fun fractionDigits(code: String): Int =
        Currency.getInstance(code).defaultFractionDigits.let { if (it >= 0) it else 2 }

    /** PRD rule: (total/months) then **ROUND DOWN** to the currency’s smallest unit. */
    fun perMonthUnitsFloor(totalMicros: Long, months: Int, currencyCode: String): BigDecimal {
        val units = BigDecimal(totalMicros).divide(BigDecimal(1_000_000)) // raw units
        val perMonth = units.divide(BigDecimal(months), 10, RoundingMode.DOWN)
        return perMonth.setScale(fractionDigits(currencyCode), RoundingMode.DOWN)
    }

    /** Locale-correct currency formatting (no extra rounding — use the scale already on amount). */
    fun formatUnits(amountUnits: BigDecimal, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        nf.currency = Currency.getInstance(currencyCode)
        return nf.format(amountUnits)
    }

    /**
     * Used to calculate discounts:
     * floor(((baseline - plan)/baseline) * 100). Assumes both inputs already floored to fraction.
     **/
    fun percentOffFloor(baselinePerMonthUnits: BigDecimal, planPerMonthUnits: BigDecimal): Int {
        if (baselinePerMonthUnits <= BigDecimal.ZERO || planPerMonthUnits >= baselinePerMonthUnits) return 0
        val pct = baselinePerMonthUnits.subtract(planPerMonthUnits)
            .divide(baselinePerMonthUnits, 6, RoundingMode.DOWN)
            .multiply(BigDecimal(100))
            .setScale(0, RoundingMode.DOWN)
        return pct.toInt()
    }
}