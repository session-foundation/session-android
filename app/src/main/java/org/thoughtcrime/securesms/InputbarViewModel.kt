package org.thoughtcrime.securesms

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.session.libsession.utilities.Phrase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.LIMIT_KEY
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.dialog.SimpleDialogData
import org.thoughtcrime.securesms.util.NumberUtil

// the amount of character left at which point we should show an indicator
private const  val CHARACTER_LIMIT_THRESHOLD = 200

abstract class InputbarViewModel(
    private val context: Context,
    private val proStatusManager: ProStatusManager,
    private val recipientRepository: RecipientRepository,
): ViewModel() {
    protected val _inputBarState = MutableStateFlow(InputBarState())
    val inputBarState: StateFlow<InputBarState> get() = _inputBarState

    private val _inputBarStateDialogsState = MutableStateFlow(InputBarDialogsState())
    val inputBarStateDialogsState: StateFlow<InputBarDialogsState> = _inputBarStateDialogsState

    /**
     * ACCESS ("what may this device do") for the character limit — which gates SENDING, not just what
     * the composer displays.
     *
     * Deliberately NOT `by lazy`. ACCESS is validated against proof expiry and the cached revocation
     * list on every resolve, and a value captured once at first use is only ever validated once: a
     * revocation landing while this screen is open could not demote us until the ViewModel was
     * recreated. `observeSelf()`'s change sources include the revocation notification and a timer armed
     * at the earliest proof expiry, so this stays current for the life of the screen.
     *
     * A `StateFlow` rather than a `getSelf()` call per use because [onTextChanged] runs on every
     * keystroke and `getSelf()` is an uncached fetch that takes the config lock.
     */
    private val isSelfPro: StateFlow<Boolean> = recipientRepository.observeSelf()
        .map { it.isPro }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            // Seeded synchronously so the very first keystroke is already correct rather than briefly
            // reading as non-Pro. Guarded because `getSelf()` throws when not logged in.
            initialValue = runCatching { recipientRepository.getSelf().isPro }.getOrDefault(false),
        )

    /**
     * The composed length, kept so [validateMessageLength] can recompute against a FRESH access read
     * instead of trusting the limit that was in force when the indicator was last drawn.
     *
     * A length is not an access decision, so caching it is safe; caching the limit is not.
     */
    private var composedCodePointCount: Int = 0

    fun onTextChanged(text: CharSequence) {
        // RENDERING: the observed value. This runs per keystroke, so it must not take the config lock.
        val maxChars = proStatusManager.getCharacterLimit(isSelfPro.value)
        val message = text.toString()
        composedCodePointCount = message.codePointCount(0, message.length)
        val charsLeft = maxChars - composedCodePointCount

        // update the char limit state based on characters left
        val charLimitState = if(charsLeft <= CHARACTER_LIMIT_THRESHOLD){
            InputBarCharLimitState(
                count = charsLeft,
                countFormatted = NumberUtil.getFormattedNumber(charsLeft.toLong()),
                danger = charsLeft < 0,
                showProBadge = !isSelfPro.value // only show the badge for non pro users
            )
        } else {
            null
        }

        _inputBarState.update { it.copy(charLimitState = charLimitState) }
    }

    /**
     * ENFORCEMENT, so it calls the ACCESS function directly and unmemoized rather than reading the
     * observed value or the indicator's cached count.
     *
     * This gates SENDING, which makes it a grant rather than a draw: a proof that expired or was revoked
     * since the indicator was last drawn must refuse here, and it cannot if the decision is inherited
     * from render state. Recomputed from [composedCodePointCount] for the same reason — the stored
     * `charLimitState.count` was computed against whatever limit was in force at the last keystroke.
     */
    fun validateMessageLength(): Boolean {
        val hasProAccess = proStatusManager.currentUserHasProAccess()
        val charsLeft = proStatusManager.getCharacterLimit(hasProAccess) - composedCodePointCount

        return if(charsLeft < 0){
            // the user is trying to send a message that is too long - we should display a dialog
            if(hasProAccess){
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
        if(isSelfPro.value){
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
            it.copy(sessionProCharLimitCTA = CharLimitCTAData(proStatusManager.proDataState.value.type))
        }
    }

    fun showMessageLengthDialog(){
        _inputBarStateDialogsState.update {
            val charsLeft = _inputBarState.value.charLimitState?.count ?: 0
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.modalMessageCharacterDisplayTitle),
                    message = context.resources.getQuantityString(
                        R.plurals.modalMessageCharacterDisplayDescription,
                        charsLeft, // quantity for plural
                        proStatusManager.getCharacterLimit(isSelfPro.value), // 1st arg: total character limit
                        charsLeft, // 2nd arg: chars left
                    ),
                    positiveStyleDanger = false,
                    positiveText = context.getString(R.string.okay),
                    onPositive = ::hideSimpleDialog

                )
            )
        }
    }

    fun showMessageTooLongDialog(){
        _inputBarStateDialogsState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.modalMessageTooLongTitle),
                    message = Phrase.from(context.getString(R.string.modalMessageCharacterTooLongDescription))
                        .put(LIMIT_KEY, proStatusManager.getCharacterLimit(isSelfPro.value))
                        .format(),
                    positiveStyleDanger = false,
                    positiveText = context.getString(R.string.okay),
                    onPositive = ::hideSimpleDialog
                )
            )
        }
    }

    fun showMessageTooLongSendDialog(){
        _inputBarStateDialogsState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.modalMessageTooLongTitle),
                    message = Phrase.from(context.getString(R.string.modalMessageTooLongDescription))
                        .put(LIMIT_KEY, proStatusManager.getCharacterLimit(isSelfPro.value))
                        .format(),
                    positiveStyleDanger = false,
                    positiveText = context.getString(R.string.okay),
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
                    it.copy(sessionProCharLimitCTA = null)
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
        val sessionProCharLimitCTA: CharLimitCTAData? = null
    )

    data class CharLimitCTAData(
        val proSubscription: ProStatus
    )

    sealed interface Commands {
        data object HideSimpleDialog : Commands
        data object HideSessionProCTA : Commands
    }
}