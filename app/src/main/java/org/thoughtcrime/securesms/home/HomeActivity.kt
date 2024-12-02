package org.thoughtcrime.securesms.home

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityHomeBinding
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ProfilePictureModifiedEvent
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.conversation.start.StartConversationFragment
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.menus.ConversationMenuHelper
import org.thoughtcrime.securesms.conversation.v2.utilities.NotificationUtils
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.SessionJobDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter
import org.thoughtcrime.securesms.home.search.GlobalSearchInputLayout
import org.thoughtcrime.securesms.home.search.GlobalSearchResult
import org.thoughtcrime.securesms.home.search.GlobalSearchViewModel
import org.thoughtcrime.securesms.messagerequests.MessageRequestsActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.SettingsActivity
import org.thoughtcrime.securesms.recoverypassword.RecoveryPasswordActivity
import org.thoughtcrime.securesms.showMuteDialog
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.util.disableClipping
import org.thoughtcrime.securesms.util.push
import org.thoughtcrime.securesms.util.show
import org.thoughtcrime.securesms.util.start
import javax.inject.Inject

// Intent extra keys so we know where we came from
private const val NEW_ACCOUNT = "HomeActivity_NEW_ACCOUNT"
private const val FROM_ONBOARDING = "HomeActivity_FROM_ONBOARDING"

@AndroidEntryPoint
class HomeActivity : PassphraseRequiredActionBarActivity(),
    ConversationClickListener,
    GlobalSearchInputLayout.GlobalSearchInputLayoutListener {

    private val TAG = "HomeActivity"

    private lateinit var binding: ActivityHomeBinding
    private lateinit var glide: RequestManager

    @Inject lateinit var threadDb: ThreadDatabase
    @Inject lateinit var mmsSmsDatabase: MmsSmsDatabase
    @Inject lateinit var recipientDatabase: RecipientDatabase
    @Inject lateinit var storage: Storage
    @Inject lateinit var groupDatabase: GroupDatabase
    @Inject lateinit var textSecurePreferences: TextSecurePreferences
    @Inject lateinit var configFactory: ConfigFactory
    @Inject lateinit var groupManagerV2: GroupManagerV2
    @Inject lateinit var lokiThreadDatabase: LokiThreadDatabase
    @Inject lateinit var sessionJobDatabase: SessionJobDatabase
    @Inject lateinit var clock: SnodeClock
    @Inject lateinit var messageNotifier: MessageNotifier

    private val globalSearchViewModel by viewModels<GlobalSearchViewModel>()
    private val homeViewModel by viewModels<HomeViewModel>()

    private val publicKey: String by lazy { textSecurePreferences.getLocalNumber()!! }

    private val homeAdapter: HomeAdapter by lazy {
        HomeAdapter(context = this, configFactory = configFactory, listener = this, ::showMessageRequests, ::hideMessageRequests)
    }

    private val globalSearchAdapter = GlobalSearchAdapter { model ->
        when (model) {
            is GlobalSearchAdapter.Model.Message -> push<ConversationActivityV2> {
                model.messageResult.run {
                    putExtra(ConversationActivityV2.THREAD_ID, threadId)
                    putExtra(ConversationActivityV2.SCROLL_MESSAGE_ID, sentTimestampMs)
                    putExtra(ConversationActivityV2.SCROLL_MESSAGE_AUTHOR, messageRecipient.address)
                }
            }
            is GlobalSearchAdapter.Model.SavedMessages -> push<ConversationActivityV2> {
                putExtra(ConversationActivityV2.ADDRESS, Address.fromSerialized(model.currentUserPublicKey))
            }
            is GlobalSearchAdapter.Model.Contact -> push<ConversationActivityV2> {
                putExtra(
                    ConversationActivityV2.ADDRESS,
                    model.contact.accountID.let(Address::fromSerialized)
                )
            }

            is GlobalSearchAdapter.Model.GroupConversation -> model.groupRecord.encodedId
                .let { Recipient.from(this, Address.fromSerialized(it), false) }
                .let(threadDb::getThreadIdIfExistsFor)
                .takeIf { it >= 0 }
                ?.let {
                    push<ConversationActivityV2> { putExtra(ConversationActivityV2.THREAD_ID, it) }
                }
            else -> Log.d("Loki", "callback with model: $model")
        }
    }

    private val isFromOnboarding: Boolean get() = intent.getBooleanExtra(FROM_ONBOARDING, false)
    private val isNewAccount: Boolean get() = intent.getBooleanExtra(NEW_ACCOUNT, false)

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)

        // Set content view
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Set custom toolbar
        setSupportActionBar(binding.toolbar)
        // Set up Glide
        glide = Glide.with(this)
        // Set up toolbar buttons
        binding.profileButton.setOnClickListener { openSettings() }
        binding.searchViewContainer.setOnClickListener {
            globalSearchViewModel.refresh()
            binding.globalSearchInputLayout.requestFocus()
        }
        binding.sessionToolbar.disableClipping()
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
        binding.recyclerView.adapter = homeAdapter
        binding.globalSearchRecycler.adapter = globalSearchAdapter

        binding.configOutdatedView.setOnClickListener {
            textSecurePreferences.setHasLegacyConfig(false)
            updateLegacyConfigView()
        }

        // Set up empty state view
        binding.emptyStateContainer.setThemedContent {
            EmptyView(isNewAccount)
        }

        // Set up new conversation button
        binding.newConversationButton.setOnClickListener { showStartConversation() }
        // Observe blocked contacts changed events

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
                        val manager = binding.recyclerView.layoutManager as LinearLayoutManager
                        val firstPos = manager.findFirstCompletelyVisibleItemPosition()
                        val offsetTop = if(firstPos >= 0) {
                            manager.findViewByPosition(firstPos)?.let { view ->
                                manager.getDecoratedTop(view) - manager.getTopDecorationHeight(view)
                            } ?: 0
                        } else 0
                        homeAdapter.data = data
                        if(firstPos >= 0) { manager.scrollToPositionWithOffset(firstPos, offsetTop) }
                        updateEmptyState()
                    }
            }
        }

        lifecycleScope.launchWhenStarted {
            launch(Dispatchers.Default) {
                // Double check that the long poller is up
                (applicationContext as ApplicationContext).startPollingIfNeeded()
                // update things based on TextSecurePrefs (profile info etc)
                // Set up remaining components if needed
                if (textSecurePreferences.getLocalNumber() != null) {
                    OpenGroupManager.startPolling()
                    JobQueue.shared.resumePendingJobs()
                }

                withContext(Dispatchers.Main) {
                    updateProfileButton()
                    TextSecurePreferences.events.filter { it == TextSecurePreferences.PROFILE_NAME_PREF }.collect {
                        updateProfileButton()
                    }
                }
            }

            // monitor the global search VM query
            launch {
                binding.globalSearchInputLayout.query
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
                            result.contactAndGroupList.takeUnless { it.isEmpty() }?.let {
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
        EventBus.getDefault().register(this@HomeActivity)
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
    }

    private val GlobalSearchResult.groupedContacts: List<GlobalSearchAdapter.Model> get() {
        class NamedValue<T>(val name: String?, val value: T)

        // Unknown is temporarily to be grouped together with numbers title.
        // https://optf.atlassian.net/browse/SES-2287
        val numbersTitle = "#"
        val unknownTitle = numbersTitle

        return contacts
            // Remove ourself, we're shown above.
            .filter { it.accountID != publicKey }
            // Get the name that we will display and sort by, and uppercase it to
            // help with sorting and we need the char uppercased later.
            .map { (it.nickname?.takeIf(String::isNotEmpty) ?: it.name?.takeIf(String::isNotEmpty))
                .let { name -> NamedValue(name?.uppercase(), it) } }
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
                ) + contacts.sortedBy { it.name ?: it.value.accountID }.map { it.value }.map { GlobalSearchAdapter.Model.Contact(it, it.nickname ?: it.name, it.accountID == publicKey) }
            }
    }

    private val GlobalSearchResult.contactAndGroupList: List<GlobalSearchAdapter.Model> get() =
        contacts.map { GlobalSearchAdapter.Model.Contact(it, it.nickname ?: it.name, it.accountID == publicKey) } +
            threads.map(GlobalSearchAdapter.Model::GroupConversation)

    private val GlobalSearchResult.messageResults: List<GlobalSearchAdapter.Model> get() {
        val unreadThreadMap = messages
            .map { it.threadId }.toSet()
            .associateWith { mmsSmsDatabase.getUnreadCount(it) }

        return messages.map {
            GlobalSearchAdapter.Model.Message(it, unreadThreadMap[it.threadId] ?: 0, it.conversationRecipient.isLocalNumber)
        }
    }

    override fun onInputFocusChanged(hasFocus: Boolean) {
        setSearchShown(hasFocus || binding.globalSearchInputLayout.query.value.isNotEmpty())
    }

    private fun setSearchShown(isShown: Boolean) {
        binding.searchToolbar.isVisible = isShown
        binding.sessionToolbar.isVisible = !isShown
        binding.recyclerView.isVisible = !isShown
        binding.emptyStateContainer.isVisible = (binding.recyclerView.adapter as HomeAdapter).itemCount == 0 && binding.recyclerView.isVisible
        binding.seedReminderView.isVisible = !TextSecurePreferences.getHasViewedSeed(this) && !isShown
        binding.globalSearchRecycler.isInvisible = !isShown
        binding.newConversationButton.isVisible = !isShown
    }

    private fun updateLegacyConfigView() {
        binding.configOutdatedView.isVisible = textSecurePreferences.getHasLegacyConfig()
    }

    override fun onResume() {
        super.onResume()
        messageNotifier.setHomeScreenVisible(true)
        if (textSecurePreferences.getLocalNumber() == null) { return; } // This can be the case after a secondary device is auto-cleared
        IdentityKeyUtil.checkUpdate(this)
        binding.profileButton.recycle() // clear cached image before update tje profilePictureView
        binding.profileButton.update()
        if (textSecurePreferences.getHasViewedSeed()) {
            binding.seedReminderView.isVisible = false
        }

        // refresh search on resume, in case we a conversation was deleted
        if (binding.globalSearchRecycler.isVisible){
            globalSearchViewModel.refresh()
        }

        updateLegacyConfigView()
    }

    override fun onPause() {
        super.onPause()
        ApplicationContext.getInstance(this).messageNotifier.setHomeScreenVisible(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
    // endregion

    // region Updating
    private fun updateEmptyState() {
        val threadCount = (binding.recyclerView.adapter)!!.itemCount
        binding.emptyStateContainer.isVisible = threadCount == 0 && binding.recyclerView.isVisible
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onUpdateProfileEvent(event: ProfilePictureModifiedEvent) {
        if (event.recipient.isLocalNumber) {
            updateProfileButton()
        } else {
            homeViewModel.tryReload()
        }
    }

    private fun updateProfileButton() {
        binding.profileButton.publicKey = publicKey
        binding.profileButton.displayName = textSecurePreferences.getProfileName()
        binding.profileButton.recycle()
        binding.profileButton.update()
    }
    // endregion

    // region Interaction
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.globalSearchRecycler.isVisible) binding.globalSearchInputLayout.clearSearch(true)
        else super.onBackPressed()
    }

    override fun onConversationClick(thread: ThreadRecord) {
        val intent = Intent(this, ConversationActivityV2::class.java)
        intent.putExtra(ConversationActivityV2.THREAD_ID, thread.threadId)
        push(intent)
    }

    override fun onLongConversationClick(thread: ThreadRecord) {
        val bottomSheet = ConversationOptionsBottomSheet(this)
        bottomSheet.publicKey = publicKey
        bottomSheet.thread = thread
        bottomSheet.group = groupDatabase.getGroup(thread.recipient.address.toString()).orNull()
        bottomSheet.onViewDetailsTapped = {
            bottomSheet.dismiss()
            val userDetailsBottomSheet = UserDetailsBottomSheet()
            val bundle = bundleOf(
                    UserDetailsBottomSheet.ARGUMENT_PUBLIC_KEY to thread.recipient.address.toString(),
                    UserDetailsBottomSheet.ARGUMENT_THREAD_ID to thread.threadId
            )
            userDetailsBottomSheet.arguments = bundle
            userDetailsBottomSheet.show(supportFragmentManager, userDetailsBottomSheet.tag)
        }
        bottomSheet.onCopyConversationId = onCopyConversationId@{
            bottomSheet.dismiss()
            if (!thread.recipient.isGroupOrCommunityRecipient && !thread.recipient.isLocalNumber) {
                val clip = ClipData.newPlainText("Account ID", thread.recipient.address.toString())
                val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(clip)
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            else if (thread.recipient.isCommunityRecipient) {
                val threadId = threadDb.getThreadIdIfExistsFor(thread.recipient)
                val openGroup = lokiThreadDatabase.getOpenGroupChat(threadId) ?: return@onCopyConversationId Unit

                val clip = ClipData.newPlainText("Community URL", openGroup.joinURL)
                val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(clip)
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        bottomSheet.onBlockTapped = {
            bottomSheet.dismiss()
            if (!thread.recipient.isBlocked) {
                blockConversation(thread)
            }
        }
        bottomSheet.onUnblockTapped = {
            bottomSheet.dismiss()
            if (thread.recipient.isBlocked) {
                unblockConversation(thread)
            }
        }
        bottomSheet.onDeleteTapped = {
            bottomSheet.dismiss()
            deleteConversation(thread)
        }
        bottomSheet.onSetMuteTapped = { muted ->
            bottomSheet.dismiss()
            setConversationMuted(thread, muted)
        }
        bottomSheet.onNotificationTapped = {
            bottomSheet.dismiss()
            NotificationUtils.showNotifyDialog(this, thread.recipient) { notifyType ->
                setNotifyType(thread, notifyType)
            }
        }
        bottomSheet.onPinTapped = {
            bottomSheet.dismiss()
            setConversationPinned(thread.threadId, true)
        }
        bottomSheet.onUnpinTapped = {
            bottomSheet.dismiss()
            setConversationPinned(thread.threadId, false)
        }
        bottomSheet.onMarkAllAsReadTapped = {
            bottomSheet.dismiss()
            markAllAsRead(thread)
        }
        bottomSheet.show(supportFragmentManager, bottomSheet.tag)
    }

    private fun blockConversation(thread: ThreadRecord) {
        showSessionDialog {
            title(R.string.block)
            text(Phrase.from(context, R.string.blockDescription)
                .put(NAME_KEY, thread.recipient.toShortString())
                .format())
            dangerButton(R.string.block, R.string.AccessibilityId_blockConfirm) {
                lifecycleScope.launch(Dispatchers.Default) {
                    storage.setBlocked(listOf(thread.recipient), true)

                    withContext(Dispatchers.Main) {
                        binding.recyclerView.adapter!!.notifyDataSetChanged()
                    }
                }
                // Block confirmation toast added as per SS-64
                val txt = Phrase.from(context, R.string.blockBlockedUser).put(NAME_KEY, thread.recipient.toShortString()).format().toString()
                Toast.makeText(context, txt, Toast.LENGTH_LONG).show()
            }
            cancelButton()
        }
    }

    private fun unblockConversation(thread: ThreadRecord) {
        showSessionDialog {
            title(R.string.blockUnblock)
            text(Phrase.from(context, R.string.blockUnblockName).put(NAME_KEY, thread.recipient.toShortString()).format())
            dangerButton(R.string.blockUnblock, R.string.AccessibilityId_unblockConfirm) {
                lifecycleScope.launch(Dispatchers.Default) {
                    storage.setBlocked(listOf(thread.recipient), false)
                    withContext(Dispatchers.Main) {
                        binding.recyclerView.adapter!!.notifyDataSetChanged()
                    }
                }
            }
            cancelButton()
        }
    }

    private fun setConversationMuted(thread: ThreadRecord, isMuted: Boolean) {
        if (!isMuted) {
            lifecycleScope.launch(Dispatchers.Default) {
                recipientDatabase.setMuted(thread.recipient, 0)
                withContext(Dispatchers.Main) {
                    binding.recyclerView.adapter!!.notifyDataSetChanged()
                }
            }
        } else {
            showMuteDialog(this) { until ->
                lifecycleScope.launch(Dispatchers.Default) {
                    recipientDatabase.setMuted(thread.recipient, until)
                    withContext(Dispatchers.Main) {
                        binding.recyclerView.adapter!!.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun setNotifyType(thread: ThreadRecord, newNotifyType: Int) {
        lifecycleScope.launch(Dispatchers.Default) {
            recipientDatabase.setNotifyType(thread.recipient, newNotifyType)
            withContext(Dispatchers.Main) {
                binding.recyclerView.adapter!!.notifyDataSetChanged()
            }
        }
    }

    private fun setConversationPinned(threadId: Long, pinned: Boolean) {
        lifecycleScope.launch(Dispatchers.Default) {
            storage.setPinned(threadId, pinned)
            homeViewModel.tryReload()
        }
    }

    private fun markAllAsRead(thread: ThreadRecord) {
        lifecycleScope.launch(Dispatchers.Default) {
            storage.markConversationAsRead(thread.threadId, clock.currentTimeMills())
        }
    }

    private fun deleteConversation(thread: ThreadRecord) {
        val threadID = thread.threadId
        val recipient = thread.recipient

        if (recipient.isGroupV2Recipient) {
            val statusChannel = ConversationMenuHelper.leaveGroup(
                context = this,
                thread = recipient,
                threadID = threadID,
                configFactory = configFactory,
                storage = storage,
                groupManager = groupManagerV2,
            )

            if (statusChannel != null) {
                lifecycleScope.launch {
                    statusChannel.consumeEach { status ->
                        when (status) {
                            ConversationMenuHelper.GroupLeavingStatus.Leaving -> {
                                homeViewModel.onLeavingGroupStarted(threadID)
                            }

                            ConversationMenuHelper.GroupLeavingStatus.Left -> {
                                homeViewModel.onLeavingGroupFinished(threadID, isError = false)
                            }
                            ConversationMenuHelper.GroupLeavingStatus.Error -> {
                                homeViewModel.onLeavingGroupFinished(threadID, isError = true)
                            }
                        }
                    }
                }
            }

            return
        }

        val title: String
        val message: CharSequence
        var positiveButtonId: Int = R.string.delete
        val negativeButtonId: Int = R.string.cancel

        // default delete action
        var deleteAction: ()->Unit = {
            lifecycleScope.launch(Dispatchers.Main) {
                val context = this@HomeActivity
                // Cancel any outstanding jobs
                sessionJobDatabase
                    .cancelPendingMessageSendJobs(threadID)

                // Delete the conversation
                val community = lokiThreadDatabase
                    .getOpenGroupChat(threadID)
                if (community != null) {
                    OpenGroupManager.delete(
                        community.server,
                        community.room,
                        context
                    )
                } else {
                    lifecycleScope.launch(Dispatchers.Default) {
                        threadDb.deleteConversation(threadID)
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
            val group = groupDatabase.getGroup(recipient.address.toString()).orNull()
            positiveButtonId = R.string.leave

            // If you are an admin of this group you can delete it
            if (group != null && group.admins.map { it.toString() }.contains(textSecurePreferences.getLocalNumber())) {
                title = getString(R.string.groupLeave)
                message = Phrase.from(this, R.string.groupLeaveDescriptionAdmin)
                    .put(GROUP_NAME_KEY, group.title)
                    .format()
            } else {
                // Otherwise this is either a community, or it's a group you're not an admin of
                title = if (recipient.isCommunityRecipient) getString(R.string.communityLeave) else getString(R.string.groupLeave)
                message = Phrase.from(this.applicationContext, R.string.groupLeaveDescription)
                    .put(GROUP_NAME_KEY, group.title)
                    .format()
            }
        } else {
            // Note to self
            if (recipient.isLocalNumber) {
                title = getString(R.string.noteToSelfHide)
                message = getString(R.string.noteToSelfHideDescription)
                positiveButtonId = R.string.hide

                // change the action for Note To Self, as they should only be hidden and the messages should remain undeleted
                deleteAction = {
                    homeViewModel.hideNoteToSelf()
                }
            }
            else { // If this is a 1-on-1 conversation
                title = getString(R.string.conversationsDelete)
                message = Phrase.from(this, R.string.conversationsDeleteDescription)
                    .put(NAME_KEY, recipient.toShortString())
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
        StartConversationFragment().show(supportFragmentManager, "StartConversationFragment")
    }
}

fun Context.startHomeActivity(isFromOnboarding: Boolean, isNewAccount: Boolean) {
    Intent(this, HomeActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        putExtra(NEW_ACCOUNT, isNewAccount)
        putExtra(FROM_ONBOARDING, isFromOnboarding)
    }.also(::startActivity)
}
