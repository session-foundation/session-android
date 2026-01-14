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
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.IconTextActionRowItem
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

                Divider()

                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.runAppBackground),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )

                Divider()

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
            title = GetString(R.string.notificationsStyle).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconTextActionRowItem(
                    title = annotatedStringResource(R.string.notificationsSound),
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    icon = R.drawable.ic_baseline_arrow_drop_down_24,
                    endText = annotatedStringResource("Eureka"),
                    onClick = {}
                )

                Divider()

                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.notificationsSoundDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )

                Divider()

                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.notificationsVibrate),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.notificationsContent).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {

                IconTextActionRowItem(
                    title = annotatedStringResource(R.string.notificationsContent),
                    subtitle = annotatedStringResource(R.string.notificationsContentDescription),
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    icon = R.drawable.ic_baseline_arrow_drop_down_24,
                    endText = annotatedStringResource("Name and Content"),
                    onClick = {}
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