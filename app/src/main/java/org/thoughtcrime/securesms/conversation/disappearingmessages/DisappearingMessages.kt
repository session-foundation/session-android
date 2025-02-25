package org.thoughtcrime.securesms.conversation.disappearingmessages

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.SSKEnvironment.MessageExpirationManagerProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.DISAPPEARING_MESSAGES_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getExpirationTypeDisplayValue
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.ui.getSubbedCharSequence
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.ConfigurationMessageUtilities
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

class DisappearingMessages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textSecurePreferences: TextSecurePreferences,
    private val messageExpirationManager: MessageExpirationManagerProtocol,
) {
    fun set(threadId: Long, address: Address, mode: ExpiryMode, isGroup: Boolean) {
        val expiryChangeTimestampMs = SnodeAPI.nowWithOffset
        MessagingModuleConfiguration.shared.storage.setExpirationConfiguration(ExpirationConfiguration(threadId, mode, expiryChangeTimestampMs))

        val message = ExpirationTimerUpdate(isGroup = isGroup).apply {
            expiryMode = mode
            sender = textSecurePreferences.getLocalNumber()
            isSenderSelf = true
            recipient = address.serialize()
            sentTimestamp = expiryChangeTimestampMs
        }

        messageExpirationManager.insertExpirationTimerMessage(message)
        MessageSender.send(message, address)
        ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(context)
    }

    fun showFollowSettingDialog(context: Context, message: MessageRecord) = context.showSessionDialog {
        title(R.string.disappearingMessagesFollowSetting)
        text(if (message.expiresIn == 0L) {
            context.getText(R.string.disappearingMessagesFollowSettingOff)
        } else {
            context.getSubbedCharSequence(R.string.disappearingMessagesFollowSettingOn,
                TIME_KEY to ExpirationUtil.getExpirationDisplayValue(context, message.expiresIn.milliseconds),
                DISAPPEARING_MESSAGES_TYPE_KEY to context.getExpirationTypeDisplayValue(message.isNotDisappearAfterRead))
        })

        dangerButton(
                text = if (message.expiresIn == 0L) R.string.confirm else R.string.set,
                contentDescription = if (message.expiresIn == 0L) R.string.AccessibilityId_confirm else R.string.AccessibilityId_setButton
        ) {
            set(message.threadId, message.recipient.address, message.expiryMode, message.recipient.isClosedGroupRecipient)
        }
        cancelButton()
    }
}

val MessageRecord.expiryMode get() = if (expiresIn <= 0) ExpiryMode.NONE
    else if (expireStarted == timestamp) ExpiryMode.AfterSend(expiresIn / 1000)
    else ExpiryMode.AfterRead(expiresIn / 1000)
