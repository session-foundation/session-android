package org.thoughtcrime.securesms.conversation.disappearingmessages.ui

import network.loki.messenger.libsession_util.util.ExpiryMode
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.OptionsCardData

typealias ExpiryOptionsCardData = OptionsCardData<ExpiryMode>

data class UiState(
    val cards: List<ExpiryOptionsCardData> = emptyList(),
    val showGroupFooter: Boolean = false,
    val showSetButton: Boolean = true,
    val disableSetButton: Boolean = false,
    val subtitle: GetString? = null,
) {
    constructor(
        vararg cards: ExpiryOptionsCardData,
        showGroupFooter: Boolean = false,
        showSetButton: Boolean = true,
        disableSetButton: Boolean = false,
        subtitle: GetString? = null,
    ): this(
        cards = cards.asList(),
        showGroupFooter = showGroupFooter,
        showSetButton = showSetButton,
        disableSetButton = disableSetButton,
        subtitle = subtitle,
    )
}
