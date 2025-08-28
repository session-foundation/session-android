package org.thoughtcrime.securesms.util

import android.content.Context
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
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import coil3.size.Precision
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.pro.ProStatusManager
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarUtils @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val recipientRepository: RecipientRepository,
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
            getUIDataFromRecipient(recipientRepository.getRecipient(Address.fromSerialized(accountId)))
        }

    fun getUIDataFromRecipient(recipient: Recipient?): AvatarUIData {
        if (recipient == null) {
            return AvatarUIData(elements = emptyList())
        }

        val groupData = recipient.data as? RecipientData.GroupLike
        val firstMember = groupData?.firstMember
        val secondMember = groupData?.secondMember

        val elements = buildList {
            when {
                // The recipient is group like and have two members, use both images
                firstMember != null && secondMember != null -> {
                    add(getUIElementForRecipient(firstMember))
                    add(getUIElementForRecipient(secondMember))
                }

                // The recipient is group like and has only one member, use that member + an unknown icon
                firstMember != null -> {
                    add(getUIElementForRecipient(firstMember))
                    add(
                        AvatarUIElement(
                            color = Color(getColorFromKey(recipient.address.toString()))
                        )
                    )
                }

                else -> {
                    add(getUIElementForRecipient(recipient))
                }
            }
        }

        return AvatarUIData(elements = elements)
    }

    private fun getUIElementForRecipient(recipient: Recipient): AvatarUIElement {
        // name
        val name = recipient.displayName()

        val defaultColor = Color(getColorFromKey(recipient.address.toString()))

        // custom image
        val (remoteFile, customIcon, color) = when {
            // use custom image if there is one
            recipient.avatar != null -> Triple(recipient.avatar!!, null, defaultColor)

            // communities without a custom image should use a default image
            recipient.isCommunityRecipient -> Triple(null, R.drawable.session_logo, null)
            else -> Triple(null, null, defaultColor)
        }

        return AvatarUIElement(
            name = extractLabel(name),
            color = color,
            icon = customIcon,
            remoteFile = remoteFile,
            freezeFrame = proStatusManager.freezeFrameForUser(recipient.address)
        )
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
    fun isSingleCustomAvatar() = elements.size == 1 && elements[0].remoteFile != null
}

data class AvatarUIElement(
    val name: String? = null,
    val color: Color? = null,
    @DrawableRes val icon: Int? = null,
    val remoteFile: RemoteFile? = null,
    val freezeFrame: Boolean = true,
)

sealed class AvatarBadge(@DrawableRes val icon: Int){
    data object None: AvatarBadge(0)
    data object Admin: AvatarBadge(R.drawable.ic_crown_custom_enlarged)
    data class Custom(@DrawableRes val iconRes: Int): AvatarBadge(iconRes)
}

fun ImageRequest.Builder.avatarOptions(
    sizePx: Int,
    freezeFrame: Boolean
): ImageRequest.Builder = this.size(sizePx, sizePx)
    .precision(Precision.INEXACT)
    .apply {
        if (freezeFrame) {
            decoderFactory(BitmapFactoryDecoder.Factory())
        }
    }