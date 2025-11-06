package org.thoughtcrime.securesms.debugmenu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.ui.theme.monospace
import java.time.Duration
import java.time.Instant


@Composable
fun DebugLogScreen(
    viewModel: DebugMenuViewModel,
    onBack: () -> Unit,
){
    val logs by viewModel.debugLogs.collectAsState()

    DebugLogs(
        logs = logs,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogs(
    logs: List<DebugLogData>,
    onBack: () -> Unit,
){
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            // App bar
            BackAppBar(title = "Debug Logs", onBack = onBack)
        },
    ) { contentPadding ->
        val scrollState = rememberLazyListState()

        Cell(
            modifier = Modifier.fillMaxSize()
                .padding(contentPadding)
                .padding(LocalDimensions.current.smallSpacing),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(LocalDimensions.current.smallSpacing),
                verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing),
                state = scrollState
            ) {
                items(items = logs){ log ->
                    Column {
                        Row {
                            Text(
                                text = log.formattedDate,
                                style = LocalType.current.small.bold()
                            )

                            Spacer(Modifier.width(LocalDimensions.current.xxsSpacing))

                            Text(
                                text = "[${log.group.label}]",
                                style = LocalType.current.small.bold().copy(
                                    color = log.group.color
                                )
                            )
                        }

                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = log.message,
                            style = LocalType.current.large.monospace().bold()
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun PrewviewDebugLogs(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        DebugLogs(
            logs = listOf(
                DebugLogData(
                    message = "This is a log",
                    group = DebugLogGroup.PRO_SUBSCRIPTION,
                    date = Instant.now(),
                    formattedDate = "10: 36"
                ),
                DebugLogData(
                    message = "This is another log",
                    group = DebugLogGroup.PRO_SUBSCRIPTION,
                    date = Instant.now() - Duration.ofMinutes(4),
                    formattedDate = "10: 36"
                ),
                DebugLogData(
                    message = "This is also a log",
                    group = DebugLogGroup.AVATAR,
                    date = Instant.now() - Duration.ofMinutes(7),
                    formattedDate = "10: 36"
                ),
            ),
            onBack = {}
        )
    }
}