package org.session.libsession.utilities

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The point of our [Phrase] is that a substitution which doesn't line up with the pattern is not
 * fatal, so these cover the cases where Square's implementation throws
 * `IllegalArgumentException` — an unknown key ("Invalid key"), an unfilled key ("Missing keys"),
 * a null value, and an unparseable pattern.
 *
 * Robolectric is needed because formatting builds a `SpannableStringBuilder`.
 */
@RunWith(RobolectricTestRunner::class)
class PhraseTest {
    @Test
    fun `substitutes a bound key`() {
        assertEquals(
            "Welcome to Session",
            Phrase.from("Welcome to {app_name}").put("app_name", "Session").format().toString()
        )
    }

    @Test
    fun `substitutes every occurrence of a key`() {
        assertEquals(
            "Session and Session",
            Phrase.from("{app_name} and {app_name}").put("app_name", "Session").format().toString()
        )
    }

    @Test
    fun `ignores a key the pattern does not contain`() {
        // Square throws "Invalid key: app_name" here. This is the case the Crowdin static-string
        // substitution creates: the token is already baked into the string, so nothing to fill.
        assertEquals(
            "Welcome to Session",
            Phrase.from("Welcome to Session").put("app_name", "Session").format().toString()
        )
    }

    @Test
    fun `leaves an unfilled key verbatim`() {
        // Square throws "Missing keys: name" here. Matches iOS, where the token renders as-is.
        assertEquals(
            "Hello {name}",
            Phrase.from("Hello {name}").format().toString()
        )
    }

    @Test
    fun `fills what it can and leaves the rest verbatim`() {
        assertEquals(
            "Alice invited you to {group_name}",
            Phrase.from("{name} invited you to {group_name}").put("name", "Alice").format().toString()
        )
    }

    @Test
    fun `leaves the token verbatim for a null value`() {
        assertEquals(
            "Hello {name}",
            Phrase.from("Hello {name}").put("name", null).format().toString()
        )
    }

    @Test
    fun `accepts an int value`() {
        assertEquals(
            "3 messages",
            Phrase.from("{count} messages").put("count", 3).format().toString()
        )
    }

    @Test
    fun `does not throw on a pattern that is not valid Phrase syntax`() {
        // Upper-case keys are a parse error for Square, thrown from `from` before any substitution.
        // We fall back to plain replacement, which is what iOS does for every string anyway.
        assertEquals(
            "Hello Alice",
            Phrase.from("Hello {Name}").put("Name", "Alice").format().toString()
        )
    }

    @Test
    fun `leaves an unfilled key verbatim on a pattern that is not valid Phrase syntax`() {
        assertEquals(
            "Hello {Name}",
            Phrase.from("Hello {Name}").format().toString()
        )
    }

    @Test
    fun `toString formats`() {
        assertEquals(
            "Welcome to Session",
            Phrase.from("Welcome to {app_name}").put("app_name", "Session").toString()
        )
    }
}
