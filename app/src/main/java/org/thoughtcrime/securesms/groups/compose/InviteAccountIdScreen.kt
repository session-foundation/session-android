package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.BackAppBar

@Composable
fun InviteAccountIdScreen() {
    InviteAccountId()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteAccountId() {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            BackAppBar(
                title = stringResource(id = R.string.membersInviteTitle),
                onBack = { },
            )
        }
    ) { paddings ->
        Column(
            modifier = Modifier
                .padding(paddings)
                .consumeWindowInsets(paddings),
        ) {

        }
    }
}
