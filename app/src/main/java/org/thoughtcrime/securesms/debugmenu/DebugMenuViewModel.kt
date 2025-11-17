package org.thoughtcrime.securesms.debugmenu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.file_server.FileServer
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.upsertContact
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.tokenpage.TokenPageNotificationManager
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.util.ClearDataUtils
import org.thoughtcrime.securesms.util.DateUtils
import java.time.ZonedDateTime


@HiltViewModel(assistedFactory = DebugMenuViewModel.Factory::class)
class DebugMenuViewModel @AssistedInject constructor(
    @Assisted private val navigator: UINavigator<DebugMenuDestination>,
    @param:ApplicationContext private val context: Context,
    private val textSecurePreferences: TextSecurePreferences,
    private val tokenPageNotificationManager: TokenPageNotificationManager,
    private val configFactory: ConfigFactory,
    private val storage: StorageProtocol,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val clearDataUtils: ClearDataUtils,
    private val recipientDatabase: RecipientSettingsDatabase,
    private val attachmentDatabase: AttachmentDatabase,
    private val conversationRepository: ConversationRepository,
    private val databaseInspector: DatabaseInspector,
    private val tokenFetcher: TokenFetcher,
    private val debugLogger: DebugLogger,
    private val dateUtils: DateUtils,
    private val loginStateRepository: LoginStateRepository,
    subscriptionManagers: Set<@JvmSuppressWildcards SubscriptionManager>,
) : ViewModel() {
    private val TAG = "DebugMenu"

    @AssistedFactory
    interface Factory {
        fun create(navigator: UINavigator<DebugMenuDestination>): DebugMenuViewModel
    }

    private val _uiState = MutableStateFlow(
        UIState(
            currentEnvironment = textSecurePreferences.getEnvironment().label,
            environments = Environment.entries.map { it.label },
            snackMessage = null,
            showEnvironmentWarningDialog = false,
            showLoadingDialog = false,
            showDeprecatedStateWarningDialog = false,
            hideMessageRequests = textSecurePreferences.hasHiddenMessageRequests(),
            hideNoteToSelf = configFactory.withUserConfigs { it.userProfile.getNtsPriority() == PRIORITY_HIDDEN },
            forceDeprecationState = deprecationManager.deprecationStateOverride.value,
            forceDeterministicEncryption = textSecurePreferences.forcesDeterministicAttachmentEncryption,
            availableDeprecationState = listOf(null) + LegacyGroupDeprecationManager.DeprecationState.entries.toList(),
            deprecatedTime = deprecationManager.deprecatedTime.value,
            deprecatingStartTime = deprecationManager.deprecatingStartTime.value,
            forceCurrentUserAsPro = textSecurePreferences.forceCurrentUserAsPro(),
            forceOtherUsersAsPro = textSecurePreferences.forceOtherUsersAsPro(),
            forceIncomingMessagesAsPro = textSecurePreferences.forceIncomingMessagesAsPro(),
            forcePostPro = textSecurePreferences.forcePostPro(),
            forceShortTTl = textSecurePreferences.forcedShortTTL(),
            debugAvatarReupload = textSecurePreferences.debugAvatarReupload,
            messageProFeature = textSecurePreferences.getDebugMessageFeatures(),
            dbInspectorState = DatabaseInspectorState.NOT_AVAILABLE,
            debugSubscriptionStatuses = setOf(
                DebugSubscriptionStatus.AUTO_GOOGLE,
                DebugSubscriptionStatus.EXPIRING_GOOGLE,
                DebugSubscriptionStatus.EXPIRING_GOOGLE_LATER,
                DebugSubscriptionStatus.AUTO_APPLE,
                DebugSubscriptionStatus.EXPIRING_APPLE,
                DebugSubscriptionStatus.EXPIRED,
                DebugSubscriptionStatus.EXPIRED_EARLIER,
                DebugSubscriptionStatus.EXPIRED_APPLE,
            ),
            selectedDebugSubscriptionStatus = textSecurePreferences.getDebugSubscriptionType() ?: DebugSubscriptionStatus.AUTO_GOOGLE,
            debugProPlanStatus = setOf(
                DebugProPlanStatus.NORMAL,
                DebugProPlanStatus.LOADING,
                DebugProPlanStatus.ERROR,
            ),
            selectedDebugProPlanStatus = textSecurePreferences.getDebugProPlanStatus() ?: DebugProPlanStatus.NORMAL,
            debugProPlans = subscriptionManagers.asSequence()
                .flatMap { it.availablePlans.asSequence().map { plan -> DebugProPlan(it, plan) } }
                .toList(),
            forceNoBilling = textSecurePreferences.getDebugForceNoBilling(),
            withinQuickRefund = textSecurePreferences.getDebugIsWithinQuickRefund(),
            availableAltFileServers = TEST_FILE_SERVERS,
            alternativeFileServer = textSecurePreferences.alternativeFileServer,
            showToastForGroups = getDebugGroupToastPref()
        )
    )
    val uiState: StateFlow<UIState>
        get() = _uiState

    val debugLogs: Flow<List<DebugLogData>> get() = debugLogger.logSnapshots

    init {
        if (databaseInspector.available) {
            viewModelScope.launch {
                databaseInspector.enabled.collectLatest { started ->
                    _uiState.update { currentState ->
                        currentState.copy(
                            dbInspectorState = if (started) DatabaseInspectorState.STARTED else DatabaseInspectorState.STOPPED
                        )
                    }
                }
            }
        }
    }

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
                val accountId = loginStateRepository.requireLocalNumber()
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

            is Commands.CopyProMasterKey -> {
                val proKey = loginStateRepository.loggedInState.value?.seeded?.proMasterPrivateKey?.toHexString()
                val clip = ClipData.newPlainText("Pro Master Key", proKey)
                clipboardManager.setPrimaryClip(ClipData(clip))

                // Show a toast if the version is below Android 13
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(
                        context,
                        "Copied Pro Master Key to clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            is Commands.HideMessageRequest -> {
                textSecurePreferences.setHasHiddenMessageRequests(command.hide)
                _uiState.value = _uiState.value.copy(hideMessageRequests = command.hide)
            }

            is Commands.HideNoteToSelf -> {
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
                            AccountId(IdPrefix.STANDARD, ED25519.generate(null).secretKey.data)
                        }

                        configFactory.withMutableUserConfigs { configs ->
                            for ((index, key) in keys.withIndex()) {
                                configs.contacts.upsertContact(
                                    Address.Standard(key),
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

            is Commands.ForceOtherUsersAsPro -> {
                textSecurePreferences.setForceOtherUsersAsPro(command.set)
                _uiState.update {
                    it.copy(forceOtherUsersAsPro = command.set)
                }
            }

            is Commands.ForceIncomingMessagesAsPro -> {
                textSecurePreferences.setForceIncomingMessagesAsPro(command.set)
                _uiState.update {
                    it.copy(forceIncomingMessagesAsPro = command.set)
                }
            }

            is Commands.ForceNoBilling -> {
                textSecurePreferences.setDebugForceNoBilling(command.set)
                _uiState.update {
                    it.copy(forceNoBilling = command.set)
                }
            }

            is Commands.WithinQuickRefund -> {
                textSecurePreferences.setDebugIsWithinQuickRefund(command.set)
                _uiState.update {
                    it.copy(withinQuickRefund = command.set)
                }
            }

            is Commands.ForcePostPro -> {
                textSecurePreferences.setForcePostPro(command.set)
                _uiState.update {
                    it.copy(forcePostPro = command.set)
                }
            }

            is Commands.ForceShortTTl -> {
                textSecurePreferences.setForcedShortTTL(command.set)
                _uiState.update {
                    it.copy(forceShortTTl = command.set)
                }
            }

            is Commands.SetMessageProFeature -> {
                val features = _uiState.value.messageProFeature.toMutableSet()
                if(command.set) features.add(command.feature) else features.remove(command.feature)
                textSecurePreferences.setDebugMessageFeatures(features)
                _uiState.update {
                    it.copy(messageProFeature = features)
                }
            }

            Commands.ToggleDatabaseInspector -> {
                if (databaseInspector.available) {
                    if (databaseInspector.enabled.value) {
                        databaseInspector.stop()
                    } else {
                        databaseInspector.start()
                    }
                }
            }

            is Commands.SetDebugSubscriptionStatus -> {
                textSecurePreferences.setDebugSubscriptionType(command.status)
                _uiState.update {
                    it.copy(selectedDebugSubscriptionStatus = command.status)
                }
            }

            is Commands.SetDebugProPlanStatus -> {
                textSecurePreferences.setDebugProPlanStatus(command.status)
                _uiState.update {
                    it.copy(selectedDebugProPlanStatus = command.status)
                }
            }

            is Commands.PurchaseDebugPlan -> {
                viewModelScope.launch {
                    command.plan.apply { manager.purchasePlan(plan) }
                }
            }

            is Commands.ToggleDeterministicEncryption -> {
                val newValue = !_uiState.value.forceDeterministicEncryption
                _uiState.update { it.copy(forceDeterministicEncryption = newValue) }
                textSecurePreferences.forcesDeterministicAttachmentEncryption = newValue
            }

            is Commands.ToggleDebugAvatarReupload -> {
                val newValue = !_uiState.value.debugAvatarReupload
                _uiState.update { it.copy(debugAvatarReupload = newValue) }
                textSecurePreferences.debugAvatarReupload = newValue
            }

            is Commands.ResetPushToken -> {
                viewModelScope.launch {
                    tokenFetcher.resetToken()
                }
            }

            is Commands.SelectAltFileServer -> {
                _uiState.update { it.copy(alternativeFileServer = command.fileServer) }
                textSecurePreferences.alternativeFileServer = command.fileServer
            }

            is Commands.NavigateTo -> {
                viewModelScope.launch {
                    navigator.navigate(command.destination)
                }
            }

            is Commands.ToggleDebugLogGroup -> {
                debugLogger.showGroupToast(command.group, command.showToast)
                _uiState.update {
                    it.copy(showToastForGroups = getDebugGroupToastPref())
                }
            }

            is Commands.ClearAllDebugLogs -> {
                debugLogger.clearAll()
            }

            is Commands.CopyAllLogs -> {
                val logs = debugLogger.currentSnapshot().joinToString("\n\n") {
                    "${dateUtils.getLocaleFormattedTime(it.date.toEpochMilli())}: ${it.message}"
                }

                val clip = ClipData.newPlainText("Debug Logs", logs)
                clipboardManager.setPrimaryClip(ClipData(clip))

                // Show a toast if the version is below Android 13
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(
                        context,
                        "Copied Debug Logs to clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            is Commands.CopyLog -> {
                val log = "${dateUtils.getLocaleFormattedTime(command.log.date.toEpochMilli())}: ${command.log.message}"

                val clip = ClipData.newPlainText("Debug Log", log)
                clipboardManager.setPrimaryClip(ClipData(clip))

                // Show a toast if the version is below Android 13
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(
                        context,
                        "Copied Debug Log to clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun getDebugGroupToastPref(): Map<String, Boolean> {
        return DebugLogGroup.entries.associate { group ->
                group.label to debugLogger.getGroupToastPreference(group)
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
            val conversations: List<ThreadRecord> = conversationRepository.observeConversationList()
                .first()

            conversations.filter { !it.recipient.isLocalNumber }.forEach {
                recipientDatabase.save(it.recipient.address) {
                    it.copy()
                }
            }

            // set all attachments back to pending
            attachmentDatabase.allAttachments.forEach {
                attachmentDatabase.setTransferState(it.attachmentId, AttachmentState.PENDING.value)
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
        val forceDeterministicEncryption: Boolean,
        val forceCurrentUserAsPro: Boolean,
        val forceOtherUsersAsPro: Boolean,
        val forceIncomingMessagesAsPro: Boolean,
        val messageProFeature: Set<ProStatusManager.MessageProFeature>,
        val forcePostPro: Boolean,
        val forceShortTTl: Boolean,
        val forceDeprecationState: LegacyGroupDeprecationManager.DeprecationState?,
        val debugAvatarReupload: Boolean,
        val availableDeprecationState: List<LegacyGroupDeprecationManager.DeprecationState?>,
        val deprecatedTime: ZonedDateTime,
        val deprecatingStartTime: ZonedDateTime,
        val dbInspectorState: DatabaseInspectorState,
        val debugSubscriptionStatuses: Set<DebugSubscriptionStatus>,
        val selectedDebugSubscriptionStatus: DebugSubscriptionStatus,
        val debugProPlanStatus: Set<DebugProPlanStatus>,
        val selectedDebugProPlanStatus: DebugProPlanStatus,
        val debugProPlans: List<DebugProPlan>,
        val forceNoBilling: Boolean,
        val withinQuickRefund: Boolean,
        val alternativeFileServer: FileServer? = null,
        val availableAltFileServers: List<FileServer> = emptyList(),
        val showToastForGroups: Map<String, Boolean> = emptyMap(),
    )

    enum class DatabaseInspectorState {
        NOT_AVAILABLE,
        STARTED,
        STOPPED,
    }

    enum class DebugSubscriptionStatus(val label: String) {
        AUTO_GOOGLE("Auto Renewing (Google, 3 months)"),
        EXPIRING_GOOGLE("Expiring/Cancelled (Expires in 14 days, Google, 12 months)"),
        EXPIRING_GOOGLE_LATER("Expiring/Cancelled (Expires in 40 days, Google, 12 months)"),
        AUTO_APPLE("Auto Renewing (Apple, 1 months)"),
        EXPIRING_APPLE("Expiring/Cancelled (Expires in 14 days, Apple, 1 months)"),
        EXPIRED("Expired (Expired 2 days ago, Google)"),
        EXPIRED_EARLIER("Expired (Expired 60 days ago, Google)"),
        EXPIRED_APPLE("Expired (Expired 2 days ago, Apple)"),
    }

    enum class DebugProPlanStatus(val label: String){
        NORMAL("Normal State"),
        LOADING("Always Loading"),
        ERROR("Always Erroring out"),
    }

    sealed class Commands {
        object ChangeEnvironment : Commands()
        data class ShowEnvironmentWarningDialog(val environment: String) : Commands()
        object HideEnvironmentWarningDialog : Commands()
        object ScheduleTokenNotification : Commands()
        object Copy07PrefixedBlindedPublicKey : Commands()
        object CopyAccountId : Commands()
        object CopyProMasterKey : Commands()
        data class HideMessageRequest(val hide: Boolean) : Commands()
        data class HideNoteToSelf(val hide: Boolean) : Commands()
        data class ForceCurrentUserAsPro(val set: Boolean) : Commands()
        data class ForceOtherUsersAsPro(val set: Boolean) : Commands()
        data class ForceIncomingMessagesAsPro(val set: Boolean) : Commands()
        data class ForceNoBilling(val set: Boolean) : Commands()
        data class WithinQuickRefund(val set: Boolean) : Commands()
        data class ForcePostPro(val set: Boolean) : Commands()
        data class ForceShortTTl(val set: Boolean) : Commands()
        data class SetMessageProFeature(val feature: ProStatusManager.MessageProFeature, val set: Boolean) : Commands()
        data class ShowDeprecationChangeDialog(val state: LegacyGroupDeprecationManager.DeprecationState?) : Commands()
        object HideDeprecationChangeDialog : Commands()
        object OverrideDeprecationState : Commands()
        data class OverrideDeprecatedTime(val time: ZonedDateTime) : Commands()
        data class OverrideDeprecatingStartTime(val time: ZonedDateTime) : Commands()
        object ClearTrustedDownloads: Commands()
        data class GenerateContacts(val prefix: String, val count: Int): Commands()
        data object ToggleDatabaseInspector : Commands()
        data class SetDebugSubscriptionStatus(val status: DebugSubscriptionStatus) : Commands()
        data class SetDebugProPlanStatus(val status: DebugProPlanStatus) : Commands()
        data class PurchaseDebugPlan(val plan: DebugProPlan) : Commands()
        data object ToggleDeterministicEncryption : Commands()
        data object ToggleDebugAvatarReupload : Commands()
        data object ResetPushToken : Commands()
        data class SelectAltFileServer(val fileServer: FileServer?) : Commands()
        data class NavigateTo(val destination: DebugMenuDestination) : Commands()
        data class ToggleDebugLogGroup(val group: DebugLogGroup, val showToast: Boolean) : Commands()
        data object ClearAllDebugLogs : Commands()
        data object CopyAllLogs : Commands()
        data class CopyLog(val log: DebugLogData) : Commands()
    }

    companion object {
        private val TEST_FILE_SERVERS: List<FileServer> = listOf(
            FileServer(
                url = "http://potatofiles.getsession.org",
                ed25519PublicKeyHex = "ff86dcd4b26d1bfec944c59859494248626d6428efc12168749d65a1b92f5e28",
            ),
            FileServer(
                url = "http://superduperfiles.oxen.io",
                ed25519PublicKeyHex = "929e33ded05e653fec04b49645117f51851f102a947e04806791be416ed76602",
            )
        )
    }
}