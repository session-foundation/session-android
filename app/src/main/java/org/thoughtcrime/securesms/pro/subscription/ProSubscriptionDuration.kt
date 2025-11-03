package org.thoughtcrime.securesms.pro.subscription

import org.thoughtcrime.securesms.util.DateUtils
import java.time.Duration
import java.time.Period
import java.time.ZonedDateTime

enum class ProSubscriptionDuration(val duration: Period, val id: String) {
    ONE_MONTH(Period.ofMonths(1), "session-pro-1-month"),
    THREE_MONTHS(Period.ofMonths(3), "session-pro-3-months"),
    TWELVE_MONTHS(Period.ofMonths(12), "session-pro-12-months")
}

fun ProSubscriptionDuration.getById(id: String): ProSubscriptionDuration? =
    ProSubscriptionDuration.entries.find { it.id == id }

private val proSettingsDateFormat = "MMMM d, yyyy"

fun ProSubscriptionDuration.expiryFromNow(): String {
    val newSubscriptionExpiryDate = ZonedDateTime.now()
        .plus(duration)
        .toInstant()
        .toEpochMilli()
    return DateUtils.getLocaleFormattedDate(
        newSubscriptionExpiryDate, proSettingsDateFormat
    )
}