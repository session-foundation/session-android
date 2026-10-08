package org.thoughtcrime.securesms.pro.subscription

/**
 * Billing-period unit, mirroring libsession's `ProPlanUnit` (pro-wire-protocol.md §1).
 * The unit is PRESERVED exactly as transmitted and is never canonicalized (e.g. "12m" and "1y" are
 * the same real duration but distinct values). A new unit renders generically via the locale formatter.
 */
enum class ProPlanUnit {
    SECOND, DAY, WEEK, MONTH, YEAR, LIFETIME;

    companion object {
        /**
         * Map the glue's lowercase unit name (see libsession-util-android `plan_unit_to_string`, which
         * mirrors the nodejs glue) to a [ProPlanUnit]. Returns null for an unrecognized name — libsession's
         * closed grammar means that shouldn't happen, so callers can pick a benign fallback.
         */
        fun fromWireName(name: String): ProPlanUnit? = when (name) {
            "second" -> SECOND
            "day" -> DAY
            "week" -> WEEK
            "month" -> MONTH
            "year" -> YEAR
            "lifetime" -> LIFETIME
            else -> null
        }
    }
}

/**
 * A parsed billing period: [count] copies of [unit]. Invariant (matching libsession): `count == 0`
 * iff `unit == LIFETIME`; for the periodic units [count] is >= 1. This is the app's own small value
 * type used both for the active-plan display (from the backend) and to express the fixed purchase SKUs.
 */
data class ProPlanPeriod(val count: Int, val unit: ProPlanUnit) {
    val isLifetime: Boolean get() = unit == ProPlanUnit.LIFETIME

    companion object {
        val LIFETIME = ProPlanPeriod(0, ProPlanUnit.LIFETIME)
    }
}

/**
 * The FIXED store SKU catalog: an opaque store slug [id] (used FORWARD ONLY — load catalog, initiate
 * purchase, price lookup) plus its [period] as a (count, unit). This is the purchase/billing catalog
 * and is intentionally a closed list; it is NEVER reverse-mapped from the active backend plan (that
 * renders generically from the backend's own (count, unit) — see [ProPlanPeriod]).
 */
enum class ProSubscriptionDuration(val period: ProPlanPeriod, val id: String) {
    ONE_MONTH(ProPlanPeriod(1, ProPlanUnit.MONTH), "session-pro-1-month"),
    THREE_MONTHS(ProPlanPeriod(3, ProPlanUnit.MONTH), "session-pro-3-months"),
    // The annual SKU's period MUST equal what the backend reports for it — ProPlan.TwelveMonth is the
    // code "1y" (session-pro-backend base.py), which libsession parses to (1, YEAR) with no
    // canonicalization. So this is (1, YEAR), not (12, MONTH); otherwise current-plan matching (period
    // equality against the active backend plan) would never match this SKU.
    TWELVE_MONTHS(ProPlanPeriod(1, ProPlanUnit.YEAR), "session-pro-12-months")
}
