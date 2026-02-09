package org.thoughtcrime.securesms.util

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IP2CountryTest {
    @get:Rule
    val logRule = MockLoggingRule()

    private val ip2Country = IP2Country(InstrumentationRegistry.getInstrumentation().context)

    @Test
    fun getCountryNamesCache() = runTest {
        for ((ip, country) in data()) {
            assertEquals(country, ip2Country.lookupCountry(ip))
        }
    }

    companion object {
        fun data(): Collection<Pair<String, String>> = listOf(
            "223.121.64.0" to "Hong Kong",
            "223.121.64.1" to "Hong Kong",
            "223.121.127.0" to "Hong Kong",
            "223.121.128.0" to "China",
            "223.121.129.0" to "China",
            "223.122.0.0" to "Hong Kong",
            "223.123.0.0" to "Pakistan",
            "223.123.128.0" to "China",
            "223.124.0.0" to "China",
            "223.128.0.0" to "China",
            "223.130.0.0" to "Singapore",
        )
    }
}
