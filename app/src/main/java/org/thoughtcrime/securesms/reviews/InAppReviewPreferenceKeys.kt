package org.thoughtcrime.securesms.reviews

import org.thoughtcrime.securesms.preferences.PreferenceKey

object InAppReviewPreferenceKeys {
    val REVIEW_STATE = PreferenceKey.json<InAppReviewState>("in_app_review_state")
    val SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW = PreferenceKey.boolean(
        "show_donation_cta_from_positive_review",
        false
    )
}
