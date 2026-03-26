package org.thoughtcrime.securesms.conversation.v2.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import network.loki.messenger.databinding.ViewConversationTypingContainerBinding

class TypingIndicatorViewContainer : LinearLayout {
    private lateinit var binding: ViewConversationTypingContainerBinding

    constructor(context: Context) : super(context) { initialize() }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { initialize() }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { initialize() }

    private fun initialize() {
        binding = ViewConversationTypingContainerBinding.inflate(LayoutInflater.from(context), this, true)
    }

    fun startAnimation() = binding.typingIndicator.root.startAnimation()
    fun stopAnimation() = binding.typingIndicator.root.stopAnimation()
}