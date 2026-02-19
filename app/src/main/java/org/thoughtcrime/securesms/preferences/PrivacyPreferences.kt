package org.thoughtcrime.securesms.preferences

object PrivacyPreferences {
    val READ_RECEIPTS = PreferenceKey.boolean("pref_read_receipts", false)
    val TYPING_INDICATORS = PreferenceKey.boolean("pref_typing_indicators", false)
    val LINK_PREVIEWS = PreferenceKey.boolean("pref_link_previews", false)
    val INCOGNITO_KEYBOARD = PreferenceKey.boolean("pref_incognito_keyboard", true)
    val HAS_SEEN_LINK_PREVIEW_SUGGESTION_DIALOG = PreferenceKey.boolean("has_seen_link_preview_suggestion_dialog", false)
    val HAS_SEEN_GIF_METADATA_WARNING = PreferenceKey.boolean("has_seen_gif_metadata_warning", false)
    val HAVE_WARNED_USER_ABOUT_SAVING_ATTACHMENTS = PreferenceKey.boolean("libsession.HAVE_WARNED_USER_ABOUT_SAVING_ATTACHMENTS", false)
    val UNIVERSAL_UNIDENTIFIED_ACCESS = PreferenceKey.boolean("pref_universal_unidentified_access", false)
}
