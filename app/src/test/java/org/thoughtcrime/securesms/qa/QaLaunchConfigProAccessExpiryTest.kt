package org.thoughtcrime.securesms.qa

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import network.loki.messenger.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.utilities.TextSecurePreferences
import java.time.Duration
import java.time.Instant

/**
 * Covers the `sessionProAccessExpiry` launch extra.
 *
 * Written because that path had **never run end to end**: the Appium harness did not send the key yet,
 * so its first exercise would otherwise have been inside a spec, where a parse bug reads as a product
 * bug rather than a setup failure. The wrong-unit case below is the whole reason the wire unit is
 * seconds, so it is the test that matters most here.
 *
 * Driven through [QaLaunchConfig.apply] rather than the private parser on purpose — the parse, the
 * range guard and the preference write are one contract, and testing the parser alone would not catch a
 * value that parses but is never persisted.
 */
@RunWith(RobolectricTestRunner::class)
class QaLaunchConfigProAccessExpiryTest {

    private lateinit var prefs: TextSecurePreferences
    private lateinit var snodeDirectory: SnodeDirectory

    @Before
    fun setUp() {
        // The whole class is compiled out when this is false, so every assertion below would vacuously
        // pass on a build type that doesn't opt in. Asserted rather than assumed.
        assertTrue(
            "Unit tests must run on a variant with ALLOW_QA_LAUNCH_CONFIG=true",
            BuildConfig.ALLOW_QA_LAUNCH_CONFIG
        )

        prefs = mockk(relaxed = true)
        snodeDirectory = mockk(relaxed = true)
        every { prefs.getDebugProAccessExpiry() } returns null
    }

    private fun applyExpiry(value: String) {
        QaLaunchConfig.apply(
            Intent().putExtra("sessionProAccessExpiry", value),
            prefs,
            snodeDirectory
        )
    }

    private fun capturedExpiry(): Instant? {
        val captured = slot<Instant?>()
        verify { prefs.setDebugProAccessExpiry(captureNullable(captured)) }
        return captured.captured
    }

    private fun assertRejected() {
        verify(exactly = 0) { prefs.setDebugProAccessExpiry(any()) }
    }

    @Test
    fun `absolute epoch seconds is applied`() {
        val expected = Instant.now().plus(Duration.ofDays(30)).epochSecond

        applyExpiry(expected.toString())

        assertEquals(Instant.ofEpochSecond(expected), capturedExpiry())
    }

    @Test
    fun `a past instant is expressible as epoch seconds`() {
        // There is deliberately no `-30d` form — a leading hyphen is unsafe through appium-adb — so
        // this is the ONLY way to mock an already-lapsed account. If it regresses, the expired-state
        // specs lose their only expiry control.
        val expected = Instant.now().minus(Duration.ofDays(14)).epochSecond

        applyExpiry(expected.toString())

        assertEquals(Instant.ofEpochSecond(expected), capturedExpiry())
    }

    @Test
    fun `epoch MILLISECONDS is rejected rather than silently mocking the year 58000`() {
        // The reason the wire unit is seconds. iOS sends Math.floor(Date.now() / 1000); a harness that
        // forgets the divide would land ~56,000 years out, and applying it would surface as a wrong
        // rendered date somewhere far from the cause.
        applyExpiry(System.currentTimeMillis().toString())

        assertRejected()
    }

    @Test
    fun `relative offsets are applied for every accepted unit`() {
        val cases = mapOf(
            "+30d" to Duration.ofDays(30),
            "+12h" to Duration.ofHours(12),
            "+45m" to Duration.ofMinutes(45),
            "+90s" to Duration.ofSeconds(90),
        )

        cases.forEach { (raw, offset) ->
            prefs = mockk(relaxed = true)
            every { prefs.getDebugProAccessExpiry() } returns null

            val before = Instant.now()
            applyExpiry(raw)
            val after = Instant.now()

            // Bounded rather than exact: the value is built from a clock read inside the call.
            val captured = capturedExpiry()!!
            assertTrue(
                "$raw resolved to $captured, outside [$before, $after] + $offset",
                !captured.isBefore(before.plus(offset)) && !captured.isAfter(after.plus(offset))
            )
        }
    }

    @Test
    fun `useActual clears a previously set override`() {
        every { prefs.getDebugProAccessExpiry() } returns Instant.now()

        applyExpiry("useactual")

        verify { prefs.setDebugProAccessExpiry(null) }
    }

    @Test
    fun `useActual is a no-op when no override is set`() {
        every { prefs.getDebugProAccessExpiry() } returns null

        applyExpiry("useactual")

        assertRejected()
    }

    @Test
    fun `unparseable values are rejected`() {
        // "30d" without the `+` is the plausible typo: it must not be read as 30 epoch seconds.
        listOf("30d", "+", "+d", "+30x", "later", "", "+30 d").forEach { raw ->
            prefs = mockk(relaxed = true)
            every { prefs.getDebugProAccessExpiry() } returns null

            applyExpiry(raw)

            verify(exactly = 0) { prefs.setDebugProAccessExpiry(any()) }
        }
    }

    @Test
    fun `an absent extra leaves the preference untouched`() {
        QaLaunchConfig.apply(
            Intent().putExtra("sessionProBackendStatus", "active"),
            prefs,
            snodeDirectory
        )

        assertRejected()
    }

    @Test
    fun `an out of range relative offset is rejected`() {
        applyExpiry("+9999d")

        assertRejected()
    }
}
