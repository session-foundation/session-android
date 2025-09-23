package org.thoughtcrime.securesms.home

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityHomeBinding
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.updateContact
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.settings.notification.NotificationSettingsActivity
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter
import org.thoughtcrime.securesms.home.search.GlobalSearchInputLayout
import org.thoughtcrime.securesms.home.search.GlobalSearchResult
import org.thoughtcrime.securesms.home.search.GlobalSearchViewModel
import org.thoughtcrime.securesms.home.search.SearchContactActionBottomSheet
import org.thoughtcrime.securesms.home.startconversation.StartConversationDestination
import org.thoughtcrime.securesms.messagerequests.MessageRequestsActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.SettingsActivity
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.recoverypassword.RecoveryPasswordActivity
import org.thoughtcrime.securesms.reviews.StoreReviewManager
import org.thoughtcrime.securesms.reviews.ui.InAppReview
import org.thoughtcrime.securesms.reviews.ui.InAppReviewViewModel
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.tokenpage.TokenPageNotificationManager
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import org.thoughtcrime.securesms.util.disableClipping
import org.thoughtcrime.securesms.util.fadeIn
import org.thoughtcrime.securesms.util.fadeOut
import org.thoughtcrime.securesms.util.push
import org.thoughtcrime.securesms.util.show
import org.thoughtcrime.securesms.util.start
import org.thoughtcrime.securesms.webrtc.WebRtcCallActivity
import javax.inject.Inject

// Intent extra keys so we know where we came from
private const val NEW_ACCOUNT = "HomeActivity_NEW_ACCOUNT"
private const val FROM_ONBOARDING = "HomeActivity_FROM_ONBOARDING"

@AndroidEntryPoint
class HomeActivity : ScreenLockActionBarActivity(),
    ConversationClickListener,
    GlobalSearchInputLayout.GlobalSearchInputLayoutListener,
    SearchContactActionBottomSheet.Callbacks{

    private val TAG = "HomeActivity"

    private lateinit var binding: ActivityHomeBinding
    private lateinit var glide: RequestManager

    @Inject lateinit var threadDb: ThreadDatabase
    @Inject lateinit var mmsSmsDatabase: MmsSmsDatabase
    @Inject lateinit var storage: Storage
    @Inject lateinit var groupDatabase: GroupDatabase
    @Inject lateinit var textSecurePreferences: TextSecurePreferences
    @Inject lateinit var configFactory: ConfigFactory
    @Inject lateinit var tokenPageNotificationManager: TokenPageNotificationManager
    @Inject lateinit var groupManagerV2: GroupManagerV2
    @Inject lateinit var deprecationManager: LegacyGroupDeprecationManager
    @Inject lateinit var clock: SnodeClock
    @Inject lateinit var messageNotifier: MessageNotifier
    @Inject lateinit var dateUtils: DateUtils
    @Inject lateinit var openGroupManager: OpenGroupManager
    @Inject lateinit var storeReviewManager: StoreReviewManager
    @Inject lateinit var proStatusManager: ProStatusManager
    @Inject lateinit var recipientRepository: RecipientRepository
    @Inject lateinit var avatarUtils: AvatarUtils
    @Inject lateinit var startConversationNavigator: UINavigator<StartConversationDestination>

    private val globalSearchViewModel by viewModels<GlobalSearchViewModel>()
    private val homeViewModel by viewModels<HomeViewModel>()
    private val inAppReviewViewModel by viewModels<InAppReviewViewModel>()

    private val publicKey: String by lazy { textSecurePreferences.getLocalNumber()!! }

    private val homeAdapter: HomeAdapter by lazy {
        HomeAdapter(context = this, configFactory = configFactory, listener = this, ::showMessageRequests, ::hideMessageRequests)
    }

    private val globalSearchAdapter by lazy {
        GlobalSearchAdapter(
            dateUtils = dateUtils,
            onContactClicked = { model ->
                val intent = when (model) {
                    is GlobalSearchAdapter.Model.Message -> ConversationActivityV2
                        .createIntent(
                            this,
                            address = model.messageResult.conversationRecipient.address as Address.Conversable,
                            scrollToMessage = model.messageResult.sentTimestampMs to model.messageResult.messageRecipient.address
                        )

                    is GlobalSearchAdapter.Model.SavedMessages -> ConversationActivityV2
                        .createIntent(
                            this,
                            address = Address.fromSerialized(model.currentUserPublicKey) as Address.Conversable
                        )

                    is GlobalSearchAdapter.Model.Contact -> ConversationActivityV2
                        .createIntent(
                            this,
                            address = Address.fromSerialized(model.contact.hexString) as Address.Conversable
                        )

                    is GlobalSearchAdapter.Model.GroupConversation -> ConversationActivityV2
                        .createIntent(
                            this,
                            address = Address.fromSerialized(model.groupId) as Address.Conversable
                        )

                    else -> {
                        Log.d("Loki", "callback with model: $model")
                        return@GlobalSearchAdapter
                    }
                }

                push(intent)
            },
            onContactLongPressed = { model ->
                onSearchContactLongPress(model.contact.hexString, model.name)
            }
        )
    }

    private fun onSearchContactLongPress(accountId: String, contactName: String) {
        val bottomSheet = SearchContactActionBottomSheet.newInstance(accountId, contactName)
        bottomSheet.show(supportFragmentManager, bottomSheet.tag)
    }

    private val isFromOnboarding: Boolean get() = intent.getBooleanExtra(FROM_ONBOARDING, false)
    private val isNewAccount: Boolean get() = intent.getBooleanExtra(NEW_ACCOUNT, false)

    override val applyDefaultWindowInsets: Boolean
        get() = false

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)

        // Set content view
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Set custom toolbar
        setSupportActionBar(binding.toolbar)
        // Set up Glide
        glide = Glide.with(this)
        // Set up toolbar buttons
        binding.profileButton.setThemedContent {
            val recipient by recipientRepository.observeSelf()
                .collectAsState(null)

            Avatar(
                size = LocalDimensions.current.iconMediumAvatar,
                data = avatarUtils.getUIDataFromRecipient(recipient),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = ::openSettings
                )
            )
        }

        binding.searchViewContainer.setOnClickListener {
            homeViewModel.onSearchClicked()
        }
        binding.sessionToolbar.disableClipping()
        binding.sessionHeaderProBadge.isVisible = homeViewModel.shouldShowCurrentUserProBadge()
        // Set up seed reminder view
        lifecycleScope.launchWhenStarted {
            binding.seedReminderView.setThemedContent {
                if (!textSecurePreferences.getHasViewedSeed()) SeedReminder { start<RecoveryPasswordActivity>() }
            }
        }

        // Set up recycler view
        binding.globalSearchInputLayout.listener = this
        homeAdapter.setHasStableIds(true)
        homeAdapter.glide = glide
        binding.conversationsRecyclerView.adapter = homeAdapter
        binding.globalSearchRecycler.adapter = globalSearchAdapter

        binding.configOutdatedView.setOnClickListener {
            textSecurePreferences.setHasLegacyConfig(false)
            updateLegacyConfigView()
        }

        // in case a phone call is in progress, this banner is visible and should bring the user back to the call
        binding.callInProgress.setOnClickListener {
            startActivity(WebRtcCallActivity.getCallActivityIntent(this))
        }

        // Set up empty state view
        binding.emptyStateContainer.setThemedContent {
            EmptyView(isNewAccount)
        }

        // set the compose dialog content
        binding.dialogs.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setThemedContent {
                val dialogsState by homeViewModel.dialogsState.collectAsState()
                HomeDialogs(
                    dialogsState = dialogsState,
                    startConversationNavigator = startConversationNavigator,
                    sendCommand = homeViewModel::onCommand
                )
            }
        }

        // Set up new conversation button
        binding.newConversationButton.setOnClickListener { showStartConversation() }

        // subscribe to outdated config updates, this should be removed after long enough time for device migration
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TextSecurePreferences.events.filter { it == TextSecurePreferences.HAS_RECEIVED_LEGACY_CONFIG }.collect {
                    updateLegacyConfigView()
                }
            }
        }

        // Subscribe to threads and update the UI
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.data
                    .filterNotNull() // We don't actually want the null value here as it indicates a loading state (maybe we need a loading state?)
                    .collectLatest { data ->
                        val manager = binding.conversationsRecyclerView.layoutManager as LinearLayoutManager
                        val firstPos = manager.findFirstCompletelyVisibleItemPosition()
                        val offsetTop = if(firstPos >= 0) {
                            manager.findViewByPosition(firstPos)?.let { view ->
                                manager.getDecoratedTop(view) - manager.getTopDecorationHeight(view)
                            } ?: 0
                        } else 0
                        homeAdapter.data = data
                        if(firstPos >= 0) { manager.scrollToPositionWithOffset(firstPos, offsetTop) }
                        binding.emptyStateContainer.isVisible = homeAdapter.itemCount == 0
                    }
            }
        }

        lifecycleScope.launchWhenStarted {
            launch(Dispatchers.Default) {
                // update things based on TextSecurePrefs (profile info etc)
                // Set up remaining components if needed
                if (textSecurePreferences.getLocalNumber() != null) {
                    JobQueue.shared.resumePendingJobs()
                }
            }

            // sync view -> viewModel
            launch {
                binding.globalSearchInputLayout.query()
                    .collect(globalSearchViewModel::setQuery)
            }

            // Get group results and display them
            launch {
                globalSearchViewModel.result.map { result ->
                    result.query to when {
                        result.query.isEmpty() -> buildList {
                            add(GlobalSearchAdapter.Model.Header(R.string.contactContacts))
                            add(GlobalSearchAdapter.Model.SavedMessages(publicKey))
                            addAll(result.groupedContacts)
                        }
                        else -> buildList {
                            val conversations = result.contactAndGroupList.toMutableList()
                            if(result.showNoteToSelf){
                                conversations.add(GlobalSearchAdapter.Model.SavedMessages(publicKey))
                            }

                            conversations.takeUnless { it.isEmpty() }?.let {
                                add(GlobalSearchAdapter.Model.Header(R.string.sessionConversations))
                                addAll(it)
                            }
                            result.messageResults.takeUnless { it.isEmpty() }?.let {
                                add(GlobalSearchAdapter.Model.Header(R.string.messages))
                                addAll(it)
                            }
                        }
                    }
                }.collectLatest(globalSearchAdapter::setNewData)
            }
        }
        if (isFromOnboarding) {
            if (Build.VERSION.SDK_INT >= 33 &&
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled().not()) {
                Permissions.with(this)
                    .request(Manifest.permission.POST_NOTIFICATIONS)
                    .execute()
            }

            configFactory.withMutableUserConfigs {
                if (!it.userProfile.isBlockCommunityMessageRequestsSet()) {
                    it.userProfile.setCommunityMessageRequests(false)
                }
            }
        }

        // Schedule a notification about the new Token Page for 1 hour after running the updated app for the first time.
        // Note: We do NOT schedule a debug notification on startup - but one may be triggered from the Debug Menu.
        if (BuildConfig.BUILD_TYPE == "release") {
            tokenPageNotificationManager.scheduleTokenPageNotification(constructDebugNotification = false)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.callBanner.collect { callBanner ->
                    when (callBanner) {
                        null -> binding.callInProgress.fadeOut()
                        else -> {
                            binding.callInProgress.text = callBanner
                            binding.callInProgress.fadeIn()
                        }
                    }
                }
            }
        }

        // Set up search layout
        lifecycleScope.launch {
            homeViewModel.isSearchOpen.collect { open ->
                setSearchShown(open)
            }
        }

        binding.root.applySafeInsetsPaddings(
            applyBottom = false,
            alsoApply = { insets ->
                binding.globalSearchRecycler.updatePadding(bottom = insets.bottom)
                binding.newConversationButton.updateLayoutParams<MarginLayoutParams> {
                    bottomMargin = insets.bottom + resources.getDimensionPixelSize(R.dimen.new_conversation_button_bottom_offset)
                }
            }
        )

        // Set up in-app review
        binding.inAppReviewView.setThemedContent {
            InAppReview(
                uiStateFlow = inAppReviewViewModel.uiState,
                storeReviewManager = storeReviewManager,
                sendCommands = inAppReviewViewModel::sendUiCommand,
            )
        }
    }

    override fun onCancelClicked() {
        homeViewModel.onCancelSearchClicked()
    }

    override fun onBlockContact(accountId: String) {
        homeViewModel.blockContact(accountId)
    }

    override fun onDeleteContact(accountId: String) {
        val address = accountId.toAddress()
        if (address is Address.WithAccountId) {
            homeViewModel.deleteContact(address)
        }
    }

    private val GlobalSearchResult.groupedContacts: List<GlobalSearchAdapter.Model> get() {
        class NamedValue<T>(val name: String?, val value: T)

        // Unknown is temporarily to be grouped together with numbers title - see: SES-2287
        val numbersTitle = "#"
        val unknownTitle = numbersTitle

        return contacts
            // Remove ourself, we're shown above.
            .filter { it.address.address != publicKey }
            // Get the name that we will display and sort by, and uppercase it to
            // help with sorting and we need the char uppercased later.
            .map { NamedValue(it.displayName().uppercase(), it) }
            // Digits are all grouped under a #, the rest are grouped by their first character.uppercased()
            // If there is no name, they go under Unknown
            .groupBy { it.name?.run { first().takeUnless(Char::isDigit)?.toString() ?: numbersTitle } ?: unknownTitle }
            // place the # at the end, after all the names starting with alphabetic chars
            .toSortedMap(compareBy {
                when (it) {
                    unknownTitle -> Char.MAX_VALUE
                    numbersTitle -> Char.MAX_VALUE - 1
                    else -> it.first()
                }
            })
            // Flatten the map of char to lists into an actual List that can be displayed.
            .flatMap { (key, contacts) ->
                listOf(
                    GlobalSearchAdapter.Model.SubHeader(key)
                ) + contacts.sortedBy { it.name ?: it.value.address.address }
                    .map {
                        GlobalSearchAdapter.Model.Contact(
                            contact = it.value,
                            isSelf = it.value.address.address == publicKey,
                            showProBadge = proStatusManager.shouldShowProBadge(it.value.address)
                        )
                    }
            }
    }

    private val GlobalSearchResult.contactAndGroupList: List<GlobalSearchAdapter.Model> get() =
        contacts.map { GlobalSearchAdapter.Model.Contact(
            it,
            it.address.address == publicKey,
            showProBadge = proStatusManager.shouldShowProBadge(it.address)) } +
            threads.map {
                GlobalSearchAdapter.Model.GroupConversation(it, showProBadge = proStatusManager.shouldShowProBadge(it.encodedId.toAddress()))
            }

    private val GlobalSearchResult.messageResults: List<GlobalSearchAdapter.Model> get() {
        val unreadThreadMap = messages
            .map { it.threadId }.toSet()
            .associateWith { mmsSmsDatabase.getUnreadCount(it) }

        return messages.map {
            GlobalSearchAdapter.Model.Message(
                messageResult = it,
                unread = unreadThreadMap[it.threadId] ?: 0,
                isSelf = it.conversationRecipient.isLocalNumber,
                showProBadge = proStatusManager.shouldShowProBadge(it.conversationRecipient.address)
            )
        }
    }

    private fun setSearchShown(isSearchShown: Boolean) {
        // Request focus immediately so the user can start typing
        if (isSearchShown) {
            binding.globalSearchInputLayout.requestFocus()
        }

        binding.searchToolbar.isVisible = isSearchShown
        binding.sessionToolbar.isVisible = !isSearchShown
        binding.seedReminderView.isVisible = !TextSecurePreferences.getHasViewedSeed(this) && !isSearchShown
        binding.globalSearchRecycler.isVisible = isSearchShown


        // Show a fade in animation for the conversation list upon re-appearing
        val shouldShowHomeAnimation = !isSearchShown && !binding.conversationListContainer.isVisible

        binding.conversationListContainer.isVisible = !isSearchShown
        if (shouldShowHomeAnimation) {
            binding.conversationListContainer.animate().cancel()
            binding.conversationListContainer.alpha = 0f
            binding.conversationListContainer.animate().alpha(1f).start()
        }

    }

    private fun updateLegacyConfigView() {
        binding.configOutdatedView.isVisible = textSecurePreferences.getHasLegacyConfig()
    }

    override fun onResume() {
        super.onResume()
        messageNotifier.setHomeScreenVisible(true)
        if (textSecurePreferences.getLocalNumber() == null) { return; } // This can be the case after a secondary device is auto-cleared
        IdentityKeyUtil.checkUpdate(this)
        if (textSecurePreferences.getHasViewedSeed()) {
            binding.seedReminderView.isVisible = false
        }

        updateLegacyConfigView()
    }

    override fun onPause() {
        super.onPause()
        ApplicationContext.getInstance(this).messageNotifier.setHomeScreenVisible(false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
    // endregion

    // region Interaction
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (homeViewModel.isSearchOpen.value && binding.globalSearchInputLayout.handleBackPressed()) {
            return
        }

        if (!homeViewModel.onBackPressed()) {
            super.onBackPressed()
        }
    }

    override fun onConversationClick(thread: ThreadRecord) {
        push(ConversationActivityV2.createIntent(this, address = thread.recipient.address as Address.Conversable))
    }

    override fun onLongConversationClick(thread: ThreadRecord) {
        val bottomSheet = ConversationOptionsBottomSheet(this)
        bottomSheet.publicKey = publicKey
        bottomSheet.thread = thread
        val threadRecipient = thread.recipient
        bottomSheet.group = groupDatabase.getGroup(threadRecipient.address.toString()).orNull()
        bottomSheet.onViewDetailsTapped = {
            bottomSheet.dismiss()
            homeViewModel.showUserProfileModal(thread)
        }
        bottomSheet.onCopyConversationId = onCopyConversationId@{
            bottomSheet.dismiss()
            if (threadRecipient.address is Address.WithAccountId && !threadRecipient.isSelf) {
                val clip = ClipData.newPlainText("Account ID", threadRecipient.address.accountId.hexString)
                val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(clip)
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            else if (threadRecipient.data is RecipientData.Community) {
                val clip = ClipData.newPlainText("Community URL", threadRecipient.data.joinURL)
                val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(clip)
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        bottomSheet.onBlockTapped = {
            bottomSheet.dismiss()
            if (!threadRecipient.blocked) {
                blockConversation(thread)
            }
        }
        bottomSheet.onUnblockTapped = {
            bottomSheet.dismiss()
            if (threadRecipient.blocked) {
                unblockConversation(thread)
            }
        }
        bottomSheet.onDeleteTapped = {
            bottomSheet.dismiss()
            deleteConversation(thread)
        }
        bottomSheet.onNotificationTapped = {
            bottomSheet.dismiss()
            // go to the notification settings
            val intent = Intent(this, NotificationSettingsActivity::class.java).apply {
                putExtra(NotificationSettingsActivity.ARG_ADDRESS, threadRecipient.address)
            }
            startActivity(intent)
        }
        bottomSheet.onPinTapped = {
            bottomSheet.dismiss()
            setConversationPinned(threadRecipient.address, true)
        }
        bottomSheet.onUnpinTapped = {
            bottomSheet.dismiss()
            setConversationPinned(threadRecipient.address, false)
        }
        bottomSheet.onMarkAllAsReadTapped = {
            bottomSheet.dismiss()
            markAllAsRead(thread)
        }
        bottomSheet.onMarkAsUnreadTapped = {
            bottomSheet.dismiss()
            markAsUnread(thread)
        }
        bottomSheet.onDeleteContactTapped = {
            bottomSheet.dismiss()
            confirmDeleteContact(thread)
        }
        bottomSheet.show(supportFragmentManager, bottomSheet.tag)
    }

    private fun blockConversation(thread: ThreadRecord) {
        showSessionDialog {
            title(R.string.block)
            text(Phrase.from(context, R.string.blockDescription)
                .put(NAME_KEY, thread.recipient.displayName())
                .format())
            dangerButton(R.string.block, R.string.AccessibilityId_blockConfirm) {
                lifecycleScope.launch(Dispatchers.Default) {
                    storage.setBlocked(listOf(thread.recipient.address), true)

                    withContext(Dispatchers.Main) {
                        binding.conversationsRecyclerView.adapter!!.notifyDataSetChanged()
                    }
                }
                // Block confirmation toast added as per SS-64
                val txt = Phrase.from(context, R.string.blockBlockedUser).put(NAME_KEY, thread.recipient.displayName()).format().toString()
                Toast.makeText(context, txt, Toast.LENGTH_LONG).show()
            }
            cancelButton()
        }
    }

    private fun unblockConversation(thread: ThreadRecord) {
        showSessionDialog {
            title(R.string.blockUnblock)
            text(Phrase.from(context, R.string.blockUnblockName).put(NAME_KEY, thread.recipient.displayName()).format())
            dangerButton(R.string.blockUnblock, R.string.AccessibilityId_unblockConfirm) {
                lifecycleScope.launch(Dispatchers.Default) {
                    storage.setBlocked(listOf(thread.recipient.address), false)
                    withContext(Dispatchers.Main) {
                        binding.conversationsRecyclerView.adapter!!.notifyDataSetChanged()
                    }
                }
            }
            cancelButton()
        }
    }

    private fun confirmDeleteContact(thread: ThreadRecord) {
        showSessionDialog {
            title(R.string.contactDelete)
            text(
                Phrase.from(context, R.string.deleteContactDescription)
                    .put(NAME_KEY, thread.recipient?.displayName().orEmpty())
                    .format()
            )
            dangerButton(R.string.delete, R.string.qa_conversation_settings_dialog_delete_contact_confirm) {
                homeViewModel.deleteContact(thread.recipient.address as Address.WithAccountId)
            }
            cancelButton()
        }
    }

    private fun setConversationPinned(address: Address, pinned: Boolean) {
        homeViewModel.setPinned(address, pinned)
    }

    private fun markAllAsRead(thread: ThreadRecord) {
        lifecycleScope.launch(Dispatchers.Default) {
            storage.markConversationAsRead(thread.threadId, clock.currentTimeMills())
        }
    }

    private fun markAsUnread(thread : ThreadRecord){
        lifecycleScope.launch(Dispatchers.Default) {
            storage.markConversationAsUnread(thread.threadId)
        }
    }

    private fun deleteConversation(thread: ThreadRecord) {
        val recipient = thread.recipient

        if (recipient.address is Address.Group) {
            confirmAndLeaveGroup(
                dialogData = groupManagerV2.getLeaveGroupConfirmationDialogData(recipient.address.accountId, recipient.displayName())
            ) {
                homeViewModel.leaveGroup(recipient.address.accountId)
            }

            return
        }

        val title: String
        val message: CharSequence
        var positiveButtonId: Int = R.string.delete
        val negativeButtonId: Int = R.string.cancel

        // default delete action
        val deleteAction: () -> Unit = {
            lifecycleScope.launch(Dispatchers.Main) {
                val context = this@HomeActivity

                // Delete the conversation
                when (recipient.address) {
                    is Address.Community -> {
                        openGroupManager.delete(recipient.address.serverUrl, recipient.address.room)
                    }

                    is Address.Standard -> {
                        configFactory.withMutableUserConfigs { configs ->
                            if (recipient.isSelf) {
                                configs.userProfile.setNtsPriority(PRIORITY_HIDDEN)
                            } else {
                                configs.contacts.updateContact(recipient.address) {
                                    priority = PRIORITY_HIDDEN
                                }
                            }
                        }
                    }

                    is Address.LegacyGroup -> {
                        configFactory.withMutableUserConfigs { configs ->
                            configs.userGroups.eraseLegacyGroup(recipient.address.groupPublicKeyHex)
                        }
                    }

                    is Address.CommunityBlindedId -> {
                        configFactory.withMutableUserConfigs { configs ->
                            configs.contacts.eraseBlinded(
                                communityServerUrl = recipient.address.serverUrl,
                                blindedId = recipient.address.blindedId.blindedId.hexString
                            )
                        }
                    }

                    is Address.Blinded,
                    is Address.Group,
                    is Address.Unknown -> {
                        error("Unexpected address to delete")
                    }
                }


                // Update the badge count
                messageNotifier.updateNotification(context)

                // Notify the user
                val toastMessage = if (recipient.isGroupOrCommunityRecipient) R.string.groupMemberYouLeft else R.string.conversationsDeleted
                Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
            }
        }

        if (recipient.isLegacyGroupRecipient || recipient.isCommunityRecipient) {
            positiveButtonId = R.string.leave

            // If you are an admin of this group you can delete it
            // we do not want admin related messaging once legacy groups are deprecated
            val isGroupAdmin = if(deprecationManager.isDeprecated){
                false
            } else { // prior to the deprecated state, calculate admin rights properly
                recipient.currentUserRole.canModerate
            }

            if (isGroupAdmin) {
                title = getString(R.string.groupLeave)
                message = Phrase.from(this, R.string.groupLeaveDescriptionAdmin)
                    .put(GROUP_NAME_KEY, recipient.displayName())
                    .format()
            } else {
                // Otherwise this is either a community, or it's a group you're not an admin of
                title = if (recipient.isCommunityRecipient) getString(R.string.communityLeave) else getString(R.string.groupLeave)
                message = Phrase.from(this.applicationContext, R.string.groupLeaveDescription)
                    .put(GROUP_NAME_KEY, recipient.displayName())
                    .format()
            }
        } else {
            // Note to self
            if (recipient.isLocalNumber) {
                title = getString(R.string.noteToSelfHide)
                message = getText(R.string.hideNoteToSelfDescription)
                positiveButtonId = R.string.hide
            }
            else { // If this is a 1-on-1 conversation
                title = getString(R.string.conversationsDelete)
                message = Phrase.from(this, R.string.deleteConversationDescription)
                    .put(NAME_KEY, recipient.displayName())
                    .format()
            }
        }

        showSessionDialog {
            title(title)
            text(message)
            dangerButton(positiveButtonId) {
                deleteAction()
            }
            button(negativeButtonId)
        }
    }

    private fun confirmAndLeaveGroup(
        dialogData: GroupManagerV2.ConfirmDialogData?,
        doLeave: suspend () -> Unit,
    ) {
        if (dialogData == null) return

        showSessionDialog {
            title(dialogData.title)
            text(dialogData.message)
            dangerButton(
                dialogData.positiveText,
                contentDescriptionRes = dialogData.positiveQaTag ?: dialogData.positiveText
            ) {
                GlobalScope.launch(Dispatchers.Default) {
                    doLeave()
                }

            }
            button(
                dialogData.negativeText,
                contentDescriptionRes = dialogData.negativeQaTag ?: dialogData.negativeText
            )
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        show(intent, isForResult = true)
    }

    private fun showMessageRequests() {
        val intent = Intent(this, MessageRequestsActivity::class.java)
        push(intent)
    }

    private fun hideMessageRequests() {
        showSessionDialog {
            text(getString(R.string.hide))
            button(R.string.yes) {
                textSecurePreferences.setHasHiddenMessageRequests(true)
                homeViewModel.tryReload()
            }
            button(R.string.no)
        }
    }

    private fun showStartConversation() {
        homeViewModel.onCommand(HomeViewModel.Commands.ShowStartConversationSheet)
    }
}

fun Context.startHomeActivity(isFromOnboarding: Boolean, isNewAccount: Boolean) {
    Intent(this, HomeActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        putExtra(NEW_ACCOUNT, isNewAccount)
        putExtra(FROM_ONBOARDING, isFromOnboarding)
    }.also(::startActivity)
}
