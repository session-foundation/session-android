package org.thoughtcrime.securesms.preferences

import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.PublicKeyValidation
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.ui.adaptive.getAdaptiveInfo
import org.thoughtcrime.securesms.ui.components.QRScannerScreen
import org.thoughtcrime.securesms.ui.components.QrImage
import org.thoughtcrime.securesms.ui.components.SessionTabRow
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.setComposeContent
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import javax.inject.Inject

private val TITLES = listOf(R.string.view, R.string.scan)

@AndroidEntryPoint
class QRCodeActivity : ScreenLockActionBarActivity() {

    @Inject
    lateinit var loginStateRepository: LoginStateRepository

    override val applyDefaultWindowInsets: Boolean
        get() = false

    private val errors = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )


    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)

        // only apply inset padding at the top so that the bottom qr scanning can go all the way
        findViewById<View>(android.R.id.content).applySafeInsetsPaddings(
            consumeInsets = false,
            applyBottom = false,
        )

        supportActionBar!!.title = resources.getString(R.string.qrCode)

        setComposeContent {
            Tabs(
                loginStateRepository.requireLocalNumber(),
                errors.asSharedFlow(),
                onScan = ::onScan
            )
        }
    }

    private fun onScan(string: String) {
        if (!PublicKeyValidation.isValid(string)) {
            errors.tryEmit(getString(R.string.qrNotAccountId))
        } else if (!isFinishing) {
            val address = Address.fromSerialized(string) as Address.Conversable
            startActivity(
                ConversationActivityV2.createIntent(this, address = address)
                    .setDataAndType(intent.data, intent.type)
                    .addFlags(FLAG_ACTIVITY_SINGLE_TOP)
            )

            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
}

@Composable
private fun Tabs(accountId: String, errors: Flow<String>, onScan: (String) -> Unit) {
    val pagerState = rememberPagerState { TITLES.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalColors.current.background)
    ) {
        SessionTabRow(pagerState, TITLES)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (TITLES[page]) {
                R.string.view -> QrPage(accountId)
                R.string.scan -> QRScannerScreen(errors, onScan = onScan)
            }
        }
    }
}

@Composable
fun QrPage(string: String) {
    if (getAdaptiveInfo().isLandscape) {
        LandscapeContent(string)
    } else {
        PortraitContent(string)
    }
}

@Composable
private fun PortraitContent(string: String) {
    Column(
        modifier = Modifier
            .background(LocalColors.current.background)
            .padding(horizontal = LocalDimensions.current.mediumSpacing)
            .fillMaxSize()
    ) {
        QrImage(
            string = string,
            modifier = Modifier
                .padding(
                    top = LocalDimensions.current.mediumSpacing,
                    bottom = LocalDimensions.current.xsSpacing
                )
                .qaTag(R.string.AccessibilityId_qrCode),
            icon = R.drawable.session
        )

        Text(
            text = stringResource(R.string.accountIdYoursDescription),
            color = LocalColors.current.textSecondary,
            textAlign = TextAlign.Center,
            style = LocalType.current.small
        )
    }
}

@Composable
private fun LandscapeContent(string: String) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalColors.current.background)
            .padding(horizontal = LocalDimensions.current.mediumSpacing)
    ) {
        // Scale QR to the shorter side to avoid overflow in landscape. Clamp for sanity
        val shortest: Dp = min(maxWidth, maxHeight)
        val qrSide = (shortest * 0.70f).coerceIn(
            LocalDimensions.current.minimumImgClamp,
            LocalDimensions.current.maximumImgClamp
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(vertical = LocalDimensions.current.spacing), // vertical + horizontal centering
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)

        ) {
            QrImage(
                string = string,
                modifier = Modifier
                    .size(qrSide)
                    .qaTag(R.string.AccessibilityId_qrCode),
                icon = R.drawable.session
            )

            Text(
                text = stringResource(R.string.accountIdYoursDescription),
                color = LocalColors.current.textSecondary,
                textAlign = TextAlign.Center,
                style = LocalType.current.small
            )
        }
    }
}
