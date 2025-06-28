package org.thoughtcrime.securesms.debugmenu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.upsertContact
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.hexEncodedPublicKey
import org.thoughtcrime.securesms.crypto.KeyPairUtilities
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.tokenpage.TokenPageNotificationManager
import org.thoughtcrime.securesms.util.ClearDataUtils
import java.time.ZonedDateTime
import javax.inject.Inject


@HiltViewModel
class DebugMenuViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textSecurePreferences: TextSecurePreferences,
    private val tokenPageNotificationManager: TokenPageNotificationManager,
    private val configFactory: ConfigFactory,
    private val storage: StorageProtocol,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val clearDataUtils: ClearDataUtils,
    private val threadDb: ThreadDatabase,
    private val recipientDatabase: RecipientDatabase,
    private val attachmentDatabase: AttachmentDatabase,
) : ViewModel() {
    private val TAG = "DebugMenu"

    private val _uiState = MutableStateFlow(
        UIState(
            currentEnvironment = textSecurePreferences.getEnvironment().label,
            environments = Environment.entries.map { it.label },
            snackMessage = null,
            showEnvironmentWarningDialog = false,
            showLoadingDialog = false,
            showDeprecatedStateWarningDialog = false,
            hideMessageRequests = textSecurePreferences.hasHiddenMessageRequests(),
            hideNoteToSelf = textSecurePreferences.hasHiddenNoteToSelf(),
            forceDeprecationState = deprecationManager.deprecationStateOverride.value,
            availableDeprecationState = listOf(null) + LegacyGroupDeprecationManager.DeprecationState.entries.toList(),
            deprecatedTime = deprecationManager.deprecatedTime.value,
            deprecatingStartTime = deprecationManager.deprecatingStartTime.value,
            forceCurrentUserAsPro = textSecurePreferences.forceCurrentUserAsPro(),
            forceIncomingMessagesAsPro = textSecurePreferences.forceIncomingMessagesAsPro(),
            forcePostPro = textSecurePreferences.forcePostPro(),
        )
    )
    val uiState: StateFlow<UIState>
        get() = _uiState

    private var temporaryEnv: Environment? = null

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var temporaryDeprecatedState: LegacyGroupDeprecationManager.DeprecationState? = null

    @OptIn(ExperimentalStdlibApi::class)
    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ChangeEnvironment -> changeEnvironment()

            is Commands.HideEnvironmentWarningDialog -> _uiState.value =
                _uiState.value.copy(showEnvironmentWarningDialog = false)

            is Commands.ShowEnvironmentWarningDialog ->
                showEnvironmentWarningDialog(command.environment)

            is Commands.ScheduleTokenNotification -> {
                tokenPageNotificationManager.scheduleTokenPageNotification( true)
                Toast.makeText(context, "Scheduled a notification for 10s from now", Toast.LENGTH_LONG).show()
            }

            is Commands.Copy07PrefixedBlindedPublicKey -> {
                val secretKey = storage.getUserED25519KeyPair()?.secretKey?.data
                    ?: throw (FileServerApi.Error.NoEd25519KeyPair)
                val userBlindedKeys = BlindKeyAPI.blindVersionKeyPair(secretKey)

                val clip = ClipData.newPlainText("07-prefixed Version Blinded Public Key",
                    "07" + userBlindedKeys.pubKey.data.toHexString())
                clipboardManager.setPrimaryClip(ClipData(clip))

                // Show a toast if the version is below Android 13
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Copied key to clipboard", Toast.LENGTH_SHORT).show()
                }
            }

            is Commands.CopyAccountId -> {
                val accountId = textSecurePreferences.getLocalNumber()
                val clip = ClipData.newPlainText("Account ID", accountId)
                clipboardManager.setPrimaryClip(ClipData(clip))

                // Show a toast if the version is below Android 13
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(
                        context,
                        "Copied account ID to clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            is Commands.HideMessageRequest -> {
                textSecurePreferences.setHasHiddenMessageRequests(command.hide)
                _uiState.value = _uiState.value.copy(hideMessageRequests = command.hide)
            }

            is Commands.HideNoteToSelf -> {
                textSecurePreferences.setHasHiddenNoteToSelf(command.hide)
                configFactory.withMutableUserConfigs {
                    it.userProfile.setNtsPriority(if(command.hide) PRIORITY_HIDDEN else PRIORITY_VISIBLE)
                }
                _uiState.value = _uiState.value.copy(hideNoteToSelf = command.hide)
            }

            is Commands.OverrideDeprecationState -> {
                if(temporaryDeprecatedState == null) return

                _uiState.value = _uiState.value.copy(forceDeprecationState = temporaryDeprecatedState,
                    showLoadingDialog = true)

                deprecationManager.overrideDeprecationState(temporaryDeprecatedState)


                // restart app
                viewModelScope.launch {
                    delay(500) // giving time to save data
                    clearDataUtils.restartApplication()
                }
            }

            is Commands.OverrideDeprecatedTime -> {
                deprecationManager.overrideDeprecatedTime(command.time)
                _uiState.value = _uiState.value.copy(deprecatedTime = command.time)
            }

            is Commands.OverrideDeprecatingStartTime -> {
                deprecationManager.overrideDeprecatingStartTime(command.time)
                _uiState.value = _uiState.value.copy(deprecatingStartTime = command.time)
            }

            is Commands.HideDeprecationChangeDialog ->
                _uiState.value = _uiState.value.copy(showDeprecatedStateWarningDialog = false)

            is Commands.ShowDeprecationChangeDialog ->
                showDeprecatedStateWarningDialog(command.state)

            is Commands.ClearTrustedDownloads -> {
                clearTrustedDownloads()
            }

            is Commands.GenerateContacts -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(showLoadingDialog = true) }

                    withContext(Dispatchers.Default) {
                        val keys = List(command.count) {
                            KeyPairUtilities.generate()
                        }

                        configFactory.withMutableUserConfigs { configs ->
                            for ((index, key) in keys.withIndex()) {
                                configs.contacts.upsertContact(
                                    accountId = key.x25519KeyPair.hexEncodedPublicKey
                                ) {
                                    name = "${command.prefix}$index"
                                    approved = true
                                    approvedMe = true
                                }
                            }
                        }
                    }

                    _uiState.update { it.copy(showLoadingDialog = false) }
                }
            }

            is Commands.ForceCurrentUserAsPro -> {
                textSecurePreferences.setForceCurrentUserAsPro(command.set)
                _uiState.update {
                    it.copy(forceCurrentUserAsPro = command.set)
                }
            }

            is Commands.ForceIncomingMessagesAsPro -> {
                textSecurePreferences.setForceIncomingMessagesAsPro(command.set)
                _uiState.update {
                    it.copy(forceIncomingMessagesAsPro = command.set)
                }
            }

            is Commands.ForcePostPro -> {
                textSecurePreferences.setForcePostPro(command.set)
                _uiState.update {
                    it.copy(forcePostPro = command.set)
                }
            }
        }
    }

    private fun showEnvironmentWarningDialog(environment: String) {
        if(environment == _uiState.value.currentEnvironment) return
        val env = Environment.entries.firstOrNull { it.label == environment } ?: return

        temporaryEnv = env

        _uiState.value = _uiState.value.copy(showEnvironmentWarningDialog = true)
    }

    private fun changeEnvironment() {
        val env = temporaryEnv ?: return

        // show a loading state
        _uiState.value = _uiState.value.copy(
            showEnvironmentWarningDialog = false,
            showLoadingDialog = true
        )

        // clear remote and local data, then restart the app
        viewModelScope.launch {
            val success = runCatching { clearDataUtils.clearAllData() } .isSuccess

            if(success){
                // save the environment
                textSecurePreferences.setEnvironment(env)
                delay(500)
                clearDataUtils.restartApplication()
            } else {
                _uiState.value = _uiState.value.copy(
                    showEnvironmentWarningDialog = false,
                    showLoadingDialog = false
                )
                Log.e(TAG, "Failed to force sync when deleting data")
                _uiState.value = _uiState.value.copy(snackMessage = "Sorry, something went wrong...")
                return@launch
            }
        }
    }

    private fun showDeprecatedStateWarningDialog(state: LegacyGroupDeprecationManager.DeprecationState?) {
        if(state == _uiState.value.forceDeprecationState) return

        temporaryDeprecatedState = state

        _uiState.value = _uiState.value.copy(showDeprecatedStateWarningDialog = true)
    }

    private fun clearTrustedDownloads() {
        // show a loading state
        _uiState.value = _uiState.value.copy(
            showEnvironmentWarningDialog = false,
            showLoadingDialog = true
        )

        // clear trusted downloads for all recipients
        viewModelScope.launch {
            val conversations: List<ThreadRecord> = threadDb.approvedConversationList.use { openCursor ->
                threadDb.readerFor(openCursor).run { generateSequence { next }.toList() }
            }

            conversations.filter { !it.recipient.isLocalNumber }.forEach {
                recipientDatabase.setAutoDownloadAttachments(it.recipient, false)
            }

            // set all attachments back to pending
            attachmentDatabase.allAttachments.forEach {
                attachmentDatabase.setTransferState(it.mmsId, it.attachmentId, AttachmentState.PENDING.value)
            }

            Toast.makeText(context, "Cleared!", Toast.LENGTH_LONG).show()

            // hide loading
            _uiState.value = _uiState.value.copy(
                showEnvironmentWarningDialog = false,
                showLoadingDialog = false
            )
        }
    }

    data class UIState(
        val currentEnvironment: String,
        val environments: List<String>,
        val snackMessage: String?,
        val showEnvironmentWarningDialog: Boolean,
        val showLoadingDialog: Boolean,
        val showDeprecatedStateWarningDialog: Boolean,
        val hideMessageRequests: Boolean,
        val hideNoteToSelf: Boolean,
        val forceCurrentUserAsPro: Boolean,
        val forceIncomingMessagesAsPro: Boolean,
        val forcePostPro: Boolean,
        val forceDeprecationState: LegacyGroupDeprecationManager.DeprecationState?,
        val availableDeprecationState: List<LegacyGroupDeprecationManager.DeprecationState?>,
        val deprecatedTime: ZonedDateTime,
        val deprecatingStartTime: ZonedDateTime,
    )

    sealed class Commands {
        object ChangeEnvironment : Commands()
        data class ShowEnvironmentWarningDialog(val environment: String) : Commands()
        object HideEnvironmentWarningDialog : Commands()
        object ScheduleTokenNotification : Commands()
        object Copy07PrefixedBlindedPublicKey : Commands()
        object CopyAccountId : Commands()
        data class HideMessageRequest(val hide: Boolean) : Commands()
        data class HideNoteToSelf(val hide: Boolean) : Commands()
        data class ForceCurrentUserAsPro(val set: Boolean) : Commands()
        data class ForceIncomingMessagesAsPro(val set: Boolean) : Commands()
        data class ForcePostPro(val set: Boolean) : Commands()
        data class ShowDeprecationChangeDialog(val state: LegacyGroupDeprecationManager.DeprecationState?) : Commands()
        object HideDeprecationChangeDialog : Commands()
        object OverrideDeprecationState : Commands()
        data class OverrideDeprecatedTime(val time: ZonedDateTime) : Commands()
        data class OverrideDeprecatingStartTime(val time: ZonedDateTime) : Commands()
        object ClearTrustedDownloads: Commands()
        data class GenerateContacts(val prefix: String, val count: Int): Commands()
    }
}