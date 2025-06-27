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
import org.session.libsession.avatars.ContactPhoto
import org.session.libsession.avatars.PlaceholderAvatarPhoto
import org.session.libsession.avatars.ProfileContactPhoto
import org.session.libsession.avatars.ResourceContactPhoto
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.AppTextSecurePreferences
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.recipients.RecipientAvatar
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.truncateIdForDisplay
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.avatarOptions
import javax.inject.Inject

@AndroidEntryPoint
class ProfilePictureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
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
    lateinit var avatarUtils: AvatarUtils

    @Inject
    lateinit var recipientRepository: RecipientRepository

    private val profilePicturesCache = mutableMapOf<View, Recipient>()
    private val resourcePadding by lazy {
        context.resources.getDimensionPixelSize(R.dimen.normal_padding).toFloat()
    }
    private val unknownOpenGroupDrawable by lazy { ResourceContactPhoto(R.drawable.ic_notification)
        .asDrawable(context, ContactColors.UNKNOWN_COLOR.toConversationColor(context), false, resourcePadding) }

    constructor(context: Context, sender: Recipient): this(context) {
        update(sender)
    }

    private fun createUnknownRecipientDrawable(publicKey: String? = null): Drawable {
        val color = if(publicKey.isNullOrEmpty()) ContactColors.UNKNOWN_COLOR.toConversationColor(context)
        else avatarUtils.getColorFromKey(publicKey)
        return ResourceContactPhoto(R.drawable.ic_user_filled_custom)
            .asDrawable(context, color, false, resourcePadding)
    }

    fun update(recipient: Recipient) {
        this.recipient = recipient
        recipient.run {
            update(
                address = address,
                profileViewDataType = when {
                    isGroupV2Recipient -> ProfileViewDataType.GroupvV2(
                        customGroupImage = (avatar as? RecipientAvatar.EncryptedRemotePic)?.url
                    )
                    isLegacyGroupRecipient -> ProfileViewDataType.LegacyGroup
                    isCommunityRecipient -> ProfileViewDataType.Community
                    isCommunityInboxRecipient -> ProfileViewDataType.CommunityInbox
                    else -> ProfileViewDataType.OneOnOne
                }
            )
        }
    }

    fun update(
        address: Address,
        profileViewDataType: ProfileViewDataType = ProfileViewDataType.OneOnOne
    ) {
        fun getUserDisplayName(publicKey: String): String = recipientRepository.getRecipientDisplayNameSync(
            Address.fromSerialized(publicKey))

        // group avatar
        if (profileViewDataType is ProfileViewDataType.GroupvV2 || profileViewDataType is ProfileViewDataType.LegacyGroup) {
            // if the group has a custom image, use that
            // other wise make up a double avatar from the first two members
            // if there is only one member then use that member + an unknown icon coloured based on the group id

            // first check if we have a custom image
            if((profileViewDataType as? ProfileViewDataType.GroupvV2)?.customGroupImage != null){
                publicKey =  address.toString()
                displayName = ""
                additionalPublicKey = null // we don't want a second image when there is a custom image set
            } else { // otherwise apply the logic based on members

                val members = if (profileViewDataType is ProfileViewDataType.LegacyGroup) {
                    groupDatabase.getGroupMemberAddresses(address.toGroupString(), true)
                } else {
                    storage.getMembers(address.toString())
                        .map { Address.fromSerialized(it.accountId()) }
                }.sorted().take(2)

                if (members.isEmpty()) {
                    publicKey = ""
                    displayName = ""
                    additionalPublicKey = ""
                    additionalDisplayName = ""
                } else if (members.size == 1) {
                    val pk = members.getOrNull(0)?.toString() ?: ""
                    publicKey = pk
                    displayName = getUserDisplayName(pk)
                    additionalPublicKey =
                        address.toString() // use the group address to later generate a colour based on the group id
                    additionalDisplayName = ""
                } else {
                    val pk = members.getOrNull(0)?.toString() ?: ""
                    publicKey = pk
                    displayName = getUserDisplayName(pk)
                    val apk = members.getOrNull(1)?.toString() ?: ""
                    additionalPublicKey = apk
                    additionalDisplayName = getUserDisplayName(apk)
                }
            }
        } else if(profileViewDataType is ProfileViewDataType.CommunityInbox) {
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
                val address = Address.fromSerialized(publicKey)
                this.recipient = recipientRepository.getRecipientSync(address) ?: Recipient.empty(address)
                this.recipient!!
            }
            
            if (profilePicturesCache[imageView] == recipient) return
            // recipient is mutable so without cloning it the line above always returns true as the changes to the underlying recipient happens on both shared instances
            profilePicturesCache[imageView] = recipient
            val signalProfilePicture: ContactPhoto? = null
            val avatar = (signalProfilePicture as? ProfileContactPhoto)?.avatarObject

            glide.clear(imageView)

            val placeholder = PlaceholderAvatarPhoto(
                publicKey,
                displayName ?: truncateIdForDisplay(publicKey),
                avatarUtils.generateTextBitmap(128, publicKey, displayName)
            )

            if (signalProfilePicture != null && avatar != "0" && avatar != "") {
                val maxSizePx = context.resources.getDimensionPixelSize(R.dimen.medium_profile_picture_size)
                glide.load(signalProfilePicture)
                    .avatarOptions(maxSizePx)
                    .placeholder(createUnknownRecipientDrawable())
                    .error(glide.load(placeholder))
                    .into(imageView)
            } else if(recipient.isGroupRecipient) { // for groups, if we have an unknown it needs to display the unknown icon but with a bg colour based on the group's id
                glide.load(createUnknownRecipientDrawable(publicKey))
                    .centerCrop()
                    .into(imageView)
            } else if (recipient.isCommunityRecipient && avatar == null) {
                glide.load(unknownOpenGroupDrawable)
                    .centerCrop()
                    .into(imageView)
            } else {
                glide.load(placeholder)
                    .placeholder(createUnknownRecipientDrawable())
                    .centerCrop()
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

    sealed interface ProfileViewDataType{
        data object OneOnOne: ProfileViewDataType
        data object LegacyGroup: ProfileViewDataType
        data object Community: ProfileViewDataType
        data object CommunityInbox: ProfileViewDataType
        data class GroupvV2(
            val customGroupImage: String? = null
        ): ProfileViewDataType
    }
}
