package org.thoughtcrime.securesms.contacts

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewUserBinding
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import com.bumptech.glide.RequestManager

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
    fun bind(user: Recipient, glide: RequestManager, actionIndicator: ActionIndicator, isSelected: Boolean = false) {
        val isLocalUser = user.isLocalNumber

        fun getUserDisplayName(publicKey: String): String {
            if (isLocalUser) return context.getString(R.string.you)
            val contact = DatabaseComponent.get(context).sessionContactDatabase().getContactWithAccountID(publicKey)
            return contact?.displayName(Contact.ContactContext.REGULAR) ?: publicKey
        }

        val address = user.address.serialize()
        binding.profilePictureView.update(user)
        binding.actionIndicatorImageView.setImageResource(R.drawable.ic_baseline_edit_24)
        binding.nameTextView.text = if (user.isGroupRecipient) user.name else getUserDisplayName(address)
        when (actionIndicator) {
            ActionIndicator.None -> {
                binding.actionIndicatorImageView.visibility = View.GONE
            }
            ActionIndicator.Menu -> {
                binding.actionIndicatorImageView.visibility = View.VISIBLE
                binding.actionIndicatorImageView.setImageResource(R.drawable.ic_more_horiz_white)
            }
            ActionIndicator.Tick -> {
                binding.actionIndicatorImageView.visibility = View.VISIBLE
                if (isSelected) {
                    binding.actionIndicatorImageView.setImageResource(R.drawable.padded_circle_accent)
                } else {
                    binding.actionIndicatorImageView.setImageDrawable(null)
                }
            }
        }
    }

    fun toggleCheckbox(isSelected: Boolean = false) {
        binding.actionIndicatorImageView.visibility = View.VISIBLE
        if (isSelected) {
            binding.actionIndicatorImageView.setImageResource(R.drawable.padded_circle_accent)
        } else {
            binding.actionIndicatorImageView.setImageDrawable(null)
        }
    }

    fun unbind() { binding.profilePictureView.recycle() }
    // endregion
}
