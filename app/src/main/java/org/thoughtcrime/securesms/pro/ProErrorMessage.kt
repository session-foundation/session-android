package org.thoughtcrime.securesms.pro

import android.content.Context
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.pro.api.ProApiError

/**
 * Non-translatable brand tokens a `pro_error_<slug>` string may contain, filled at runtime.
 *
 * TODO: this conditional-substitution dance only exists because android substitutes brand tokens
 * at runtime (Phrase is strict: putting an absent token throws). Once brand tokens are baked in at
 * build time (the AUTO_REPLACE_STATIC_STRINGS migration), the strings arrive already-substituted and
 * this whole helper collapses to a plain resource lookup.
 */
private val PRO_ERROR_BRAND_SUBS = listOf(
    PRO_KEY to NonTranslatableStringConstants.PRO,
    APP_PRO_KEY to NonTranslatableStringConstants.APP_PRO,
    APP_NAME_KEY to NonTranslatableStringConstants.APP_NAME,
)

/**
 * Resolve a failed Pro backend request to a user-facing message.
 *
 * The backend sends an open-ended [ProApiError.errorCode] slug (wire spec §5.1) plus an English
 * diagnostic [ProApiError.error]. We prefer a localized `pro_error_<slug>` string when one exists
 * (a brand-new slug therefore needs only a translation entry + rebuild — no code change), and fall
 * back to the backend diagnostic, then a generic message.
 *
 *  1. localized `pro_error_<errorCode>` if that string resource exists (base English counts), with any
 *     brand tokens ({pro}/{app_pro}/{app_name}) substituted when present;
 *  2. else the backend English diagnostic [ProApiError.error];
 *  3. else [R.string.errorGeneric].
 */
fun ProApiError.userFacingMessage(context: Context): String {
    errorCode?.let { slug ->
        val resId = context.resources.getIdentifier("pro_error_$slug", "string", context.packageName)
        if (resId != 0) {
            // getIdentifier only tells us the resource exists; the format() below can still throw if the
            // string carries an arg token we don't fill — in that case fall through to the diagnostic.
            runCatching {
                val pattern = context.resources.getString(resId)
                val phrase = Phrase.from(context, resId)
                for ((key, value) in PRO_ERROR_BRAND_SUBS) {
                    if (pattern.contains("{$key}")) phrase.put(key, value)
                }
                phrase.format().toString()
            }.getOrNull()?.let { return it }
        }
    }
    return error ?: context.getString(R.string.errorGeneric)
}
