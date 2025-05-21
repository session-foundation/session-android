package org.thoughtcrime.securesms.conversation.disappearingmessages.ui

import network.loki.messenger.libsession_util.util.ExpiryMode
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.OptionsCardData

typealias ExpiryOptionsCardData = OptionsCardData<ExpiryMode>

data class UiState(
    val cards: List<ExpiryOptionsCardData> = emptyList(),
    val showGroupFooter: Boolean = false,
    val showSetButton: Boolean = true,
    val subtitle: GetString? = null,
) {
    constructor(
        vararg cards: ExpiryOptionsCardData,
        showGroupFooter: Boolean = false,
        showSetButton: Boolean = true,
        subtitle: GetString? = null,
    ): this(
        cards.asList(),
        showGroupFooter,
        showSetButton,
        subtitle,
    )
}
