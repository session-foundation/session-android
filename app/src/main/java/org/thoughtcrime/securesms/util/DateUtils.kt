package org.thoughtcrime.securesms.util

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.DATE_FORMAT_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.TIME_FORMAT_PREF
import org.session.libsignal.utilities.Log
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import android.text.format.DateUtils as AndroidxDateUtils

enum class RelativeDay { TODAY, YESTERDAY, TOMORROW }

/**
 * Utility methods to help display dates in a user-friendly way.
 */
@Singleton
class DateUtils @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textSecurePreferences: TextSecurePreferences
) {
    private val tag = "DateUtils"

    // Default formats
    private val defaultDateFormat = "dd/MM/yyyy"
    private val defaultTimeFormat = "HH:mm"
    private val twelveHourFormat = "h:mm a"
    private val defaultDateTimeFormat = "d MMM YYYY hh:mm a"

    private val messageDateTimeFormat = "h:mm a EEE, MM/dd/yyyy"

    // System defaults and patterns
    private val systemDefaultPattern by lazy {
        DateFormat.getBestDateTimePattern(Locale.getDefault(), "yyyyMMdd")
    }

    private val validDatePatterns by lazy {
        listOf(
            systemDefaultPattern,
            "M/d/yy", "d/M/yy", "dd/MM/yyyy", "dd.MM.yyyy",
            "dd-MM-yyyy", "yyyy/M/d", "yyyy.M.d", "yyyy-M-d"
        )
    }

    private val validTimePatterns = listOf("HH:mm", "h:mm")

    // User preferences with property accessors
    private var userDateFormat: String
        get() = textSecurePreferences.getStringPreference(DATE_FORMAT_PREF, defaultDateFormat)!!
        private set(value) {
            textSecurePreferences.setStringPreference(DATE_FORMAT_PREF, value)
        }

    // The user time format is the one chosen by the user,if they chose one from the ui (not yet available but coming later)
    // Or we check for the system preference setting for 12 vs 24h format
    private var userTimeFormat: String
        get() = textSecurePreferences.getStringPreference(
            TIME_FORMAT_PREF,
            if (DateFormat.is24HourFormat(context)) defaultTimeFormat else twelveHourFormat
        )!!
        private set(value) {
            textSecurePreferences.setStringPreference(TIME_FORMAT_PREF, value)
        }

    // Public getters
    fun getDateFormat(): String = userDateFormat
    fun getTimeFormat(): String = userTimeFormat

    // TODO: This is presently unused but it WILL be used when we tie the ability to choose a date format into the UI for SES-360
    fun getUiPrintableDatePatterns(): List<String> =
        validDatePatterns.mapIndexed { index, pattern ->
            if (index == 0) "$pattern (${context.getString(R.string.theDefault)})" else pattern
        }

    // TODO: This is presently unused but it WILL be used when we tie the ability to choose a time format into the UI for SES-360
    fun getUiPrintableTimePatterns(): List<String> =
        validTimePatterns.mapIndexed { index, pattern ->
            if (index == 0) "$pattern (${context.getString(R.string.theDefault)})" else pattern
        }

    // Method to get the String for a relative day in a locale-aware fashion
    fun getLocalisedRelativeDayString(relativeDay: RelativeDay): String {
        val now = System.currentTimeMillis()

        // To compare a time to 'now' we need to get a date relative to it, so plus or minus a day, or not
        val offset = when (relativeDay) {
            RelativeDay.TOMORROW -> 1
            RelativeDay.YESTERDAY -> -1
            else -> 0 // Today
        }

        val comparisonTime = now + TimeUnit.DAYS.toMillis(offset.toLong())

        return AndroidxDateUtils.getRelativeTimeSpanString(
            comparisonTime,
            now,
            AndroidxDateUtils.DAY_IN_MILLIS,
            AndroidxDateUtils.FORMAT_SHOW_DATE
        ).toString()
    }

    // Format a given timestamp with a specific pattern
    fun formatTime(timestamp: Long, pattern: String, locale: Locale = Locale.getDefault()): String {
        val formatter = DateTimeFormatter.ofPattern(pattern, locale)

        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }

    fun getLocaleFormattedDateTime(timestamp: Long): String =
        formatTime(timestamp, defaultDateTimeFormat)

    // Method to get a date in a locale-aware fashion or with a specific pattern
    fun getLocaleFormattedDate(timestamp: Long, specificPattern: String = ""): String =
        formatTime(timestamp, specificPattern.takeIf { it.isNotEmpty() } ?: userDateFormat)

    // Method to get a time in a locale-aware fashion (i.e., 13:25 or 1:25 PM)
    fun getLocaleFormattedTime(timestamp: Long): String =
        formatTime(timestamp, userTimeFormat)

    // Method to get a time in a forced 12-hour format (e.g., 1:25 PM rather than 13:25)
    fun getLocaleFormattedTwelveHourTime(timestamp: Long): String =
        formatTime(timestamp, twelveHourFormat)

    // TODO: While currently unused, this will be tied into the UI when the user can adjust their preferred date format
    fun updatePreferredDateFormat(dateFormatPattern: String) {
        userDateFormat = if (dateFormatPattern in validDatePatterns) {
            dateFormatPattern
        } else {
            Log.w(tag, "Asked to set invalid date format pattern: $dateFormatPattern - using default instead.")
            defaultDateFormat
        }
    }

    // TODO: While currently unused, this will be tied into the UI when the user can adjust their preferred time format
    fun updatePreferredTimeFormat(timeFormatPattern: String) {
        userTimeFormat = if (timeFormatPattern in validTimePatterns) {
            timeFormatPattern
        } else {
            Log.w(tag, "Asked to set invalid time format pattern: $timeFormatPattern - using default instead.")
            defaultTimeFormat
        }
    }

    // Note: Date patterns are in TR-35 format.
    // See: https://www.unicode.org/reports/tr35/tr35-dates.html#Date_Format_Patterns
    fun getDisplayFormattedTimeSpanString(timestamp: Long, locale: Locale = Locale.getDefault()): String =
        when {
            // If it's within the last 24 hours we just give the time in 24-hour format, such as "13:27" for 1:27pm
            isToday(timestamp) -> formatTime(timestamp, userTimeFormat, locale)

            // If it's within the last week we give the day as 3 letters then the time in 24-hour format, such as "Fri 13:27" for Friday 1:27pm
            isWithinDays(timestamp, 7) -> formatTime(timestamp, "EEE $userTimeFormat", locale)

            // If it's within the last year we give the month as 3 letters then the time in 24-hour format, such as "Mar 13:27" for March 1:27pm
            // CAREFUL: "MMM d + getHourFormat(c)" actually turns out to be "8 July, 17:14" etc. - it is DAY-NUMBER and then MONTH (which can go up to 4 chars) - and THEN the time. Wild.
            isWithinDays(timestamp, 365) -> formatTime(timestamp, "MMM d $userTimeFormat", locale)

            // NOTE: The `userDateFormat` is ONLY ever used on dates which exceed one year!
            // See the Figma linked in ticket SES-360 for details.
            else -> formatTime(timestamp, userDateFormat, locale)
        }

    fun getMediumDateTimeFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern(defaultDateTimeFormat)

    fun getMessageDateTimeFormattedString(timestamp: Long): String = getLocaleFormattedDate(timestamp, messageDateTimeFormat)

    // Method to get the String for a relative day in a locale-aware fashion, including using the
    // auto-localised words for "today" and "yesterday" as appropriate.
    fun getRelativeDate(locale: Locale, timestamp: Long): String =
        when {
            isToday(timestamp) -> getLocalisedRelativeDayString(RelativeDay.TODAY)
            isYesterday(timestamp) -> getLocalisedRelativeDayString(RelativeDay.YESTERDAY)
            else -> formatTime(timestamp, userDateFormat, locale)
        }

    fun isSameDay(t1: Long, t2: Long): Boolean {
        val date1 = toLocalDate(t1)
        val date2 = toLocalDate(t2)
        return date1 == date2
    }

    fun isSameHour(t1: Long, t2: Long): Boolean {
        val date1 = toLocalDateTime(t1)
        val date2 = toLocalDateTime(t2)
        return date1.year == date2.year &&
                date1.month == date2.month &&
                date1.dayOfMonth == date2.dayOfMonth &&
                date1.hour == date2.hour
    }

    fun getExpiryString(instant: Instant?): String {
        val now = Instant.now()
        val timeRemaining = Duration.between(now, instant)

        // Instant has passed
        if (timeRemaining.isNegative || timeRemaining.isZero) {
            return context.getString(R.string.proExpired)
        }

        val totalHours = max(timeRemaining.toHours(), 1)
        val locale = context.resources.configuration.locales[0]
        val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)

        return if (totalHours >= 24) {
            // More than one full day remaining - show days
            val days = timeRemaining.toDays()
            format.format(Measure(days, MeasureUnit.DAY))
        } else {
            // Less than 24 hours remaining - show hours
            format.format(Measure(totalHours, MeasureUnit.HOUR))
        }
    }

    fun getLocalisedTimeDuration(amount: Int, unit: MeasureUnit): String {
        val locale = context.resources.configuration.locales[0]
        val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
        return format.format(Measure(amount, unit))
    }

    // Helper methods
    private fun toLocalDate(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun toLocalDateTime(timestamp: Long): LocalDateTime =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()

    private fun isToday(timestamp: Long): Boolean =
        toLocalDate(timestamp) == LocalDate.now()

    private fun isYesterday(timestamp: Long): Boolean =
        toLocalDate(timestamp) == LocalDate.now().minusDays(1)

    private fun isWithinDays(timestamp: Long, days: Long): Boolean =
        System.currentTimeMillis() - timestamp <= TimeUnit.DAYS.toMillis(days)

    private fun getLocalizedPattern(template: String, locale: Locale): String =
        DateFormat.getBestDateTimePattern(locale, template)

    companion object {
        fun Long.asEpochSeconds(): ZonedDateTime? {
            if (this <= 0) return null

            return Instant.ofEpochSecond(this).atZone(ZoneId.of("UTC"))
        }

        fun Long.secondsToInstant(): Instant? {
            if (this <= 0) return null

            return Instant.ofEpochSecond(this)
        }

        fun Long.millsToInstant(): Instant? {
            if (this <= 0) return null

            return Instant.ofEpochMilli(this)
        }

        fun Long.asEpochMillis(): ZonedDateTime? {
            if (this <= 0) return null

            return Instant.ofEpochMilli(this).atZone(ZoneId.of("UTC"))
        }

        fun Instant.toEpochSeconds(): Long {
            return this.toEpochMilli() / 1000
        }
    }
}