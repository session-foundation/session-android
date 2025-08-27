package org.thoughtcrime.securesms.conversation.v2.input_bar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.net.Uri
import android.text.Editable
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.bumptech.glide.RequestManager
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewInputBarBinding
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.InputbarViewModel
import org.thoughtcrime.securesms.InputbarViewModel.InputBarContentState
import org.thoughtcrime.securesms.conversation.v2.ViewUtil
import org.thoughtcrime.securesms.conversation.v2.components.LinkPreviewDraftView
import org.thoughtcrime.securesms.conversation.v2.components.LinkPreviewDraftViewDelegate
import org.thoughtcrime.securesms.conversation.v2.messages.QuoteView
import org.thoughtcrime.securesms.conversation.v2.messages.QuoteViewDelegate
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.util.addTextChangedListener
import org.thoughtcrime.securesms.util.contains
import javax.inject.Inject

// TODO: A lot of the logic regarding voice messages is currently performed in the ConversationActivity
// TODO: and here - it would likely be best to move this into the CA's ViewModel.

// Enums to keep track of the state of our voice recording mechanism as the user can
// manipulate the UI faster than we can setup & teardown.
enum class VoiceRecorderState {
    Idle,
    SettingUpToRecord,
    Recording,
    ShuttingDownAfterRecord
}

@SuppressLint("ClickableViewAccessibility")
class InputBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(
    context,
    attrs,
    defStyleAttr
), InputBarEditTextDelegate,
    QuoteViewDelegate,
    LinkPreviewDraftViewDelegate,
    TextView.OnEditorActionListener {

    private var binding: ViewInputBarBinding = ViewInputBarBinding.inflate(LayoutInflater.from(context), this, true)
    private var linkPreviewDraftView: LinkPreviewDraftView? = null
    private var quoteView: QuoteView? = null
    var delegate: InputBarDelegate? = null
    var quote: MessageRecord? = null
    var linkPreview: LinkPreview? = null
    private var showInput: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                showOrHideInputIfNeeded()
            }
        }
    private var allowAttachMultimediaButtons: Boolean = true
        set(value) {
            field = value
            updateMultimediaButtonsState()

            binding.inputBarEditText.allowMultimediaInput = value
        }

    var text: String
        get() = binding.inputBarEditText.text?.toString() ?: ""
        set(value) { binding.inputBarEditText.setText(value) }

    fun setText(text: CharSequence, type: TextView.BufferType){
        binding.inputBarEditText.setText(text, type)
    }

    var voiceRecorderState = VoiceRecorderState.Idle

    @Inject
    lateinit var recipientRepository: RecipientRepository

    private val attachmentsButton = InputBarButton(context, R.drawable.ic_plus).apply {
        contentDescription = context.getString(R.string.AccessibilityId_attachmentsButton)
    }

    val microphoneButton = InputBarButton(context, R.drawable.ic_mic).apply {
        contentDescription = context.getString(R.string.AccessibilityId_voiceMessageNew)
    }

    private val sendButton = InputBarButton(context, R.drawable.ic_arrow_up, isSendButton = true).apply {
        contentDescription = context.getString(R.string.AccessibilityId_send)
    }

    private val textColor: Int by lazy {
        context.getColorFromAttr(android.R.attr.textColorPrimary)
    }

    private val dangerColor: Int by lazy {
        context.getColorFromAttr(R.attr.danger)
    }

    var sendOnly: Boolean = false

    init {
        // Parse custom attributes
        attrs?.let { attributeSet ->
            val typedArray = context.obtainStyledAttributes(attributeSet, R.styleable.InputBar)
            try {
                sendOnly = typedArray.getBoolean(R.styleable.InputBar_sendOnly, false)
            } finally {
                typedArray.recycle()
            }
        }

        // Send button
        binding.microphoneOrSendButtonContainer.addView(sendButton)
        sendButton.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        sendButton.onUp = { e ->
            if (sendButton.contains(PointF(e.x, e.y))) {
                delegate?.sendMessage()
            }
        }

        // Edit text
        binding.inputBarEditText.setOnEditorActionListener(this)
        if (TextSecurePreferences.isEnterSendsEnabled(context)) {
            binding.inputBarEditText.imeOptions = EditorInfo.IME_ACTION_SEND
        } else {
            binding.inputBarEditText.imeOptions = EditorInfo.IME_ACTION_NONE
        }
        val incognitoFlag = if (TextSecurePreferences.isIncognitoKeyboardEnabled(context)) 16777216 else 0
        binding.inputBarEditText.imeOptions = binding.inputBarEditText.imeOptions or incognitoFlag // Always use incognito keyboard if setting enabled
        binding.inputBarEditText.delegate = this

        if(sendOnly){
            sendButton.isVisible = true
            binding.attachmentsButtonContainer.isVisible = false
            microphoneButton.isVisible = false
        } else {
            sendButton.isVisible = false

            // Attachments button
            binding.attachmentsButtonContainer.addView(attachmentsButton)
            attachmentsButton.layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            attachmentsButton.onPress = { toggleAttachmentOptions() }

            // Microphone button
            binding.microphoneOrSendButtonContainer.addView(microphoneButton)
            microphoneButton.layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

            microphoneButton.onMove = { delegate?.onMicrophoneButtonMove(it) }
            microphoneButton.onCancel = { delegate?.onMicrophoneButtonCancel(it) }

            // Use a separate 'raw' OnTouchListener to record the microphone button down/up timestamps because
            // they don't get delayed by any multi-threading or delegates which throw off the timestamp accuracy.
            // For example: If we bind something to `microphoneButton.onPress` and also log something in
            // `microphoneButton.onUp` and tap the button then the logged output order is onUp and THEN onPress!
            microphoneButton.setOnTouchListener(object : OnTouchListener {
                override fun onTouch(v: View, event: MotionEvent): Boolean {

                    // We only handle single finger touch events so just consume the event and bail if there are more
                    if (event.pointerCount > 1) return true

                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {

                            // Only start spinning up the voice recorder if we're not already recording, setting up, or tearing down
                            if (voiceRecorderState == VoiceRecorderState.Idle) {
                                startRecordingVoiceMessage()
                            }
                        }

                        MotionEvent.ACTION_UP -> {

                            // Handle the pointer up event appropriately, whether that's to keep recording if recording was locked
                            // on, or finishing recording if just hold-to-record.
                            delegate?.onMicrophoneButtonUp(event)
                        }
                    }

                    // Return false to propagate the event rather than consuming it
                    return false
                }
            })
        }
    }

    override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
        if (v === binding.inputBarEditText && actionId == EditorInfo.IME_ACTION_SEND) {
            // same as pressing send button
            delegate?.sendMessage()
            return true
        }
        return false
    }

    override fun inputBarEditTextContentChanged(text: CharSequence) {
        microphoneButton.isVisible = text.trim().isEmpty() && !sendOnly
        sendButton.isVisible = microphoneButton.isGone || sendOnly
        delegate?.inputBarEditTextContentChanged(text)
    }

    override fun commitInputContent(contentUri: Uri) { delegate?.commitInputContent(contentUri) }

    private fun toggleAttachmentOptions() { delegate?.toggleAttachmentOptions() }


    private fun startRecordingVoiceMessage() {
        delegate?.startRecordingVoiceMessage()
    }

    fun draftQuote(thread: Recipient, message: MessageRecord, glide: RequestManager) {
        quoteView?.let(binding.inputBarAdditionalContentContainer::removeView)

        quote = message

        // If we already have a link preview View then clear the 'additional content' layout so that
        // our quote View is always the first element (i.e., at the top of the reply).
        if (linkPreview != null && linkPreviewDraftView != null) {
            binding.inputBarAdditionalContentContainer.removeAllViews()
        }

        // Inflate quote View with typed array here
        val layout = LayoutInflater.from(context).inflate(R.layout.view_quote_draft, binding.inputBarAdditionalContentContainer, false)
        quoteView = layout.findViewById<QuoteView>(R.id.mainQuoteViewContainer).also {
            it.delegate = this
            binding.inputBarAdditionalContentContainer.addView(layout)
            val attachments = (message as? MmsMessageRecord)?.slideDeck
            val sender =
                if (message.isOutgoing) recipientRepository.getRecipientSync(Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)!!))
                else message.individualRecipient
            it.bind(sender, message.body, attachments, thread, true, message.isOpenGroupInvitation, message.threadId, false, glide)
        }

        // Before we request a layout update we'll add back any LinkPreviewDraftView that might
        // exist - as this goes into the LinearLayout second it will be below the quote View.
        if (linkPreview != null && linkPreviewDraftView != null) {
            binding.inputBarAdditionalContentContainer.addView(linkPreviewDraftView)
        }

        // focus the text and show keyboard
        ViewUtil.focusAndShowKeyboard(binding.inputBarEditText)

        requestLayout()
    }

    override fun cancelQuoteDraft() {
        binding.inputBarAdditionalContentContainer.removeView(quoteView)
        quote = null
        quoteView = null
        requestLayout()
    }

    fun draftLinkPreview() {
        // As `draftLinkPreview` is called before `updateLinkPreview` when we modify a URI in a
        // message we'll bail early if a link preview View already exists and just let
        // `updateLinkPreview` get called to update the existing View.
        if (linkPreview != null && linkPreviewDraftView != null) return
        linkPreviewDraftView?.let(binding.inputBarAdditionalContentContainer::removeView)
        linkPreviewDraftView = LinkPreviewDraftView(context).also { it.delegate = this }

        // Add the link preview View. Note: If there's already a quote View in the 'additional
        // content' container then this preview View will be added after / below it - which is fine.
        binding.inputBarAdditionalContentContainer.addView(linkPreviewDraftView)
        requestLayout()
    }

    fun updateLinkPreviewDraft(glide: RequestManager, updatedLinkPreview: LinkPreview) {
        // Update our `linkPreview` property with the new (provided as an argument to this function)
        // then update the View from that.
        linkPreview = updatedLinkPreview.also { linkPreviewDraftView?.update(glide, it) }
    }

    override fun cancelLinkPreviewDraft() {
        binding.inputBarAdditionalContentContainer.removeView(linkPreviewDraftView)
        linkPreview = null
        linkPreviewDraftView = null
        requestLayout()
    }

    private fun showOrHideInputIfNeeded() {
        if (!showInput) {
            cancelQuoteDraft()
            cancelLinkPreviewDraft()
        }

        binding.inputBarEditText.isVisible = showInput
        attachmentsButton.isVisible = showInput && !sendOnly
        microphoneButton.isVisible = showInput && text.isEmpty() && !sendOnly
        sendButton.isVisible = showInput && text.isNotEmpty() || sendOnly
    }

    private fun updateMultimediaButtonsState() {
        attachmentsButton.isEnabled = allowAttachMultimediaButtons

        microphoneButton.isEnabled  = allowAttachMultimediaButtons
    }

    fun addTextChangedListener(listener: (String) -> Unit) {
        binding.inputBarEditText.addTextChangedListener(listener)
    }

    fun setInputBarEditableFactory(factory: Editable.Factory) {
        binding.inputBarEditText.setEditableFactory(factory)
    }

    fun setState(state: InputbarViewModel.InputBarState){
        // handle content state
        when(state.contentState){
            is InputBarContentState.Hidden ->{
                isVisible = false
            }

            is InputBarContentState.Disabled ->{
                isVisible = true
                binding.inputBarEditText.isVisible = false
                binding.inputBarAdditionalContentContainer.isVisible = false
                binding.inputBarEditText.text?.clear()
                inputBarEditTextContentChanged("")
                binding.disabledBanner.isVisible = true
                binding.disabledText.text = state.contentState.text
                if(state.contentState.onClick == null){
                    binding.disabledBanner.setOnClickListener(null)
                } else {
                    binding.disabledBanner.setOnClickListener {
                        state.contentState.onClick()
                    }
                }
            }

            else -> {
                isVisible = true
                binding.inputBarEditText.isVisible = true
                binding.inputBarAdditionalContentContainer.isVisible = true
                binding.disabledBanner.isVisible = false
            }
        }

        // handle buttons state
        allowAttachMultimediaButtons = state.enableAttachMediaControls
    }

    fun setCharLimitState(state: InputbarViewModel.InputBarCharLimitState?) {
        // handle char limit
        if(state != null){
            binding.characterLimitText.text = state.count.toString()
            binding.characterLimitText.setTextColor(if(state.danger) dangerColor else textColor)
            binding.characterLimitContainer.setOnClickListener {
                delegate?.onCharLimitTapped()
            }

            binding.badgePro.isVisible = state.showProBadge

            binding.characterLimitContainer.isVisible = true
        } else {
            binding.characterLimitContainer.setOnClickListener(null)
            binding.characterLimitContainer.isVisible = false
        }
    }
}

interface InputBarDelegate {
    fun inputBarEditTextContentChanged(newContent: CharSequence)
    fun toggleAttachmentOptions()
    fun showVoiceMessageUI()
    fun startRecordingVoiceMessage()
    fun onMicrophoneButtonMove(event: MotionEvent)
    fun onMicrophoneButtonCancel(event: MotionEvent)
    fun onMicrophoneButtonUp(event: MotionEvent)
    fun sendMessage()
    fun commitInputContent(contentUri: Uri)
    fun onCharLimitTapped()
}
