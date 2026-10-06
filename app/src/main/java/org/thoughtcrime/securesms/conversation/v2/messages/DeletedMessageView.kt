package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewDeletedMessageBinding
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.content.UnsupportedMessageContent

class DeletedMessageView : LinearLayout {
    private val binding: ViewDeletedMessageBinding by lazy { ViewDeletedMessageBinding.bind(this) }
    // region Lifecycle
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    // endregion

    // region Updating
    fun bind(message: MessageRecord, @ColorInt textColor: Int) {
        assert(message.isDeleted)
        // set the text to the message's body if it is set, else use a fallback
        binding.deleteTitleTextView.text = message.body.ifEmpty { context.resources.getQuantityString(R.plurals.deleteMessageDeleted, 1, 1) }
        binding.deleteTitleTextView.contentDescription = context.getString(R.string.AccessibilityId_deleteMessageDeleted)
        binding.deletedMessageViewIconImageView.setImageResource(R.drawable.ic_trash_2)
        applyColor(textColor)
    }

    fun bindUnsupported(@ColorInt textColor: Int) {
        binding.deleteTitleTextView.text = UnsupportedMessageContent.PLACEHOLDER_TEXT
        binding.deleteTitleTextView.contentDescription = null
        binding.deletedMessageViewIconImageView.setImageResource(R.drawable.ic_circle_alert)
        applyColor(textColor)
    }

    private fun applyColor(@ColorInt textColor: Int) {
        val deletedColor = textColor.also { alpha = 0.7f } // deleted messages use the regular text colour with some opacitiy applied)
        binding.deleteTitleTextView.setTextColor(deletedColor)
        binding.deletedMessageViewIconImageView.imageTintList = ColorStateList.valueOf(deletedColor)
    }
    // endregion
}