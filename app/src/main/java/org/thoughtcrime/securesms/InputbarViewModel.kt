package org.thoughtcrime.securesms

import android.app.Application
import androidx.lifecycle.ViewModel
import com.squareup.phrase.Phrase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.LIMIT_KEY
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.SimpleDialogData
import org.thoughtcrime.securesms.util.NumberUtil

// the amount of character left at which point we should show an indicator
private const  val CHARACTER_LIMIT_THRESHOLD = 200

abstract class InputbarViewModel(
    private val application: Application,
    private val proStatusManager: ProStatusManager
): ViewModel() {
    protected val _inputBarState = MutableStateFlow(InputBarState())
    val inputBarState: StateFlow<InputBarState> get() = _inputBarState

    private val _inputBarStateDialogsState = MutableStateFlow(InputBarDialogsState())
    val inputBarStateDialogsState: StateFlow<InputBarDialogsState> = _inputBarStateDialogsState

    fun onTextChanged(text: CharSequence) {
        // check the character limit
        val maxChars = proStatusManager.getCharacterLimit()
        val charsLeft = maxChars - text.length

        // update the char limit state based on characters left
        val charLimitState = if(charsLeft <= CHARACTER_LIMIT_THRESHOLD){
            InputBarCharLimitState(
                count = charsLeft,
                countFormatted = NumberUtil.getFormattedNumber(charsLeft.toLong()),
                danger = charsLeft < 0,
                showProBadge = proStatusManager.isPostPro() && !proStatusManager.isCurrentUserPro() // only show the badge for non pro users POST pro launch
            )
        } else {
            null
        }

        _inputBarState.update { it.copy(charLimitState = charLimitState) }
    }

    fun validateMessageLength(): Boolean {
        // the message is too long if we have a negative char left in the input state
        val charsLeft = _inputBarState.value.charLimitState?.count ?: 0
        return if(charsLeft < 0){
            // the user is trying to send a message that is too long - we should display a dialog
            // we currently have different logic for PRE and POST Pro launch
            // which we can remove once Pro is out - currently we can switch this fro the debug menu
            if(!proStatusManager.isPostPro() || proStatusManager.isCurrentUserPro()){
                showMessageTooLongSendDialog()
            } else {
                showSessionProCTA()
            }

            false
        } else {
            true
        }
    }

    fun onCharLimitTapped(){
        // we currently have different logic for PRE and POST Pro launch
        // which we can remove once Pro is out - currently we can switch this fro the debug menu
        if(!proStatusManager.isPostPro() || proStatusManager.isCurrentUserPro()){
            handleCharLimitTappedForProUser()
        } else {
            handleCharLimitTappedForRegularUser()
        }
    }

    private fun handleCharLimitTappedForProUser(){
        if((_inputBarState.value.charLimitState?.count ?: 0) < 0){
            showMessageTooLongDialog()
        } else {
            showMessageLengthDialog()
        }
    }

    private fun handleCharLimitTappedForRegularUser(){
        showSessionProCTA()
    }

    fun showSessionProCTA(){
        _inputBarStateDialogsState.update {
            it.copy(sessionProCharLimitCTA = true)
        }
    }

    fun showMessageLengthDialog(){
        _inputBarStateDialogsState.update {
            val charsLeft = _inputBarState.value.charLimitState?.count ?: 0
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = application.getString(R.string.modalMessageCharacterDisplayTitle),
                    message = application.resources.getQuantityString(
                        R.plurals.modalMessageCharacterDisplayDescription,
                        charsLeft, // quantity for plural
                        proStatusManager.getCharacterLimit(), // 1st arg: total character limit
                        charsLeft, // 2nd arg: chars left
                    ),
                    positiveStyleDanger = false,
                    positiveText = application.getString(R.string.okay),
                    onPositive = ::hideSimpleDialog

                )
            )
        }
    }

    fun showMessageTooLongDialog(){
        _inputBarStateDialogsState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = application.getString(R.string.modalMessageTooLongTitle),
                    message = Phrase.from(application.getString(R.string.modalMessageCharacterTooLongDescription))
                        .put(LIMIT_KEY, proStatusManager.getCharacterLimit())
                        .format(),
                    positiveStyleDanger = false,
                    positiveText = application.getString(R.string.okay),
                    onPositive = ::hideSimpleDialog
                )
            )
        }
    }

    fun showMessageTooLongSendDialog(){
        _inputBarStateDialogsState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = application.getString(R.string.modalMessageTooLongTitle),
                    message = Phrase.from(application.getString(R.string.modalMessageTooLongDescription))
                        .put(LIMIT_KEY, proStatusManager.getCharacterLimit())
                        .format(),
                    positiveStyleDanger = false,
                    positiveText = application.getString(R.string.okay),
                    onPositive = ::hideSimpleDialog
                )
            )
        }
    }

    private fun hideSimpleDialog(){
        _inputBarStateDialogsState.update {
            it.copy(showSimpleDialog = null)
        }
    }

    fun onInputBarCommand(command: Commands) {
        when (command) {
            is Commands.HideSimpleDialog -> {
                hideSimpleDialog()
            }

            is Commands.HideSessionProCTA -> {
                _inputBarStateDialogsState.update {
                    it.copy(sessionProCharLimitCTA = false)
                }
            }
        }
    }

    data class InputBarCharLimitState(
        val count: Int,
        val countFormatted: String,
        val danger: Boolean,
        val showProBadge: Boolean
    )

    sealed interface InputBarContentState {
        data object Hidden : InputBarContentState
        data object Visible : InputBarContentState
        data class Disabled(val text: String, val onClick: (() -> Unit)? = null) : InputBarContentState
    }

    data class InputBarState(
        val contentState: InputBarContentState = InputBarContentState.Visible,
        // Note: These input media controls are with regard to whether the user can attach multimedia files
        // or record voice messages to be sent to a recipient - they are NOT things like video or audio
        // playback controls.
        val enableAttachMediaControls: Boolean = true,
        val charLimitState: InputBarCharLimitState? = null,
    )

    data class InputBarDialogsState(
        val showSimpleDialog: SimpleDialogData? = null,
        val sessionProCharLimitCTA: Boolean = false
    )

    sealed interface Commands {
        data object HideSimpleDialog : Commands
        data object HideSessionProCTA : Commands
    }
}