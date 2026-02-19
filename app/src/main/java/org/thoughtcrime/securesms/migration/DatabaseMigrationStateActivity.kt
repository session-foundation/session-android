package org.thoughtcrime.securesms.migration

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeActivity
import org.thoughtcrime.securesms.ui.components.LogExporter
import org.thoughtcrime.securesms.util.ClearDataUtils
import javax.inject.Inject

@AndroidEntryPoint
class DatabaseMigrationStateActivity : FullComposeActivity() {
    @Inject
    lateinit var migrationManager: DatabaseMigrationManager

    @Inject
    lateinit var clearDataUtils: ClearDataUtils

    @Inject
    lateinit var exporter: LogExporter

    @Composable
    override fun ComposeContent() {
        DatabaseMigrationScreen(
            migrationManager = migrationManager,
            fm = supportFragmentManager,
            exporter = exporter,
            clearDataUtils = clearDataUtils,
        )

        val state = migrationManager.migrationState.collectAsState().value
        LaunchedEffect(state) {
            if (state == DatabaseMigrationManager.MigrationState.Completed) {
                IntentCompat.getParcelableExtra(intent, "next_intent", Intent::class.java)
                    ?.let(::startActivity)
                finish()
            }
        }
    }
}
