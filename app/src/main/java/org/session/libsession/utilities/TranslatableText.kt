package org.session.libsession.utilities

import android.content.Context
import androidx.annotation.StringRes
import com.squareup.phrase.Phrase

/**
 * A class that represents a text with substitutions.
 *
 * This is mainly used for modeling the texts inside a view model so that the view model doesn't
 * need to do any Android-specific operations.
 */
data class TranslatableText(
    @param:StringRes val textResId: Int,
    val substitution: Map<StringSubKey, CharSequence>? = null
) {
    constructor(
        @StringRes textResId: Int,
        vararg substitution: Pair<StringSubKey, CharSequence>
    ): this(
        textResId,
        substitution.toMap()
    )

    fun format(context: Context): String {
        return if (substitution != null) {
            substitution
                .asSequence()
                .fold(Phrase.from(context, textResId)) { phrase, (key, value) ->
                    phrase.put(key, value)
                }
                .format()
            .toString()
        } else {
            context.getString(textResId)
        }
    }
}
