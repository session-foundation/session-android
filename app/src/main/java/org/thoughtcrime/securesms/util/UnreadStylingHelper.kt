package org.thoughtcrime.securesms.util

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import network.loki.messenger.R

object UnreadStylingHelper {

    fun getUnreadBackground(context: Context, isUnread: Boolean): Drawable? {
        val drawableRes = if (isUnread) {
            ContextCompat.getDrawable(context, R.drawable.conversation_unread_background)
        } else {
            ContextCompat.getDrawable(context, R.drawable.conversation_view_background)
        }

        return drawableRes
    }

    fun formatUnreadCount(unreadCount: Int): String? {
        return when {
            unreadCount == 0 -> null
            unreadCount < 1000 -> unreadCount.toString()
            else -> "999+"
        }
    }

    fun getUnreadTypeface(isUnread: Boolean): Typeface {
        return if (isUnread) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun getAccentBackground(context: Context) : ColorDrawable {
        val accentColor = context.getAccentColor()
        val background = ColorDrawable(accentColor)

        return background
    }
}