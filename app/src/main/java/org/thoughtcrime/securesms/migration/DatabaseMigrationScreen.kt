package org.thoughtcrime.securesms.migration

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.with
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import com.squareup.phrase.Phrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.thoughtcrime.securesms.preferences.ClearAllDataDialog
import org.thoughtcrime.securesms.preferences.ShareLogsDialog
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonModel
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.components.CircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.OutlineButton
import org.thoughtcrime.securesms.ui.components.PrimaryFillButton
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.util.ClearDataUtils


@Composable
fun DatabaseMigrationScreen(
    migrationManager: DatabaseMigrationManager,
    clearDataUtils: ClearDataUtils,
    fm: FragmentManager,
) {
    val scope = rememberCoroutineScope()

    DatabaseMigration(
        state = migrationManager.migrationState.collectAsState().value,
        onRetry = {
            migrationManager.requestMigration(fromRetry = true)
        },
        onExportLogs = {
            ShareLogsDialog {}.show(fm, "share_log")
        },
        onClearData = {
            scope.launch {
                clearDataUtils.clearAllDataAndRestart(Dispatchers.IO)
            }
        },
        onClearDataWithoutLoggingOut = {
            scope.launch {
                clearDataUtils.clearAllDataWithoutLoggingOutAndRestart(Dispatchers.IO)
            }
        }
    )
}

@Composable
@Preview
private fun DatabaseMigration(
    @PreviewParameter(DatabaseMigrationStateProvider::class)
    state: DatabaseMigrationManager.MigrationState,
    onRetry: () -> Unit = {},
    onExportLogs: () -> Unit = {},
    onClearData: () -> Unit = {},
    onClearDataWithoutLoggingOut: () -> Unit = {},
) {
    var showingClearDeviceRestoreWarning by remember { mutableStateOf(false) }
    var showingClearDeviceRestartWarning by remember { mutableStateOf(false) }

    Surface(
        color = LocalColors.current.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(LocalDimensions.current.smallSpacing),
            contentAlignment = Alignment.Center
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    modifier = Modifier.size(120.dp),
                    contentDescription = null
                )

                when (state) {
                    is DatabaseMigrationManager.MigrationState.Completed,
                    DatabaseMigrationManager.MigrationState.Idle -> {
                    }

                    is DatabaseMigrationManager.MigrationState.Error -> {
                        val title =
                            Phrase.from(LocalContext.current, R.string.databaseErrorGeneric)
                                .put(APP_NAME_KEY, stringResource(R.string.app_name))
                                .format()
                                .toString()

                        Text(
                            modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing),
                            text = title,
                            textAlign = TextAlign.Center,
                            style = LocalType.current.base,
                            color = LocalColors.current.text,
                        )

                        Spacer(Modifier.size(LocalDimensions.current.spacing))

                        Row {
                            PrimaryFillButton(
                                text = stringResource(R.string.retry),
                                onClick = onRetry
                            )

                            Spacer(Modifier.size(LocalDimensions.current.smallSpacing))

                            OutlineButton(
                                text = stringResource(R.string.helpReportABugExportLogs),
                                onClick = onExportLogs
                            )
                        }

                        Spacer(Modifier.size(LocalDimensions.current.mediumSpacing))

                        OutlineButton(
                            text = stringResource(R.string.clearDeviceRestore),
                            color = LocalColors.current.danger,
                            onClick = { showingClearDeviceRestoreWarning = true }
                        )
                        Spacer(Modifier.size(LocalDimensions.current.xsSpacing))
                        OutlineButton(
                            text = stringResource(R.string.clearDeviceRestart),
                            color = LocalColors.current.danger,
                            onClick = { showingClearDeviceRestartWarning = true }
                        )

                        Spacer(Modifier.size(LocalDimensions.current.xsSpacing))

                    }

                    is DatabaseMigrationManager.MigrationState.Migrating -> {
                        val currentStep = state.steps.lastOrNull { it.percentage < 100 }
                            ?: state.steps.first()

                        Text(
                            text = currentStep.title,
                            style = LocalType.current.h7,
                            color = LocalColors.current.text,
                        )

                        Spacer(Modifier.size(LocalDimensions.current.xsSpacing))

                        Text(
                            text = currentStep.subtitle,
                            style = LocalType.current.base,
                            color = LocalColors.current.text,
                        )

                        Spacer(Modifier.size(LocalDimensions.current.spacing))

                        SmallCircularProgressIndicator(color = LocalColors.current.text)
                    }
                }
            }
        }
    }

    if (showingClearDeviceRestartWarning) {
        AlertDialog(
            onDismissRequest = { showingClearDeviceRestartWarning = false },
            text = stringResource(R.string.databaseErrorClearDataWarning),
            buttons = listOf(
                DialogButtonModel(
                    text = GetString.FromResId(R.string.clear),
                    color = LocalColors.current.danger,
                    onClick = onClearData
                ),
                DialogButtonModel(
                    text = GetString.FromResId(R.string.cancel),
                    onClick = { showingClearDeviceRestartWarning = false }
                )
            )
        )
    }

    if (showingClearDeviceRestoreWarning) {
        AlertDialog(
            onDismissRequest = { showingClearDeviceRestoreWarning = false },
            text = stringResource(R.string.databaseErrorRestoreDataWarning),
            buttons = listOf(
                DialogButtonModel(
                    text = GetString.FromResId(R.string.clear),
                    color = LocalColors.current.danger,
                    onClick = onClearDataWithoutLoggingOut
                ),
                DialogButtonModel(
                    text = GetString.FromResId(R.string.cancel),
                    onClick = { showingClearDeviceRestoreWarning = false }
                )
            )
        )
    }
}

private class DatabaseMigrationStateProvider :
    PreviewParameterProvider<DatabaseMigrationManager.MigrationState> {
    override val values: Sequence<DatabaseMigrationManager.MigrationState>
        get() = sequenceOf(
            DatabaseMigrationManager.MigrationState.Idle,
            DatabaseMigrationManager.MigrationState.Completed,
            DatabaseMigrationManager.MigrationState.Error(Exception("Test error")),
            DatabaseMigrationManager.MigrationState.Migrating(
                listOf(
                    DatabaseMigrationManager.ProgressStep("Step 1", "A few minutes", 100),
                    DatabaseMigrationManager.ProgressStep(
                        "Step 2 in progress",
                        "Wait a few seconds",
                        40
                    )
                )
            )
        )
}
