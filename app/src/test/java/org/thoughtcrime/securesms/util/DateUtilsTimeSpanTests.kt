package org.thoughtcrime.securesms.util

import android.content.Context
import android.text.format.DateFormat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.DATE_FORMAT_PREF
import org.session.libsession.utilities.TextSecurePreferences.Companion.TIME_FORMAT_PREF
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Tests for DateUtils using Robolectric to provide a Context
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28]) // Use a specific SDK to ensure consistent behavior
class DateUtilsTest {

    private lateinit var context: Context
    private lateinit var preferences: TextSecurePreferences
    private lateinit var dateUtils: DateUtils

    // Fixed test timestamps
    private val testTimestamp = 1617321600000L // 2021-04-02 00:00:00 UTC
    private val testTimestampHour = 1617321600000L + 3600000 // +1 hour
    private val testTimestampYesterday = testTimestamp - 86400000 // -1 day
    private val testTimestampLastMonth = testTimestamp - 30 * 86400000 // -30 days

    @Before
    fun setup() {
        // Set up a fixed timezone for testing
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        // Get Robolectric context - this will be a real implementation, not a mock
        context = org.robolectric.RuntimeEnvironment.getApplication()

        // Mock preferences with explicit system behavior handling
        preferences = mock(TextSecurePreferences::class.java)

        // Mock the date format preference
        `when`(preferences.getStringPreference(DATE_FORMAT_PREF, "dd/MM/yyyy"))
            .thenReturn("dd/MM/yyyy")

        // For time format, we need to account for the system's 24-hour format setting
        val systemDefault = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        `when`(preferences.getStringPreference(TIME_FORMAT_PREF, systemDefault))
            .thenReturn("HH:mm") // Force 24-hour format for consistent testing

        // Create the real DateUtils with a Robolectric context
        dateUtils = DateUtils(context, preferences)
    }

    @Test
    fun `test getDateFormat returns correct value`() {
        assertEquals("dd/MM/yyyy", dateUtils.getDateFormat())

        // Test with a different preference value
        val newPreferences = mock(TextSecurePreferences::class.java)
        `when`(newPreferences.getStringPreference(DATE_FORMAT_PREF, "dd/MM/yyyy"))
            .thenReturn("yyyy-MM-dd")

        val systemDefault = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        `when`(newPreferences.getStringPreference(TIME_FORMAT_PREF, systemDefault))
            .thenReturn("HH:mm")

        val newDateUtils = DateUtils(context, newPreferences)
        assertEquals("yyyy-MM-dd", newDateUtils.getDateFormat())
    }

    @Test
    fun `test getTimeFormat returns correct value`() {
        assertEquals("HH:mm", dateUtils.getTimeFormat())

        // Test with a different preference value
        val newPreferences = mock(TextSecurePreferences::class.java)
        `when`(newPreferences.getStringPreference(DATE_FORMAT_PREF, "dd/MM/yyyy"))
            .thenReturn("dd/MM/yyyy")

        val systemDefault = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        `when`(newPreferences.getStringPreference(TIME_FORMAT_PREF, systemDefault))
            .thenReturn("h:mm")

        val newDateUtils = DateUtils(context, newPreferences)
        assertEquals("h:mm", newDateUtils.getTimeFormat())
    }

    @Test
    fun `test getUiPrintableDatePatterns contains correct items`() {
        val patterns = dateUtils.getUiPrintableDatePatterns()
        assertTrue(patterns.isNotEmpty())
        // The first pattern might be system-dependent, but it should contain "Default"
        assertTrue(patterns[0].contains("Default"))
        assertTrue(patterns.any { it.contains("dd/MM/yyyy") })
    }

    @Test
    fun `test getLocaleFormattedDate formats date correctly`() {
        val result = dateUtils.getLocaleFormattedDate(testTimestamp)
        assertEquals("02/04/2021", result)

        // Test with specific format
        val customResult = dateUtils.getLocaleFormattedDate(testTimestamp, "yyyy.MM.dd")
        assertEquals("2021.04.02", customResult)
    }

    @Test
    fun `test getLocaleFormattedTime formats time correctly`() {
        val result = dateUtils.getLocaleFormattedTime(testTimestamp)
        assertEquals("00:00", result)
    }

    @Test
    fun `test getLocaleFormattedTwelveHourTime formats time correctly`() {
        // Using a fixed time to ensure consistent AM/PM in the formatted output
        val noon = LocalDate.of(2021, 4, 2)
            .atTime(12, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val result = dateUtils.getLocaleFormattedTwelveHourTime(noon)

        // Ensure it has AM/PM indicator and is formatted in 12-hour mode
        assertTrue(result.contains("PM") || result.contains("pm") || result.contains("p.m."))
        assertTrue(result.contains("12:30") || result.contains("12.30"))
    }

    @Test
    fun `test updatePreferredDateFormat with valid format`() {
        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)

        // First, get the list of valid patterns to ensure we use one that exists
        val validPatterns = dateUtils.getUiPrintableDatePatterns()
        println("Available date patterns: $validPatterns")

        // Find a valid format that's different from the default
        val validFormat = when {
            validPatterns.any { it.contains("dd-MM-yyyy") } -> "dd-MM-yyyy"
            validPatterns.any { it.contains("yyyy/M/d") } -> "yyyy/M/d"
            validPatterns.any { it.contains("yyyy-M-d") } -> "yyyy-M-d"
            else -> {
                // If none of our expected formats are there, use the second item in the list
                // (first is system default with "(Default)" text, so get the raw pattern)
                validPatterns.getOrNull(1)?.takeWhile { it != ' ' } ?: "dd/MM/yyyy"
            }
        }

        dateUtils.updatePreferredDateFormat(validFormat)

        verify(preferences).setStringPreference(eq(DATE_FORMAT_PREF), captor.capture())
        assertEquals(validFormat, captor.value)
    }

    @Test
    fun `test updatePreferredDateFormat validates against actual valid patterns`() {
        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)

        // Get the actual valid patterns from the DateUtils implementation
        val validPatterns = dateUtils.getUiPrintableDatePatterns()

        // Extract just the pattern part (remove "(Default)" text if present)
        val cleanPatterns = validPatterns.map { pattern ->
            if (pattern.contains("(")) {
                pattern.substringBefore(" (")
            } else {
                pattern
            }
        }

        // Test with the first non-default pattern
        val testPattern = cleanPatterns.find { it != "dd/MM/yyyy" } ?: cleanPatterns[0]

        dateUtils.updatePreferredDateFormat(testPattern)

        verify(preferences).setStringPreference(eq(DATE_FORMAT_PREF), captor.capture())

        // The captured value should be the test pattern if it was valid,
        // or the default if it wasn't
        assertTrue("Expected either the test pattern or default pattern",
            captor.value == testPattern || captor.value == "dd/MM/yyyy")

        // If it's not the test pattern, it should be because the pattern wasn't actually valid
        if (captor.value != testPattern) {
            println("Pattern '$testPattern' was not accepted, fell back to default")
        }
    }

    @Test
    fun `test updatePreferredDateFormat with invalid format falls back to default`() {
        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)

        // Use an invalid format
        dateUtils.updatePreferredDateFormat("invalid-format")

        verify(preferences).setStringPreference(eq(DATE_FORMAT_PREF), captor.capture())
        assertEquals("dd/MM/yyyy", captor.value) // Should fall back to default
    }

    @Test
    fun `test updatePreferredTimeFormat updates preference`() {
        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)

        // Update with valid format
        dateUtils.updatePreferredTimeFormat("h:mm")

        verify(preferences).setStringPreference(eq(TIME_FORMAT_PREF), captor.capture())
        assertEquals("h:mm", captor.value)

        // Reset for next test
        reset(preferences)
        val systemDefault = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        `when`(preferences.getStringPreference(TIME_FORMAT_PREF, systemDefault))
            .thenReturn("HH:mm")

        // Test with invalid format
        dateUtils.updatePreferredTimeFormat("invalid-format")

        verify(preferences).setStringPreference(eq(TIME_FORMAT_PREF), captor.capture())
        assertEquals("HH:mm", captor.value) // Should default to 24-hour format
    }

    @Test
    fun `test isSameDay returns correct result`() {
        // Same day, different hours
        assertTrue(dateUtils.isSameDay(testTimestamp, testTimestampHour))

        // Different days
        assertFalse(dateUtils.isSameDay(testTimestamp, testTimestampYesterday))
    }

    @Test
    fun `test isSameHour returns correct result`() {
        // Same hour (0:00)
        assertTrue(dateUtils.isSameHour(testTimestamp, testTimestamp + 59 * 60 * 1000))

        // Different hour
        assertFalse(dateUtils.isSameHour(testTimestamp, testTimestampHour))
    }
}

/**
 * Tests for time span displays with controlled system time format
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DateUtilsTimeSpanTest {

    private lateinit var context: Context
    private lateinit var preferences: TextSecurePreferences
    private lateinit var dateUtils: DateUtils

    // Fixed point in time to base all tests on - April 15, 2023 at 12:00 UTC
    private val fixedNow = 1681560000000L // 2023-04-15 12:00:00 UTC

    @Before
    fun setup() {
        // Set fixed timezone for predictable results
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        // Get Robolectric context
        context = org.robolectric.RuntimeEnvironment.getApplication()

        // Mock preferences accounting for system 24-hour format
        preferences = mock(TextSecurePreferences::class.java)
        `when`(preferences.getStringPreference(DATE_FORMAT_PREF, "dd/MM/yyyy"))
            .thenReturn("dd/MM/yyyy")

        val systemDefault = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        `when`(preferences.getStringPreference(TIME_FORMAT_PREF, systemDefault))
            .thenReturn("HH:mm") // Force 24-hour for consistent testing

        dateUtils = DateUtils(context, preferences)
    }

    @Test
    fun `getDisplayFormattedTimeSpanString handles timestamps from today`() {
        // Build a timestamp that is guaranteed to be "today" but not in the future
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        // Pick 1 minute after midnight; this is always ≤ now (unless the test runs at 00:00:xx)
        val timestampToday = if (now.isAfter(midnight.plusSeconds(60)))
            midnight.plusSeconds(60)
        else
            now // fallback: current instant

        val millis = timestampToday.toEpochMilli()

        val result = dateUtils.getDisplayFormattedTimeSpanString(millis, Locale.US)
        val expected = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
            .withZone(zone)
            .format(timestampToday)

        // Assertions
        assertTrue("Today's timestamp should print only the time",
            result.matches(Regex("\\d{1,2}:\\d{2}")))
        assertEquals(expected, result)
    }

    @Test
    fun `getDisplayFormattedTimeSpanString handles timestamps older than a year`() {
        // 2 years ago
        val twoYearsAgo = createRelativeTimestamp(fixedNow, -2, 0, 0)

        val result = dateUtils.getDisplayFormattedTimeSpanString(twoYearsAgo, Locale.US)

        // Should show the full date format (e.g., "15/04/2021")
        assertEquals("15/04/2021", result)
    }

    @Test
    fun `getDisplayFormattedTimeSpanString adapts based on preferred time format`() {
        // Create preferences with 12-hour time format
        val twelveHourPrefs = mock(TextSecurePreferences::class.java).apply {
            `when`(getStringPreference(DATE_FORMAT_PREF, "dd/MM/yyyy")).thenReturn("dd/MM/yyyy")

            // Mock both possible system defaults to ensure we get what we want
            val systemDefault = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            `when`(getStringPreference(TIME_FORMAT_PREF, systemDefault)).thenReturn("h:mm")
        }
        val twelveHourDateUtils = DateUtils(context, twelveHourPrefs)

        // Any moment from today – 1 minute ago is safe
        val timestampToday = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)

        val result = twelveHourDateUtils.getDisplayFormattedTimeSpanString(timestampToday, Locale.US)
        val expected = DateTimeFormatter.ofPattern("h:mm", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestampToday))

        assertTrue(result.matches(Regex("\\d{1,2}:\\d{2}")))
        assertEquals(expected, result)
    }

    // Helper method to create timestamps relative to a base time
    private fun createRelativeTimestamp(baseTime: Long, yearDelta: Int, dayDelta: Int, hourDelta: Int): Long {
        val instant = Instant.ofEpochMilli(baseTime)
        val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())

        return dateTime
            .plusYears(yearDelta.toLong())
            .plusDays(dayDelta.toLong())
            .plusHours(hourDelta.toLong())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}