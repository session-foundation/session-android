package org.thoughtcrime.securesms.pro

import java.time.Duration

/**
 * The time windows the Pro refresh and the home CTAs share.
 *
 * **Cross-client contract (spec §9.3).** Desktop and iOS use the same values; they move by agreement
 * across clients or not at all, and a divergence needs saying why in the commit.
 *
 * These live here rather than inside the startup gate because the gate is not their only consumer. The
 * gate decides whether to fetch on a cold start, and the home CTAs decide whether to show — off the same
 * two windows. Tuning one of a duplicated pair leaves the gate and the CTA disagreeing about the same
 * instant, which is the one property they exist to share.
 */
object ProRefreshWindows {
    /** Minimum spacing between startup-gate fetches, persisted across processes. */
    val STARTUP_MIN_INTERVAL: Duration = Duration.ofHours(24)

    /** How long before coverage lapses the Expiring CTA may fire. Anchored at the payment date. */
    val EXPIRING_CTA: Duration = Duration.ofDays(7)

    /** How long after coverage has ended the Expired CTA may fire. Anchored at coverage end. */
    val EXPIRED_CTA: Duration = Duration.ofDays(30)
}
