package org.session.libsession.utilities

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.widget.TextView
import com.squareup.phrase.Phrase as SquarePhrase

/**
 * Drop-in replacement for [com.squareup.phrase.Phrase] which never throws when a substitution
 * doesn't line up with the pattern.
 *
 * Square's implementation throws [IllegalArgumentException] both when [put] is given a key the
 * pattern doesn't contain ("Invalid key") and when [format] is reached with a key the pattern does
 * contain but nothing was bound to ("Missing keys"). Either is a crash in front of the user for
 * what is only ever a text problem, and both became far more likely once the Crowdin pipeline
 * started substituting the non-translatable constants at generation time — every `{app_name}` the
 * client still tried to fill would take the first path.
 *
 * This instead matches iOS's `LocalizationHelper`: an unknown key is ignored, and a key the
 * pattern contains but nothing filled is left in the output verbatim (`{app_name}`), so the failure
 * is visible in the UI rather than fatal.
 *
 * Formatting itself is still delegated to Square's implementation so that spans carried by the
 * resource (the `<b>`/`<font>` markup the generated `strings.xml` contains) survive substitution.
 */
class Phrase private constructor(private val pattern: CharSequence) {
    private val delegate: SquarePhrase? = try {
        SquarePhrase.from(pattern)
    } catch (e: IllegalArgumentException) {
        // The pattern isn't valid Phrase syntax (keys must be lower case a-z/`_`). Square would
        // have thrown before we could substitute anything, so fall back to plain replacement.
        null
    }
    private val boundKeys: MutableSet<String> = mutableSetOf()
    private val fallbackValues: MutableMap<String, CharSequence> = mutableMapOf()

    fun put(key: String, value: CharSequence?): Phrase {
        // A null value can't be substituted, so leave the token in place rather than guessing
        if (value == null) return this

        boundKeys.add(key)

        if (delegate == null) {
            fallbackValues[key] = value
        } else {
            // putOptional (rather than put) so a key the pattern doesn't contain is ignored
            delegate.putOptional(key, value)
        }

        return this
    }

    fun put(key: String, value: Int): Phrase = put(key, value.toString())

    fun putOptional(key: String, value: CharSequence?): Phrase = put(key, value)

    fun putOptional(key: String, value: Int): Phrase = put(key, value.toString())

    fun format(): CharSequence {
        val delegate = this.delegate ?: return formatWithoutDelegate()

        // Bind anything the pattern still expects to its own token so that `format` can't throw
        unboundKeys().forEach { delegate.putOptional(it, "{$it}") }

        return delegate.format()
    }

    fun into(target: TextView?) {
        target?.text = format()
    }

    override fun toString(): String = format().toString()

    private fun unboundKeys(): List<String> =
        KEY_PATTERN.findAll(pattern)
            .map { it.groupValues[1] }
            .distinct()
            .filterNot { boundKeys.contains(it) }
            .toList()

    private fun formatWithoutDelegate(): CharSequence =
        fallbackValues.entries.fold(pattern.toString()) { result, (key, value) ->
            result.replace("{$key}", value.toString())
        }

    companion object {
        // Matches Square's key grammar: lower case a-z plus `_`, and `{{` escapes a literal brace.
        // Note: the closing brace has to be escaped. Android matches with ICU, which rejects a bare
        // `}` as a dangling quantifier — unlike the JVM's java.util.regex, so an unescaped one
        // compiles fine under Robolectric and then throws PatternSyntaxException on a device.
        private val KEY_PATTERN = Regex("""(?<!\{)\{([a-z_][a-z_0-9]*)\}""")

        @JvmStatic
        fun from(pattern: CharSequence): Phrase = Phrase(pattern)

        @JvmStatic
        fun from(context: Context, resId: Int): Phrase = Phrase(context.getText(resId))

        @JvmStatic
        fun from(resources: Resources, resId: Int): Phrase = Phrase(resources.getText(resId))

        @JvmStatic
        fun from(view: View, resId: Int): Phrase = Phrase(view.resources.getText(resId))

        @JvmStatic
        fun fromPlural(context: Context, resId: Int, quantity: Int): Phrase =
            fromPlural(context.resources, resId, quantity)

        @JvmStatic
        fun fromPlural(resources: Resources, resId: Int, quantity: Int): Phrase =
            Phrase(resources.getQuantityText(resId, quantity))

        @JvmStatic
        fun fromPlural(view: View, resId: Int, quantity: Int): Phrase =
            fromPlural(view.resources, resId, quantity)
    }
}
