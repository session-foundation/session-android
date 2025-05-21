package org.thoughtcrime.securesms.conversation.v2.messages

import android.widget.TextView
import androidx.core.view.isVisible
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.DateUtils

private val maxTimeBetweenBreaksMS =  5.minutes.inWholeMilliseconds

fun TextView.showDateBreak(message: MessageRecord, previous: MessageRecord?, dateUtils: DateUtils) {
    val showDateBreak = (previous == null || message.timestamp - previous.timestamp > maxTimeBetweenBreaksMS)
    isVisible = showDateBreak
    text = if (showDateBreak) dateUtils.getDisplayFormattedTimeSpanString(
        Locale.getDefault(),
        message.timestamp
    ) else ""
}