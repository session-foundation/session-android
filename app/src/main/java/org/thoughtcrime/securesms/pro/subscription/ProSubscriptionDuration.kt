package org.thoughtcrime.securesms.pro.subscription

import org.thoughtcrime.securesms.util.DateUtils
import java.time.Duration
import java.time.Period
import java.time.ZonedDateTime

enum class ProSubscriptionDuration(val duration: Period) {
    ONE_MONTH(Period.ofMonths(1)),
    THREE_MONTHS(Period.ofMonths(3)),
    TWELVE_MONTHS(Period.ofMonths(12))
}

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