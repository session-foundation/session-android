package org.session.libsession.utilities

import android.content.Context
import org.session.libsession.LocalisedTimeUtil
import java.util.concurrent.TimeUnit
import kotlin.time.Duration


object ExpirationUtil {

    @JvmStatic
    fun getExpirationDisplayValue(context: Context, duration: Duration): String =
        LocalisedTimeUtil.getDurationWithSingleLargestTimeUnit(context, duration)

    fun getExpirationAbbreviatedDisplayValue(expirationTimeSecs: Long): String {
        return if (expirationTimeSecs < TimeUnit.MINUTES.toSeconds(1)) {
            expirationTimeSecs.toString() + "s"
        } else if (expirationTimeSecs < TimeUnit.HOURS.toSeconds(1)) {
            val minutes = expirationTimeSecs / TimeUnit.MINUTES.toSeconds(1)
            minutes.toString() + "m"
        } else if (expirationTimeSecs < TimeUnit.DAYS.toSeconds(1)) {
            val hours = expirationTimeSecs / TimeUnit.HOURS.toSeconds(1)
            hours.toString() + "h"
        } else if (expirationTimeSecs < TimeUnit.DAYS.toSeconds(7)) {
            val days = expirationTimeSecs / TimeUnit.DAYS.toSeconds(1)
            days.toString() + "d"
        } else {
            val weeks = expirationTimeSecs / TimeUnit.DAYS.toSeconds(7)
            weeks.toString() + "w"
        }
    }
}
