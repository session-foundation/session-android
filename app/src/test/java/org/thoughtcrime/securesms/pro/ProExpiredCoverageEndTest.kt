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
 * A multi-day grace on the wire means an Apple account whose dunning window ran out: Apple states its
 * retry window separately, so it arrives as grace. Play folds grace into the expiry it reports, so a
 * Play account's grace is only the ~1h renewal-latency allowance and the two anchors all but coincide.
 * The cases below therefore sweep grace as a parameter rather than asserting one store's number.
 *
 * These pin the anchor. The CTA condition itself lives in `HomeViewModel`, which needs Android; what
 * is testable here is the instant it keys off, and that is the part that was wrong.
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
        // The wire sends grace = 0 for an account that is not renewing, so the two anchors coincide and
        // this fix is a no-op for those accounts rather than a change.
        assertEquals(paymentDue, expired(Duration.ZERO).coverageEndedAt)
    }

    @Test
    fun `the window is a full 30 days of coverage having ended, whatever grace was`() {
        // The property that matters: window length is independent of grace. A 16-day store grace used to
        // cost 16 of the 30 days.
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
        // The reachable failure of the payment-date anchor, not a corner case: with 30 days of grace,
        // `now` is already past `paymentDue + 30d` the moment EXPIRED can first be reported, so that
        // anchor yields no window at all and the CTA could never fire.
        val grace = ctaWindow
        val firstMomentExpiredIsReportable = paymentDue.plus(grace).plusSeconds(1)

        assertTrue(ctaShows(grace, firstMomentExpiredIsReportable))
        assertFalse(
            "the payment-date anchor is what this test exists to rule out",
            firstMomentExpiredIsReportable.isBefore(paymentDue.plus(ctaWindow)),
        )
    }
}
