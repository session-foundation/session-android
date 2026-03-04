package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.thoughtcrime.securesms.ui.theme.LocalType

@Composable
fun ControlMessage(
    data: MessageViewData,
    modifier: Modifier = Modifier
) {
    // Control messages are usually simple text or system info
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Control Message WIP", style = LocalType.current.small)
    }
}