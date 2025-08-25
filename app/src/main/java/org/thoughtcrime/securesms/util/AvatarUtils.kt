package org.thoughtcrime.securesms.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestOptions
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.avatars.ContactPhoto
import org.session.libsession.avatars.ProfileContactPhoto
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.pro.ProStatusManager
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarUtils @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usernameUtils: UsernameUtils,
    private val groupDatabase: GroupDatabase, // for legacy groups
    private val storage: Lazy<StorageProtocol>,
    private val proStatusManager: ProStatusManager,
) {
    // Hardcoded possible bg colors for avatar backgrounds
    private val avatarBgColors = arrayOf(
        ContextCompat.getColor(context, R.color.accent_green),
        ContextCompat.getColor(context, R.color.accent_blue),
        ContextCompat.getColor(context, R.color.accent_yellow),
        ContextCompat.getColor(context, R.color.accent_pink),
        ContextCompat.getColor(context, R.color.accent_purple),
        ContextCompat.getColor(context, R.color.accent_orange),
        ContextCompat.getColor(context, R.color.accent_red),
    )

    suspend fun getUIDataFromAccountId(accountId: String): AvatarUIData =
        withContext(Dispatchers.Default) {
            getUIDataFromRecipient(Recipient.from(context, Address.fromSerialized(accountId), false))
        }

    suspend fun getUIDataFromRecipient(recipient: Recipient?): AvatarUIData {
        if (recipient == null) {
            return AvatarUIData(elements = emptyList())
        }

        return withContext(Dispatchers.Default) {
            // set up the data based on the conversation type
            val elements = mutableListOf<AvatarUIElement>()

            // Groups can have a double avatar setup, if they don't have a custom image
            if (recipient.isGroupRecipient) {
                // if the group has a custom image, use that
                // other wise make up a double avatar from the first two members
                // if there is only one member then use that member + an unknown icon coloured based on the group id
                if (recipient.profileAvatar != null) {
                    elements.add(getUIElementForRecipient(recipient))
                } else {
                    val members = if (recipient.isLegacyGroupRecipient) {
                        groupDatabase.getGroupMemberAddresses(
                            recipient.address.toGroupString(),
                            true
                        )
                    } else {
                        storage.get().getMembers(recipient.address.toString())
                            .map { Address.fromSerialized(it.accountId()) }
                    }.sorted().take(2)

                    when (members.size) {
                        0 -> elements.add(AvatarUIElement())

                        1 -> {
                            // when we only have one member, use that member as one of the two avatar
                            // and the second should be the unknown icon with a colour based on the group id
                            elements.add(
                                getUIElementForRecipient(
                                    Recipient.from(
                                        context, Address.fromSerialized(
                                            members[0].toString()
                                        ), false
                                    )
                                )
                            )

                            elements.add(
                                AvatarUIElement(
                                    color = Color(getColorFromKey(recipient.address.toString()))
                                )
                            )
                        }

                        else -> {
                            members.forEach {
                                elements.add(
                                    getUIElementForRecipient(
                                        Recipient.from(context, it, false)
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                elements.add(getUIElementForRecipient(recipient))
            }

            AvatarUIData(
                elements = elements
            )
        }
    }

    private fun getUIElementForRecipient(recipient: Recipient): AvatarUIElement {
        // name
        val name = if(recipient.isLocalNumber) usernameUtils.getCurrentUsernameWithAccountIdFallback()
        else recipient.name

        val defaultColor = Color(getColorFromKey(recipient.address.toString()))

        // custom image
        val (contactPhoto, customIcon, color) = when {
            // use custom image if there is one
            hasAvatar(recipient.contactPhoto) -> Triple(recipient.contactPhoto, null, defaultColor)

            // communities without a custom image should use a default image
            recipient.isCommunityRecipient -> Triple(null, R.drawable.session_logo, null)
            else -> Triple(null, null, defaultColor)
        }

        return AvatarUIElement(
            name = extractLabel(name),
            color = color,
            icon = customIcon,
            contactPhoto = contactPhoto,
            freezeFrame = proStatusManager.freezeFrameForUser(recipient.address)
        )
    }

    private fun hasAvatar(contactPhoto: ContactPhoto?): Boolean {
        val avatar = (contactPhoto as? ProfileContactPhoto)?.avatarObject
        return contactPhoto != null && avatar != "0" && avatar != ""
    }

    fun getColorFromKey(hashString: String): Int {
        val hash: Long
        if (hashString.length >= 12 && hashString.matches(Regex("^[0-9A-Fa-f]+\$"))) {
            hash = getSha512(hashString).substring(0 until 12).toLong(16)
        } else {
            hash = 0
        }

        return avatarBgColors[(hash % avatarBgColors.size).toInt()]
    }

    fun generateTextBitmap(pixelSize: Int, hashString: String, displayName: String?): BitmapDrawable {
        val colorPrimary = getColorFromKey(hashString)

        val labelText = when {
            !TextUtils.isEmpty(displayName) -> extractLabel(displayName!!.capitalize(Locale.ROOT))
            !TextUtils.isEmpty(hashString) -> extractLabel(hashString)
            else -> EMPTY_LABEL
        }

        val bitmap = createBitmap(pixelSize, pixelSize)
        val canvas = Canvas(bitmap)

        // Draw background/frame
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = colorPrimary
        canvas.drawCircle(pixelSize.toFloat() / 2, pixelSize.toFloat() / 2, pixelSize.toFloat() / 2, paint)

        // Draw text
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textPaint.textSize = pixelSize * 0.5f
        textPaint.color = android.graphics.Color.WHITE
        val areaRect = Rect(0, 0, pixelSize, pixelSize)
        val textBounds = RectF(areaRect)
        textBounds.right = textPaint.measureText(labelText)
        textBounds.bottom = textPaint.descent() - textPaint.ascent()
        textBounds.left += (areaRect.width() - textBounds.right) * 0.5f
        textBounds.top += (areaRect.height() - textBounds.bottom) * 0.5f
        canvas.drawText(labelText, textBounds.left, textBounds.top - textPaint.ascent(), textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun getSha512(input: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-512").digest(input.toByteArray())

        // Convert byte array into signum representation
        val no = BigInteger(1, messageDigest)

        // Convert message digest into hex value
        var hashText: String = no.toString(16)

        // Add preceding 0s to make it 32 bytes
        if (hashText.length < 128) {
            val sb = StringBuilder()
            for (i in 0 until 128 - hashText.length) {
                sb.append('0')
            }
            hashText = sb.append(hashText).toString()
        }

        return hashText
    }

    companion object {
        private val EMPTY_LABEL = "0"

        fun extractLabel(content: String): String {
            val trimmedContent = content.trim()
            if (trimmedContent.isEmpty()) return EMPTY_LABEL
            return if (trimmedContent.length > 2 && IdPrefix.fromValue(trimmedContent) != null) {
                trimmedContent[2].toString()
            } else {
                val splitWords = trimmedContent.split(Regex("\\W"))
                if (splitWords.size < 2) {
                    trimmedContent.take(2)
                } else {
                    splitWords.filter { word -> word.isNotEmpty() }.take(2).map { it.first() }.joinToString("")
                }
            }.uppercase()
        }
    }
}

data class AvatarUIData(
    val elements: List<AvatarUIElement>,
){
    /**
     * Helper function to determine if an avatar is composed of a single element, which is
     * a custom photo.
     * This is used for example to know when to display a fullscreen avatar on tap
     */
    fun isSingleCustomAvatar() = elements.size == 1 && elements[0].contactPhoto != null
}

data class AvatarUIElement(
    val name: String? = null,
    val color: Color? = null,
    @DrawableRes val icon: Int? = null,
    val contactPhoto: ContactPhoto? = null,
    val freezeFrame: Boolean = true
)

sealed class AvatarBadge(@DrawableRes val icon: Int){
    data object None: AvatarBadge(0)
    data object Admin: AvatarBadge(R.drawable.ic_crown_custom_enlarged)
    data class Custom(@DrawableRes val iconRes: Int): AvatarBadge(iconRes)
}

// Helper function for our common avatar Glide options
fun <T>RequestBuilder<T>.avatarOptions(
    sizePx: Int,
    freezeFrame: Boolean
): RequestBuilder<T> = this.override(sizePx)
    .dontTransform()
    .diskCacheStrategy(DiskCacheStrategy.NONE)
    .optionalTransform(CenterCrop())
    .run {
        if(freezeFrame){
            this.dontAnimate()
                .apply(RequestOptions.decodeTypeOf(Bitmap::class.java))
        } else this
    }

