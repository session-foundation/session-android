package org.thoughtcrime.securesms.media

import android.content.Context
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import network.loki.messenger.R
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.RelativeDay

/**
 * A data structure that describes a series of time points in the past. It's primarily
 * used to bucket items into categories like "Today", "Yesterday", "This week", "This month", etc.
 *
 * Call [getBucketText] to get the appropriate string resource for a given time. If no bucket is
 * appropriate, it will return null.
 */
class FixedTimeBuckets(
    private val startOfToday: ZonedDateTime,
    private val startOfYesterday: ZonedDateTime,
    private val startOfThisWeek: ZonedDateTime,
    private val startOfThisMonth: ZonedDateTime
) {
    constructor(now: ZonedDateTime = ZonedDateTime.now()) : this(
        startOfToday = now.toLocalDate().atStartOfDay(now.zone),
        startOfYesterday = now.toLocalDate().minusDays(1).atStartOfDay(now.zone),
        startOfThisWeek = now.toLocalDate()
            .with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
            .atStartOfDay(now.zone),
        startOfThisMonth = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.zone)
    )

    /**
     * Test the given time against the buckets and return the appropriate string the time
     * bucket. If no bucket is appropriate, it will return null.
     */
    fun getBucketText(context: Context, dateUtils: DateUtils, time: ZonedDateTime): String? {
        return when {
            time >= startOfToday     -> dateUtils.getLocalisedRelativeDayString(RelativeDay.TODAY)
            time >= startOfYesterday -> dateUtils.getLocalisedRelativeDayString(RelativeDay.YESTERDAY)
            time >= startOfThisWeek  -> context.getString(R.string.attachmentsThisWeek)
            time >= startOfThisMonth -> context.getString(R.string.attachmentsThisMonth)
            else -> null
        }
    }
}