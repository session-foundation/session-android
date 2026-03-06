package org.thoughtcrime.securesms.notifications

import androidx.annotation.StringRes
import network.loki.messenger.R

enum class NotificationPrivacy(@get:StringRes val titleRes: Int) {
    ShowNameAndContent(R.string.notificationsContentShowNameAndContent),
    ShowNameOnly(R.string.notificationsContentShowNameOnly),
    ShowNothing(R.string.notificationsContentShowNoNameOrContent),
}
