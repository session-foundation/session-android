package org.thoughtcrime.securesms.conversation.v2.settings.notification

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.LocalisedTimeUtil
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_TIME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_LARGE_KEY
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.OptionsCardData
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel(assistedFactory = NotificationSettingsViewModel.Factory::class)
class NotificationSettingsViewModel @AssistedInject constructor(
    @Assisted private val address: Address,
    @param:ApplicationContext private val context: Context,
    private val recipientDatabase: RecipientSettingsDatabase,
    private val dateUtils: DateUtils,
    private val recipientRepository: RecipientRepository,
) : ViewModel() {
    private var thread: Recipient? = null

    private val durationForever: Long = Long.MAX_VALUE

    // the options the user is currently using
    private var currentOption: NotificationType = NotificationType.All
    private var currentMutedUntil: Long? = null

    // the option selected on this screen
    private var selectedOption: NotificationType = NotificationType.All
    private var selectedMuteDuration: Long? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        // update data when we have a recipient and update when there are changes from the thread or recipient
        viewModelScope.launch(Dispatchers.Default) {
            recipientRepository.observeRecipient(address).collectLatest {
                thread = it

                // update the user's current choice of notification
                currentMutedUntil = if(it?.isMuted() == true) it.mutedUntilMills else null
                val hasMutedUntil = currentMutedUntil != null && currentMutedUntil!! > 0L

                currentOption = when{
                    hasMutedUntil -> NotificationType.Mute
                    it?.notifyType == NotifyType.MENTIONS -> NotificationType.MentionsOnly
                    else -> NotificationType.All
                }

                // set our default selection to those
                selectedOption = currentOption
                // default selection for mute is either our custom "Muted Until" or "Forever" if nothing is pre picked
                selectedMuteDuration = if(hasMutedUntil) currentMutedUntil else durationForever

                updateState()
            }
        }
    }

    private fun updateState(){
        // start with the default options
        val defaultOptions = OptionsCardData(
            title = null,
            options = listOf(
                // All
                RadioOption(
                    value = NotificationType.All,
                    title = GetString(R.string.notificationsAllMessages),
                    iconRes = R.drawable.ic_volume_2,
                    qaTag = GetString(R.string.qa_conversation_settings_notifications_radio_all),
                    selected = selectedOption is NotificationType.All
                ),
                // Mentions Only
                RadioOption(
                    value = NotificationType.MentionsOnly,
                    title = GetString(R.string.notificationsMentionsOnly),
                    iconRes = R.drawable.ic_at_sign,
                    qaTag = GetString(R.string.qa_conversation_settings_notifications_radio_mentions),
                    selected = selectedOption is NotificationType.MentionsOnly
                ),
                // Mute
                RadioOption(
                    value = NotificationType.Mute,
                    title = GetString(R.string.notificationsMute),
                    iconRes = R.drawable.ic_volume_off,
                    qaTag = GetString(R.string.qa_conversation_settings_notifications_radio_mute),
                    selected = selectedOption is NotificationType.Mute
                ),
            )
        )

        var muteOptions: OptionsCardData<Long>? = null

        // add the mute options if necessary
        if(selectedOption is NotificationType.Mute) {
            val muteRadioOptions = mutableListOf<RadioOption<Long>>()

            // if the user is currently "muting until", and that muting is not forever,
            // then add a new option that specifies how much longer the mute  is on for
            if(currentMutedUntil != null && currentMutedUntil!! > 0L &&
                currentMutedUntil!! < System.currentTimeMillis() + TimeUnit.DAYS.toMillis(14)){ // more than two weeks from now means forever
                val title = Phrase.from(context.getString(R.string.notificationsMutedForTime))
                    .put(DATE_TIME_KEY, formatTime(currentMutedUntil!!))
                    .format().toString()
                muteRadioOptions.add(
                    RadioOption(
                        value = currentMutedUntil!!,
                        title = GetString(title),
                        qaTag = GetString(R.string.qa_conversation_settings_notifications_radio_muted_until),
                        selected = selectedMuteDuration == currentMutedUntil
                    )
                )
            }

            // add debug options on non prod builds
            if (BuildConfig.BUILD_TYPE != "release") {
                muteRadioOptions.addAll(
                    debugMuteDurations.map {
                        RadioOption(
                            value = it.first,
                            title = GetString(
                                LocalisedTimeUtil.getDurationWithSingleLargestTimeUnit(
                                    context,
                                    it.first.milliseconds
                                )
                            ),
                            subtitle = GetString("For testing purposes"),
                            qaTag = GetString(it.second),
                            selected = selectedMuteDuration == it.first
                        )
                    }
                )
            }

            // add the regular options
            muteRadioOptions.addAll(
                muteDurations.map {
                    RadioOption(
                        value = it.first,
                        title =
                            if(it.first == durationForever) GetString(R.string.forever)
                            else GetString(
                                LocalisedTimeUtil.getDurationWithSingleLargestTimeUnit(
                                    context,
                                    it.first.milliseconds
                                )
                            ),
                        qaTag = GetString(it.second),
                        selected = selectedMuteDuration == it.first
                    )
                }
            )

            muteOptions = OptionsCardData(
                    title = GetString(R.string.disappearingMessagesTimer),
                    options = muteRadioOptions
                )
        }

        _uiState.update {
            UiState(
                notificationTypes = defaultOptions,
                muteTypes = muteOptions,
                enableButton = shouldEnableSetButton()
            )
        }
    }

    private fun formatTime(timestamp: Long): String{
        return dateUtils.formatTime(timestamp, "HH:mm dd/MM/yy")
    }

    private fun shouldEnableSetButton(): Boolean {
        return when{
            selectedOption is NotificationType.Mute -> selectedMuteDuration != currentMutedUntil
            else -> selectedOption != currentOption
        }
    }

    suspend fun onSetClicked()  {
        when(selectedOption){
            is NotificationType.All, is NotificationType.MentionsOnly -> {
                unmute()
                setNotifyType(selectedOption.notifyType)
             }

            else -> {
                val muteDuration = selectedMuteDuration ?: return

                mute(if(muteDuration == durationForever) muteDuration else System.currentTimeMillis() + muteDuration)

                // also show a toast in this case
                val toastString = if(muteDuration == durationForever) {
                    context.getString(R.string.notificationsMuted)
                } else {
                    context.getSubbedString(
                        R.string.notificationsMutedFor,
                        TIME_LARGE_KEY to LocalisedTimeUtil.getDurationWithSingleLargestTimeUnit(
                            context,
                            muteDuration.milliseconds
                        )
                    )
                }

                Toast.makeText(context, toastString, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun onOptionSelected(value: Any) {
        when(value){
            is Long -> selectedMuteDuration = value

            is NotificationType -> selectedOption = value
        }

        updateState()
    }

    private suspend fun unmute() {
        val conversation = thread ?: return
        withContext(Dispatchers.Default) {
            recipientDatabase.save(conversation.address) {
                it.copy(muteUntil = null)
            }
        }
    }

    private suspend fun mute(until: Long) {
        val conversation = thread ?: return
        withContext(Dispatchers.Default) {
            recipientDatabase.save(conversation.address) {
                it.copy(muteUntil = until.millsToInstant())
            }
        }
    }

    private suspend fun setNotifyType(notifyType: NotifyType) {
        val conversation = thread ?: return
        withContext(Dispatchers.Default) {
            recipientDatabase.save(conversation.address) {
                it.copy(notifyType = notifyType)
            }
        }
    }

    data class UiState(
        val notificationTypes: OptionsCardData<NotificationType>? = null,
        val muteTypes: OptionsCardData<Long>? = null,
        val enableButton: Boolean = false,
    )

    sealed class NotificationType(val notifyType: NotifyType) {
        data object All: NotificationType(NotifyType.ALL)
        data object MentionsOnly: NotificationType(NotifyType.MENTIONS)
        data object Mute: NotificationType(NotifyType.NONE)
    }

    private val debugMuteDurations = listOf(
        TimeUnit.MINUTES.toMillis(1) to R.string.qa_conversation_settings_notifications_radio_1m,
        TimeUnit.MINUTES.toMillis(5) to R.string.qa_conversation_settings_notifications_radio_5m,
    )

    private val muteDurations = listOf(
        durationForever to R.string.qa_conversation_settings_notifications_radio_forever,
        TimeUnit.HOURS.toMillis(1) to R.string.qa_conversation_settings_notifications_radio_1h,
        TimeUnit.HOURS.toMillis(2) to R.string.qa_conversation_settings_notifications_radio_2h,
        TimeUnit.DAYS.toMillis(1) to R.string.qa_conversation_settings_notifications_radio_1d,
        TimeUnit.DAYS.toMillis(7) to R.string.qa_conversation_settings_notifications_radio_1w,
    )

    @AssistedFactory
    interface Factory {
        fun create(address: Address): NotificationSettingsViewModel
    }
}