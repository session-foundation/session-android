package org.thoughtcrime.securesms.pro

import android.content.Context
import network.loki.messenger.R
import org.thoughtcrime.securesms.pro.api.ProApiError

/**
 * Resolve a failed Pro backend request to a user-facing message.
 *
 * The backend sends an open-ended [ProApiError.errorCode] slug (wire spec §5.1) plus an English
 * diagnostic [ProApiError.error]. We prefer a localized `pro_error_<slug>` string when one exists
 * (a brand-new slug therefore needs only a translation entry + rebuild — no code change), and fall
 * back to the backend diagnostic, then a generic message.
 *
 *  1. localized `pro_error_<errorCode>` if that string resource exists (base English counts);
 *  2. else the backend English diagnostic [ProApiError.error];
 *  3. else [R.string.errorGeneric].
 */
fun ProApiError.userFacingMessage(context: Context): String {
    errorCode?.let { slug ->
        val resId = context.resources.getIdentifier("pro_error_$slug", "string", context.packageName)
        if (resId != 0) return context.getString(resId)
    }
    return error ?: context.getString(R.string.errorGeneric)
}
