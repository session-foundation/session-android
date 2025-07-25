package org.thoughtcrime.securesms.conversation.v2.messages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewControlMessageBinding
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.CALL_NOTIFICATIONS_ENABLED
import org.session.libsession.utilities.getColorFromAttr
import org.thoughtcrime.securesms.conversation.disappearingmessages.DisappearingMessages
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.content.DisappearingMessageUpdate
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.PrivacySettingsActivity
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.ui.findActivity
import org.thoughtcrime.securesms.ui.getSubbedCharSequence
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.DateUtils
import javax.inject.Inject


@AndroidEntryPoint
class ControlMessageView : LinearLayout {

    private val TAG = "ControlMessageView"

    private val binding = ViewControlMessageBinding.inflate(LayoutInflater.from(context), this, true)

    val iconSize by lazy {
        resources.getDimensionPixelSize(R.dimen.medium_spacing)
    }

    private val infoDrawable: Drawable? by lazy {
        val icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_info, context.theme)?.toBitmap()
        if(icon != null) {
            val d = BitmapDrawable(resources, Bitmap.createScaledBitmap(icon, iconSize, iconSize, true))
            d.setTint(context.getColorFromAttr(R.attr.message_received_text_color))
            d
        } else null
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    @Inject lateinit var disappearingMessages: DisappearingMessages
    @Inject lateinit var dateUtils: DateUtils

    val controlContentView: View get() = binding.controlContentView

    init {
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
    }

    fun bind(message: MessageRecord, previous: MessageRecord?, longPress: (() -> Unit)? = null) {
        binding.dateBreakTextView.showDateBreak(message, previous, dateUtils)
        binding.iconImageView.isGone = true
        binding.expirationTimerView.isGone = true
        binding.followSetting.isGone = true
        var messageBody: CharSequence = message.getDisplayBody(context)

        binding.root.contentDescription = null
        binding.textView.text = messageBody
        val messageContent = message.messageContent
        when {
            messageContent is DisappearingMessageUpdate -> {
                binding.apply {
                    expirationTimerView.isVisible = true

                    val threadRecipient = DatabaseComponent.get(context).threadDatabase().getRecipientForThreadId(message.threadId)

                    if (threadRecipient?.isGroupRecipient == true) {
                        expirationTimerView.setTimerIcon()
                    } else {
                        expirationTimerView.setExpirationTime(message.expireStarted, message.expiresIn)
                    }

                    followSetting.isVisible = ExpirationConfiguration.isNewConfigEnabled
                            && !message.isOutgoing
                            && messageContent.expiryMode != (MessagingModuleConfiguration.shared.storage.getExpirationConfiguration(message.threadId)?.expiryMode ?: ExpiryMode.NONE)
                            && threadRecipient?.isGroupOrCommunityRecipient != true

                    if (followSetting.isVisible) {
                        binding.controlContentView.setOnClickListener {
                            disappearingMessages.showFollowSettingDialog(context, threadId = message.threadId, recipient = message.recipient, messageContent)
                        }
                    } else {
                        binding.controlContentView.setOnClickListener(null)
                    }
                }
            }

            message.isGroupExpirationTimerUpdate -> {
                binding.expirationTimerView.apply {
                    isVisible = true
                    setTimerIcon()
                }
            }

            message.isMediaSavedNotification -> {
                binding.iconImageView.apply {
                    setImageDrawable(
                        ResourcesCompat.getDrawable(resources, R.drawable.ic_arrow_down_to_line, context.theme)
                    )
                    isVisible = true
                }
            }
            message.isMessageRequestResponse -> {
                val msgRecipient = message.recipient.address.toString()
                val me = TextSecurePreferences.getLocalNumber(context)
                binding.textView.text =  if(me == msgRecipient) { // you accepted the user's request
                    val threadRecipient = DatabaseComponent.get(context).threadDatabase().getRecipientForThreadId(message.threadId)
                    context.getSubbedCharSequence(
                        R.string.messageRequestYouHaveAccepted,
                        NAME_KEY to (threadRecipient?.name ?: "")
                    )
                } else { // they accepted your request
                    context.getString(R.string.messageRequestsAccepted)
                }

                binding.root.contentDescription = context.getString(R.string.AccessibilityId_message_request_config_message)
            }
            message.isCallLog -> {
                val drawableRes = when {
                    message.isIncomingCall -> R.drawable.ic_phone_incoming
                    message.isOutgoingCall -> R.drawable.ic_phone_outgoing
                    else -> R.drawable.ic_phone_missed
                }

                // Since this is using text drawable we need to go the long way around to size and style the drawable
                // We could set the colour and style directly in the drawable's xml but it then makes it non reusable
                // This will all be simplified  once we turn this all to Compose
                val icon = ResourcesCompat.getDrawable(resources, drawableRes, context.theme)?.toBitmap()
                icon?.let{
                    val drawable = BitmapDrawable(resources, Bitmap.createScaledBitmap(icon, iconSize, iconSize, true));
                    binding.callTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        drawable,null, null, null)

                    val iconTint = when {
                        message.isIncomingCall || message.isOutgoingCall -> R.attr.message_received_text_color
                        else -> R.attr.danger
                    }

                    drawable.setTint(context.getColorFromAttr(iconTint))
                }

                binding.textView.isVisible = false
                binding.callTextView.text = messageBody

                if (message.expireStarted > 0 && message.expiresIn > 0) {
                    binding.expirationTimerView.isVisible = true
                    binding.expirationTimerView.setExpirationTime(message.expireStarted, message.expiresIn)
                }

                // remove clicks by default
                binding.controlContentView.setOnClickListener(null)
                hideInfo()

                // handle click behaviour depending on criteria
                if (message.isMissedCall || message.isFirstMissedCall) {
                    when {
                        // when the call toggle is disabled in the privacy screen,
                        // show a dedicated privacy dialog
                        !TextSecurePreferences.isCallNotificationsEnabled(context) -> {
                            showInfo()
                            binding.controlContentView.setOnClickListener {
                                context.showSessionDialog {
                                    val titleTxt = context.getSubbedString(
                                        R.string.callsMissedCallFrom,
                                        NAME_KEY to message.individualRecipient.name
                                    )
                                    title(titleTxt)

                                    val bodyTxt = context.getSubbedCharSequence(
                                        R.string.callsYouMissedCallPermissions,
                                        NAME_KEY to message.individualRecipient.name
                                    )
                                    text(bodyTxt)

                                    button(R.string.sessionSettings) {
                                        val intent = Intent(context, PrivacySettingsActivity::class.java)
                                        // allow the screen to auto scroll to the appropriate toggle
                                        intent.putExtra(PrivacySettingsActivity.SCROLL_AND_TOGGLE_KEY, CALL_NOTIFICATIONS_ENABLED)
                                        context.startActivity(intent)
                                    }
                                    cancelButton()
                                }
                            }
                        }

                        // if we're currently missing the audio/microphone permission,
                        // show a dedicated permission dialog
                        !Permissions.hasAll(context, Manifest.permission.RECORD_AUDIO) -> {
                            showInfo()
                            binding.controlContentView.setOnClickListener {
                                context.showSessionDialog {
                                    val titleTxt = context.getSubbedString(
                                        R.string.callsMissedCallFrom,
                                        NAME_KEY to message.individualRecipient.name
                                    )
                                    title(titleTxt)

                                    val bodyTxt = context.getSubbedCharSequence(
                                        R.string.callsMicrophonePermissionsRequired,
                                        NAME_KEY to message.individualRecipient.name
                                    )
                                    text(bodyTxt)

                                    button(R.string.theContinue) {
                                        Permissions.with(context.findActivity())
                                            .request(Manifest.permission.RECORD_AUDIO)
                                            .withPermanentDenialDialog(
                                                context.getSubbedString(R.string.permissionsMicrophoneAccessRequired,
                                                    APP_NAME_KEY to context.getString(R.string.app_name))
                                            )
                                            .execute()
                                    }
                                    cancelButton()
                                }
                            }
                        }
                    }
                }
            }
        }

        binding.textView.isGone = message.isCallLog
        binding.callView.isVisible = message.isCallLog

        // handle long clicked if it was passed on
        longPress?.let {
            binding.controlContentView.setOnLongClickListener {
                longPress.invoke()
                true
            }
        }
    }

    fun showInfo(){
        binding.callTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            binding.callTextView.compoundDrawablesRelative.first(),
            null,
            infoDrawable,
            null
        )
    }

    fun hideInfo(){
        binding.callTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            binding.callTextView.compoundDrawablesRelative.first(),
            null,
            null,
            null
        )
    }

    fun recycle() {

    }
}