package org.thoughtcrime.securesms.conversation.v2.input_bar.mentions

import android.view.View
import network.loki.messenger.databinding.ViewMentionCandidateV2Binding
import org.thoughtcrime.securesms.conversation.v2.mention.MentionViewModel
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

fun ViewMentionCandidateV2Binding.update(candidate: MentionViewModel.Candidate) {
    mentionCandidateNameTextView.text = candidate.nameHighlighted
    profilePictureView.setThemedContent {
        Avatar(
            size = LocalDimensions.current.iconMediumAvatar,
            data = candidate.member.avatarData,
        )
    }

    moderatorIconImageView.visibility = if (candidate.member.showAdminCrown) View.VISIBLE else View.GONE
}
