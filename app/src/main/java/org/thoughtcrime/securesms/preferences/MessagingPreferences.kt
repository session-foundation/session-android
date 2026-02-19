package org.thoughtcrime.securesms.preferences

import kotlinx.serialization.Serializable
import org.session.libsession.messaging.file_server.FileServer

object MessagingPreferences {
    val AUTOPLAY_AUDIO_MESSAGES = PreferenceKey.boolean("pref_autoplay_audio", false)
    val SEND_WITH_ENTER = PreferenceKey.boolean("pref_enter_sends", false)
    val THREAD_TRIM_ENABLED = PreferenceKey.boolean("pref_trim_threads", true)
    val CONFIGURATION_SYNCED = PreferenceKey.boolean("pref_configuration_synced", false)
    val GIF_SEARCH_IN_GRID_LAYOUT = PreferenceKey.boolean("pref_gif_grid_layout", false)
    val MESSAGE_BODY_TEXT_SIZE = PreferenceKey.string("pref_message_body_text_size", "16")
    val HAS_HIDDEN_MESSAGE_REQUESTS = PreferenceKey.boolean("pref_message_requests_hidden", false)
    val FORCED_SHORT_TTL = PreferenceKey.boolean("forced_short_ttl", false)
    val FORCES_DETERMINISTIC_ATTACHMENT_ENCRYPTION = PreferenceKey.boolean("forces_deterministic_attachment_upload", false)
    val DEBUG_AVATAR_REUPLOAD = PreferenceKey.boolean("debug_avatar_reupload", false)
    val ALTERNATIVE_FILE_SERVER = PreferenceKey.json<FileServer>("alternative_file_server")
}
