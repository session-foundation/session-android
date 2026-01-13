package org.thoughtcrime.securesms.preferences.compose

import android.R.attr.onClick
import android.R.attr.subtitle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.GoToChoosePlan
import org.thoughtcrime.securesms.ui.ActionRowItem
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.SwitchActionRowItem
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.util.State

@Composable
fun NotificationsPreferenceScreen() {
    BasePreferenceScreens(
        onBack = {},
        title = GetString(R.string.sessionNotifications).string()
    ) {
        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.notificationsStrategy).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.useFastMode),
                    subtitle = annotatedStringResource(R.string.notificationsFastModeDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )

                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.runAppBackground),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )

                ActionRowItem(
                    title = annotatedStringResource(R.string.notificationsGoToDevice ),
                    qaTag = R.string.qa_pro_settings_action_renew_plan,
                    onClick = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.conversationsSendWithEnterKey).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.conversationsSendWithEnterKey),
                    subtitle = annotatedStringResource(R.string.conversationsSendWithEnterKeyDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.conversationsAudioMessages).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.conversationsAutoplayAudioMessage),
                    subtitle = annotatedStringResource(R.string.conversationsAutoplayAudioMessageDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.conversationsBlockedContacts).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.conversationsBlockedContacts),
                    subtitle = annotatedStringResource(R.string.blockedContactsManageDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }
    }
}

@Preview
@Composable
fun PreviewNotificationsPreferenceScreen() {
    NotificationsPreferenceScreen()
}