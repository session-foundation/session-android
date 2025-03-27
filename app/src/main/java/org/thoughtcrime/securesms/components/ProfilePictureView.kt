package org.thoughtcrime.securesms.components

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewProfilePictureBinding
import org.session.libsession.avatars.ContactColors
import org.session.libsession.avatars.PlaceholderAvatarPhoto
import org.session.libsession.avatars.ProfileContactPhoto
import org.session.libsession.avatars.ResourceContactPhoto
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.AppTextSecurePreferences
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.truncateIdForDisplay
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.GroupDatabase
import javax.inject.Inject

@AndroidEntryPoint
class ProfilePictureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {
    private val TAG = "ProfilePictureView"

    private val binding = ViewProfilePictureBinding.inflate(LayoutInflater.from(context), this)
    private val glide: RequestManager = Glide.with(this)
    private val prefs = AppTextSecurePreferences(context)
    private val userPublicKey = prefs.getLocalNumber()
    var publicKey: String? = null
    var displayName: String? = null
    var additionalPublicKey: String? = null
    var additionalDisplayName: String? = null
    var recipient: Recipient? = null

    @Inject
    lateinit var groupDatabase: GroupDatabase

    @Inject
    lateinit var storage: StorageProtocol

    @Inject
    lateinit var usernameUtils: UsernameUtils

    private val profilePicturesCache = mutableMapOf<View, Recipient>()
    private val resourcePadding by lazy {
        context.resources.getDimensionPixelSize(R.dimen.normal_padding).toFloat()
    }
    private val unknownOpenGroupDrawable by lazy { ResourceContactPhoto(R.drawable.ic_notification)
        .asDrawable(context, ContactColors.UNKNOWN_COLOR.toConversationColor(context), false, resourcePadding) }

    constructor(context: Context, sender: Recipient): this(context) {
        update(sender)
    }

    private fun createUnknownRecipientDrawable(): Drawable {
        return ResourceContactPhoto(R.drawable.ic_user_filled_custom)
            .asDrawable(context, ContactColors.UNKNOWN_COLOR.toConversationColor(context), false, resourcePadding)
    }

    fun update(recipient: Recipient) {
        this.recipient = recipient
        recipient.run {
            update(
                address = address,
                isLegacyGroupRecipient = isLegacyGroupRecipient,
                isCommunityInboxRecipient = isCommunityInboxRecipient,
                isGroupsV2Recipient = isGroupV2Recipient
            )
        }
    }

    fun update(
        address: Address,
        isLegacyGroupRecipient: Boolean = false,
        isCommunityInboxRecipient: Boolean = false,
        isGroupsV2Recipient: Boolean = false,
    ) {
        fun getUserDisplayName(publicKey: String): String = prefs.takeIf { userPublicKey == publicKey }?.getProfileName()
            ?: usernameUtils.getContactNameWithAccountID(publicKey)

        if (isLegacyGroupRecipient || isGroupsV2Recipient) {
            val members = if (isLegacyGroupRecipient) {
                groupDatabase
                    .getGroupMemberAddresses(address.toGroupString(), true)
            } else {
                storage.getMembers(address.toString())
                    .map { Address.fromSerialized(it.accountId()) }
            }.sorted().take(2)

            if (members.size <= 1) {
                publicKey = ""
                displayName = ""
                additionalPublicKey = ""
                additionalDisplayName = ""
            } else {
                val pk = members.getOrNull(0)?.toString() ?: ""
                publicKey = pk
                displayName = getUserDisplayName(pk)
                val apk = members.getOrNull(1)?.toString() ?: ""
                additionalPublicKey = apk
                additionalDisplayName = getUserDisplayName(apk)
            }
        } else if(isCommunityInboxRecipient) {
            val publicKey = GroupUtil.getDecodedOpenGroupInboxAccountId(address.toString())
            this.publicKey = publicKey
            displayName = getUserDisplayName(publicKey)
            additionalPublicKey = null
        } else {
            val publicKey = address.toString()
            this.publicKey = publicKey
            displayName = getUserDisplayName(publicKey)
            additionalPublicKey = null
        }
        update()
    }

    fun update() {
        val publicKey = publicKey ?: return Log.w(TAG, "Could not find public key to update profile picture")
        val additionalPublicKey = additionalPublicKey
        // if we have a multi avatar setup
        if (additionalPublicKey != null) {
            setProfilePictureIfNeeded(binding.doubleModeImageView1, publicKey, displayName)
            setProfilePictureIfNeeded(binding.doubleModeImageView2, additionalPublicKey, additionalDisplayName)
            binding.doubleModeImageViewContainer.visibility = View.VISIBLE

            // clear single image
            glide.clear(binding.singleModeImageView)
            binding.singleModeImageView.visibility = View.INVISIBLE
        } else { // single image mode
            setProfilePictureIfNeeded(binding.singleModeImageView, publicKey, displayName)
            binding.singleModeImageView.visibility = View.VISIBLE

            // clear multi image
            glide.clear(binding.doubleModeImageView1)
            glide.clear(binding.doubleModeImageView2)
            binding.doubleModeImageViewContainer.visibility = View.INVISIBLE
        }

    }

    private fun setProfilePictureIfNeeded(imageView: ImageView, publicKey: String, displayName: String?) {
        if (publicKey.isNotEmpty()) {
            // if we already have a recipient that matches the current key, reuse it
            val recipient = if(this.recipient != null && this.recipient?.address?.toString() == publicKey){
                this.recipient!!
            }
            else {
                this.recipient = Recipient.from(context, Address.fromSerialized(publicKey), false)
                this.recipient!!
            }
            
            if (profilePicturesCache[imageView] == recipient) return
            // recipient is mutable so without cloning it the line above always returns true as the changes to the underlying recipient happens on both shared instances
            profilePicturesCache[imageView] = recipient.clone()
            val signalProfilePicture = recipient.contactPhoto
            val avatar = (signalProfilePicture as? ProfileContactPhoto)?.avatarObject

            glide.clear(imageView)

            val placeholder = PlaceholderAvatarPhoto(publicKey, displayName ?: truncateIdForDisplay(publicKey))

            if (signalProfilePicture != null && avatar != "0" && avatar != "") {
                glide.load(signalProfilePicture)
                    .placeholder(createUnknownRecipientDrawable())
                    .centerCrop()
                    .error(glide.load(placeholder))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .circleCrop()
                    .into(imageView)
            } else if (recipient.isCommunityRecipient && recipient.groupAvatarId == null) {
                glide.load(unknownOpenGroupDrawable)
                    .centerCrop()
                    .circleCrop()
                    .into(imageView)
            } else {
                glide.load(placeholder)
                    .placeholder(createUnknownRecipientDrawable())
                    .centerCrop()
                    .circleCrop()
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(imageView)
            }
        } else {
            glide.load(createUnknownRecipientDrawable())
                .fitCenter()
                .into(imageView)
        }
    }

    fun recycle() {
        profilePicturesCache.clear()
    }
    // endregion
}
