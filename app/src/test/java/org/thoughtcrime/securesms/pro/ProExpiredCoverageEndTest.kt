package org.thoughtcrime.securesms.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * [ProStatus.Expired.coverageEndedAt] and the 30-day Expired-CTA window measured from it.
 *
 * The backend only reports EXPIRED once coverage has ended, and coverage ends a grace period after the
 * payment-due date it sends. So a window measured from the payment date is short by exactly the grace
 * period, and empty once grace reaches the window length.
 *
 * A multi-day grace means an Apple account whose dunning ran out, since Apple states its retry window
 * separately; on Play it is only the ~1h latency allowance. The cases sweep grace as a parameter rather
 * than asserting either store's number.
 *
 * The CTA condition itself lives in `HomeViewModel`, which needs Android. What is testable here is the
 * instant it keys off.
 */
class ProExpiredCoverageEndTest {

    private val ctaWindow: Duration = Duration.ofDays(30)
    private val paymentDue: Instant = Instant.parse("2026-08-01T00:00:00Z")

    private fun expired(grace: Duration) = ProStatus.Expired(
        expiredAt = paymentDue,
        gracePeriod = grace,
        providerData = previewAppleMetaData,
    )

    /** The CTA condition from `HomeViewModel`, over the anchor this type exposes. */
    private fun ctaShows(grace: Duration, now: Instant): Boolean =
        now.isBefore(expired(grace).coverageEndedAt.plus(ctaWindow))

    @Test
    fun `coverage ends a grace period after the payment date`() {
        assertEquals(
            paymentDue.plus(Duration.ofDays(16)),
            expired(Duration.ofDays(16)).coverageEndedAt,
        )
    }

    @Test
    fun `zero grace leaves the payment date as coverage end`() {
        // Grace is 0 when not renewing, so the anchors coincide and this is a no-op for those accounts.
        assertEquals(paymentDue, expired(Duration.ZERO).coverageEndedAt)
    }

    @Test
    fun `the window is a full 30 days of coverage having ended, whatever grace was`() {
        // The property: window length is independent of grace.
        for (graceDays in listOf(0L, 1L, 16L, 29L, 45L)) {
            val grace = Duration.ofDays(graceDays)
            val coverageEnd = paymentDue.plus(grace)
            assertTrue(
                "grace=$graceDays: should show one day after coverage ended",
                ctaShows(grace, coverageEnd.plus(Duration.ofDays(1))),
            )
            assertTrue(
                "grace=$graceDays: should show at 29 days after coverage ended",
                ctaShows(grace, coverageEnd.plus(Duration.ofDays(29))),
            )
            assertFalse(
                "grace=$graceDays: should not show at 30 days after coverage ended",
                ctaShows(grace, coverageEnd.plus(ctaWindow)),
            )
        }
    }

    @Test
    fun `a grace period as long as the window does not empty it`() {
        // With 30 days of grace, `now` is already past `paymentDue + 30d` the moment EXPIRED can first
        // be reported, so the payment-date anchor yields no window at all.
        val grace = ctaWindow
        val firstMomentExpiredIsReportable = paymentDue.plus(grace).plusSeconds(1)

        assertTrue(ctaShows(grace, firstMomentExpiredIsReportable))
        assertFalse(
            "the payment-date anchor is what this test exists to rule out",
            firstMomentExpiredIsReportable.isBefore(paymentDue.plus(ctaWindow)),
        )
    }
}
