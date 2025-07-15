package org.thoughtcrime.securesms.conversation.disappearingmessages

import android.content.Context
import androidx.annotation.StringRes
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.SSKEnvironment.MessageExpirationManagerProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.DISAPPEARING_MESSAGES_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.model.content.DisappearingMessageUpdate
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.ui.getSubbedCharSequence
import javax.inject.Inject

class DisappearingMessages @Inject constructor(
    private val textSecurePreferences: TextSecurePreferences,
    private val messageExpirationManager: MessageExpirationManagerProtocol,
    private val storage: StorageProtocol,
    private val clock: SnodeClock,
    private val groupManagerV2: GroupManagerV2
) {
    fun set(threadId: Long, address: Address, mode: ExpiryMode, isGroup: Boolean) {
        val expiryChangeTimestampMs = clock.currentTimeMills()
        storage.setExpirationConfiguration(ExpirationConfiguration(threadId, mode, expiryChangeTimestampMs))

        if (address.isGroupV2) {
            groupManagerV2.setExpirationTimer(AccountId(address.toString()), mode, expiryChangeTimestampMs)
        } else {
            val message = ExpirationTimerUpdate(isGroup = isGroup).apply {
                expiryMode = mode
                sender = textSecurePreferences.getLocalNumber()
                isSenderSelf = true
                recipient = address.toString()
                sentTimestamp = expiryChangeTimestampMs
            }

            messageExpirationManager.insertExpirationTimerMessage(message)
            MessageSender.send(message, address)
        }
    }

    fun showFollowSettingDialog(context: Context,
                                threadId: Long,
                                recipient: Recipient,
                                content: DisappearingMessageUpdate) = context.showSessionDialog {
        title(R.string.disappearingMessagesFollowSetting)

        val bodyText: CharSequence
        @StringRes
        val dangerButtonText: Int
        @StringRes
        val dangerButtonContentDescription: Int

        when (content.expiryMode) {
            ExpiryMode.NONE -> {
                bodyText = context.getText(R.string.disappearingMessagesFollowSettingOff)
                dangerButtonText = R.string.confirm
                dangerButtonContentDescription = R.string.AccessibilityId_confirm
            }
            is ExpiryMode.AfterSend -> {
                bodyText = context.getSubbedCharSequence(
                    R.string.disappearingMessagesFollowSettingOn,
                    TIME_KEY to ExpirationUtil.getExpirationDisplayValue(
                        context,
                        content.expiryMode.duration
                    ),
                    DISAPPEARING_MESSAGES_TYPE_KEY to context.getString(R.string.disappearingMessagesTypeSent)
                )

                dangerButtonText = R.string.set
                dangerButtonContentDescription = R.string.AccessibilityId_setButton
            }
            is ExpiryMode.AfterRead -> {
                bodyText = context.getSubbedCharSequence(
                    R.string.disappearingMessagesFollowSettingOn,
                    TIME_KEY to ExpirationUtil.getExpirationDisplayValue(
                        context,
                        content.expiryMode.duration
                    ),
                    DISAPPEARING_MESSAGES_TYPE_KEY to context.getString(R.string.disappearingMessagesTypeRead)
                )

                dangerButtonText = R.string.set
                dangerButtonContentDescription = R.string.AccessibilityId_setButton
            }
        }

        text(bodyText)

        dangerButton(
                text = dangerButtonText,
                contentDescriptionRes = dangerButtonContentDescription,
        ) {
            set(threadId, recipient.address, content.expiryMode, recipient.isGroupRecipient)
        }
        cancelButton()
    }
}
