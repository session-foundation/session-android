package org.thoughtcrime.securesms.pro.subscription

import java.time.Duration
import java.time.Period

enum class ProSubscriptionDuration(val duration: Period) {
    ONE_MONTH(Period.ofMonths(1)),
    THREE_MONTHS(Period.ofMonths(3)),
    TWELVE_MONTHS(Period.ofMonths(12))
}