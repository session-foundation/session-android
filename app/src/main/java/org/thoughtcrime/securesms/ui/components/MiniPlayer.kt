package org.thoughtcrime.securesms.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.audio.model.PlayableAudio
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import androidx.core.net.toUri
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.audio.model.AudioPlaybackState
import org.thoughtcrime.securesms.ui.border
import org.thoughtcrime.securesms.ui.theme.LocalType

@Composable
fun AudioMiniPlayer(
    modifier: Modifier = Modifier,
    audio: AudioPlaybackState.Active,
    onPlayerTap: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onPlaybackSpeedToggle: () -> Unit = {},
    onClose: () -> Unit = {}
){
    Row(
        modifier = modifier.fillMaxWidth()
            .padding(
                horizontal = 2.dp,
                vertical = LocalDimensions.current.xxxsSpacing
            )
            .clip(MaterialTheme.shapes.small)
            .background(LocalColors.current.backgroundSecondary)
            .border(
                LocalDimensions.current.borderStroke,
                LocalColors.current.borders,
                MaterialTheme.shapes.small
            )
            .clickable(onClick = onPlayerTap),
        verticalAlignment = Alignment.CenterVertically

    ){
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
        ) {
            Icon(
                painter = painterResource(id = if (audio is AudioPlaybackState.Active.Playing)
                    R.drawable.pause
                    else R.drawable.play
                ),
                tint = LocalColors.current.text,
                contentDescription = stringResource(if (audio is AudioPlaybackState.Active.Playing)
                    R.string.playpause_button_pause
                    else R.string.playpause_button_play)
            )
        }

        Spacer(Modifier.width(LocalDimensions.current.xxsSpacing))

        Text(
            modifier = Modifier.weight(1f),
            text = audio.senderOrFile(),
            color = LocalColors.current.text,
            style = LocalType.current.base
        )

        Spacer(Modifier.width(LocalDimensions.current.xxsSpacing))

        Text(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .background(color = LocalColors.current.backgroundTertiary)
                .clickable(onClick = onPlaybackSpeedToggle)
                .padding(LocalDimensions.current.xxsSpacing),
            text = audio.playbackSpeedFormatted(),
            color = LocalColors.current.text,
            style = LocalType.current.base
        )

        Spacer(Modifier.width(LocalDimensions.current.xxsSpacing))

        IconButton(
            onClick = onClose,
            modifier = Modifier
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_x),
                tint = LocalColors.current.text,
                contentDescription = stringResource(R.string.close)
            )
        }
    }
}


@Preview
@Composable
fun PreviewMiniPlayer(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        AudioMiniPlayer(
            audio = AudioPlaybackState.Active.Playing(
                playable = PlayableAudio(
                    messageId = MessageId(id = 1, false),
                    uri = "".toUri(),
                    thread = Address.Standard(AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234")),
                    isVoiceNote = true,
                    durationMs = 6340,
                    senderName = "Atreyu",
                    filename = "audio.mp3",
                    avatar = null
                ),
                positionMs = 0,
                durationMs = 6340,
                bufferedPositionMs = 0,
                playbackSpeed = 1f,
                isBuffering = false
            ),
            onPlayerTap = {},
            onPlayPause = {},
            onPlaybackSpeedToggle = {},
            onClose = {}
        )
    }
}

@Preview
@Composable
fun PreviewMiniPlayerPaused(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        AudioMiniPlayer(
            audio = AudioPlaybackState.Active.Paused(
                playable = PlayableAudio(
                    messageId = MessageId(id = 1, false),
                    uri = "".toUri(),
                    thread = Address.Standard(AccountId("05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234")),
                    isVoiceNote = true,
                    durationMs = 6340,
                    senderName = "Atreyu",
                    filename = "audio.mp3",
                    avatar = null
                ),
                positionMs = 0,
                durationMs = 6340,
                bufferedPositionMs = 0,
                playbackSpeed = 1f,
                isBuffering = false
            ),
            onPlayerTap = {},
            onPlayPause = {},
            onPlaybackSpeedToggle = {},
            onClose = {}
        )
    }
}
