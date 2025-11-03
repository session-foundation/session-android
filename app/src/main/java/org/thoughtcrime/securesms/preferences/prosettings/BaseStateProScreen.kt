package org.thoughtcrime.securesms.preferences.prosettings

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.CircularProgressIndicator
import org.thoughtcrime.securesms.util.State


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T>BaseStateProScreen(
    state: State<T>,
    onBack: () -> Unit,
    successContent: @Composable (T) -> Unit
) {
    when (state) {
        is State.Error -> {
            // show a toast and go back to pro settings home screen
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                Toast.makeText(context, R.string.errorGeneric, Toast.LENGTH_LONG).show()
                onBack()
            }
        }

        is State.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                BackAppBar(title = "", onBack = onBack)

                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        is State.Success<T> -> successContent(state.value)
    }
}