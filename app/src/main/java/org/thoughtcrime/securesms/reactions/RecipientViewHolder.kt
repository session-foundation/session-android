package org.thoughtcrime.securesms.reactions

import android.view.View
import androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
import network.loki.messenger.R
import network.loki.messenger.databinding.ReactionsBottomSheetDialogFragmentRecipientItemBinding
import org.session.libsession.utilities.recipients.displayName
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.util.AvatarUtils

class RecipientViewHolder(
    private val avatarUtils: AvatarUtils,
    private val callback: ReactionViewPagerAdapter.Listener,
    private val binding: ReactionsBottomSheetDialogFragmentRecipientItemBinding,
    private val canRemove: Boolean
) : ReactionRecipientsAdapter.ViewHolder(binding.getRoot()) {
    init {
        binding.reactionsBottomViewAvatar.setViewCompositionStrategy(
            DisposeOnDetachedFromWindowOrReleasedFromPool
        )
    }

    fun bind(reaction: ReactionDetails) {
        binding.reactionsBottomViewRecipientRemove.setOnClickListener { v: View? ->
            callback.onRemoveReaction(reaction.baseEmoji, reaction.localId, reaction.timestamp)
        }

        itemView.setOnClickListener {
            callback.onEmojiReactionUserTapped(reaction.sender)
        }

        binding.reactionsBottomViewAvatar.setThemedContent {
            Avatar(
                size = LocalDimensions.current.iconMediumAvatar,
                data = avatarUtils.getUIDataFromRecipient(reaction.sender)
            )
        }

        if (reaction.sender.isSelf) {
            binding.reactionsBottomViewRecipientName.setText(R.string.you)
            binding.reactionsBottomViewRecipientRemove.setVisibility(if (canRemove) View.VISIBLE else View.GONE)
        } else {
            val name = reaction.sender.displayName()
            binding.reactionsBottomViewRecipientName.text = name
            binding.reactionsBottomViewRecipientRemove.setVisibility(View.GONE)
        }
    }

    fun unbind() {
    }
}
