package org.thoughtcrime.securesms.contacts

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewUserBinding
import org.session.libsession.utilities.recipients.Recipient

@AndroidEntryPoint
class UserView : LinearLayout {
    private lateinit var binding: ViewUserBinding

    enum class ActionIndicator {
        None,
        Menu,
        Tick
    }

    // region Lifecycle
    constructor(context: Context) : super(context) {
        setUpViewHierarchy()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        setUpViewHierarchy()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        setUpViewHierarchy()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        setUpViewHierarchy()
    }

    private fun setUpViewHierarchy() {
        binding = ViewUserBinding.inflate(LayoutInflater.from(context), this, true)
    }
    // endregion

    // region Updating
    fun bind(user: Recipient, actionIndicator: ActionIndicator, isSelected: Boolean = false, showCurrentUserAsNoteToSelf: Boolean = false) {
        val isLocalUser = user.isLocalNumber

        fun getUserDisplayName(): String {
            return when {
                isLocalUser && showCurrentUserAsNoteToSelf -> context.getString(R.string.noteToSelf)
                isLocalUser && !showCurrentUserAsNoteToSelf -> context.getString(R.string.you)
                else -> user.displayName
            }
        }

        binding.profilePictureView.update(user)
        binding.actionIndicatorImageView.setImageResource(R.drawable.ic_radio_unselected)
        binding.nameTextView.text = if (user.isGroupOrCommunityRecipient) user.displayName else getUserDisplayName()
        when (actionIndicator) {
            ActionIndicator.None -> {
                binding.actionIndicatorImageView.visibility = View.GONE
            }
            ActionIndicator.Menu -> {
                binding.actionIndicatorImageView.visibility = View.VISIBLE
                binding.actionIndicatorImageView.setImageResource(R.drawable.ic_circle_dots_custom)
            }
            ActionIndicator.Tick -> {
                binding.actionIndicatorImageView.visibility = View.VISIBLE
                if (isSelected) {
                    binding.actionIndicatorImageView.setImageResource(R.drawable.ic_radio_selected)
                } else {
                    binding.actionIndicatorImageView.setImageResource(R.drawable.ic_radio_unselected)
                }
            }
        }
    }

    fun toggleCheckbox(isSelected: Boolean = false) {
        binding.actionIndicatorImageView.visibility = View.VISIBLE
        if (isSelected) {
            binding.actionIndicatorImageView.setImageResource(R.drawable.ic_radio_selected)
        } else {
            binding.actionIndicatorImageView.setImageResource(R.drawable.ic_radio_unselected)
        }
    }

    fun handleAdminStatus(isAdmin: Boolean){
        binding.adminIcon.visibility = if (isAdmin) View.VISIBLE else View.GONE
    }

    fun unbind() { binding.profilePictureView.recycle() }
    // endregion
}
