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

    /**
     * Parse only Years/Months: P1M, P3M, P1Y, P1Y6M. (Weeks/Days intentionally ignored.)
     **/
    fun monthsFromIso(iso: String): Int {
        val y = Regex("""(\d+)Y""").find(iso)?.groupValues?.get(1)?.toInt() ?: 0
        val m = Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toInt() ?: 0
        return (y * 12 + m).coerceAtLeast(1)
    }

    /**
     * Currency fraction digits (e.g., USD=2, JPY=0). Default to 2 if unknown.
     **/
    private fun fractionDigits(code: String): Int =
        Currency.getInstance(code).defaultFractionDigits.let { if (it >= 0) it else 2 }

    /**
     * PRD rule: (total/months) then **ROUND DOWN** to the currency’s smallest unit.
     **/
    fun perMonthUnitsFloor(totalMicros: Long, months: Int, currencyCode: String): BigDecimal {
        // 1) Convert Play’s micros → currency *units* as a BigDecimal
        val units = BigDecimal(totalMicros).divide(BigDecimal(1_000_000)) // e.g., 47_99_0000 → 47.99

        // 2) Compute the raw monthly price: total / months.
        //    We keep extra precision (scale=10) and ROUND DOWN to avoid accidental rounding up mid-way.
        val perMonth = units.divide(BigDecimal(months), 10, RoundingMode.DOWN)

        // 3) Floor to the currency’s smallest unit (fraction digits):
        //    USD/EUR/AUD → 2 decimals, JPY/KRW → 0 decimals, KWD → 3 decimals, etc.
        return perMonth.setScale(fractionDigits(currencyCode), RoundingMode.DOWN)
    }

    fun microToBigDecimal(micro: Long): BigDecimal {
        return BigDecimal(micro).divide(BigDecimal(1_000_000))
    }

    /**
     * Locale-correct currency formatting
     **/
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