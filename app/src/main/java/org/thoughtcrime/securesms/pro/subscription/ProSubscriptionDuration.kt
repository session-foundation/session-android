package org.thoughtcrime.securesms.pro.subscription

import org.thoughtcrime.securesms.util.DateUtils
import java.time.Instant
import java.time.Period
import java.time.ZoneId

enum class ProSubscriptionDuration(val duration: Period, val id: String) {
    ONE_MONTH(Period.ofMonths(1), "session-pro-1-month"),
    THREE_MONTHS(Period.ofMonths(3), "session-pro-3-months"),
    TWELVE_MONTHS(Period.ofMonths(12), "session-pro-12-months")
}

fun ProSubscriptionDuration.getById(id: String): ProSubscriptionDuration? =
    ProSubscriptionDuration.entries.find { it.id == id }
