package org.thoughtcrime.securesms.conversation.v2.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.icu.text.BreakIterator
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity.CLIPBOARD_SERVICE
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.StringSubstitutionConstants.COMMUNITY_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.updateContact
import org.session.libsession.utilities.upsertContact
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.utilities.TextUtilities.textSizeInBytes
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.dependencies.ConfigFactory.Companion.MAX_GROUP_DESCRIPTION_BYTES
import org.thoughtcrime.securesms.dependencies.ConfigFactory.Companion.MAX_NAME_BYTES
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.ui.SimpleDialogData
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils


@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ConversationSettingsViewModel.Factory::class)
class ConversationSettingsViewModel @AssistedInject constructor(
    @Assisted private val address: Address.Conversable,
    @param:ApplicationContext private val context: Context,
    private val avatarUtils: AvatarUtils,
    private val repository: ConversationRepository,
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val conversationRepository: ConversationRepository,
    private val textSecurePreferences: TextSecurePreferences,
    private val navigator: UINavigator<ConversationSettingsDestination>,
    private val groupManagerV2: GroupManagerV2,
    private val prefs: TextSecurePreferences,
    private val lokiThreadDatabase: LokiThreadDatabase,
    private val groupManager: GroupManagerV2,
    private val openGroupManager: OpenGroupManager,
    private val recipientRepository: RecipientRepository,
    private val proStatusManager: ProStatusManager,
) : ViewModel() {

    private val threadId by lazy {
        requireNotNull(storage.getThreadId(address)) {
            "Thread doesn't exist for this conversation"
        }
    }

    private val _uiState: MutableStateFlow<UIState> = MutableStateFlow(
        UIState(
            avatarUIData = AvatarUIData(emptyList())
        )
    )
    val uiState: StateFlow<UIState> = _uiState

    private val _dialogState: MutableStateFlow<DialogsState> = MutableStateFlow(DialogsState())
    val dialogState: StateFlow<DialogsState> = _dialogState

    private var recipient: Recipient? = null

    private var groupV2: GroupInfo.ClosedGroupInfo? = null

    private val community: OpenGroup? by lazy {
        storage.getOpenGroup(threadId)
    }

    private val optionCopyAccountId: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.accountIDCopy),
            icon = R.drawable.ic_copy,
            qaTag = R.string.qa_conversation_settings_copy_account,
            onClick = ::copyAccountId
        )
    }

    private val optionSearch: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.searchConversation),
            icon = R.drawable.ic_search,
            qaTag = R.string.qa_conversation_settings_search,
            onClick = ::goBackToSearch
        )
    }


    private fun optionDisappearingMessage(subtitle: String?): OptionsItem {
        return OptionsItem(
            name = context.getString(R.string.disappearingMessages),
            subtitle = subtitle,
            icon = R.drawable.ic_timer,
            qaTag = R.string.qa_conversation_settings_disappearing,
            subtitleQaTag = R.string.qa_conversation_settings_disappearing_sub,
            onClick = {
                navigateTo(ConversationSettingsDestination.RouteDisappearingMessages)
            }
        )
    }

    private val optionPin: OptionsItem by lazy {
        OptionsItem(
            name = context.getString(R.string.pinConversation),
            icon = R.drawable.ic_pin,
            qaTag = R.string.qa_conversation_settings_pin,
            onClick = ::pinConversation
        )
    }

    private val optionUnpin: OptionsItem by lazy {
        OptionsItem(
            name = context.getString(R.string.pinUnpinConversation),
            icon = R.drawable.ic_pin_off,
            qaTag = R.string.qa_conversation_settings_pin,
            onClick = ::unpinConversation
        )
    }

    private fun optionNotifications(iconRes: Int, subtitle: String?): OptionsItem {
        return OptionsItem(
            name = context.getString(R.string.sessionNotifications),
            subtitle = subtitle,
            icon = iconRes,
            qaTag = R.string.qa_conversation_settings_notifications,
            subtitleQaTag = R.string.qa_conversation_settings_notifications_sub,
            onClick = {
                navigateTo(ConversationSettingsDestination.RouteNotifications)
            }
        )
    }

    private val optionAttachments: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.attachments),
            icon = R.drawable.ic_file,
            qaTag = R.string.qa_conversation_settings_attachments,
            onClick = {
                navigateTo(ConversationSettingsDestination.RouteAllMedia)
            }
        )
    }

    private val optionBlock: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.block),
            icon = R.drawable.ic_user_round_x,
            qaTag = R.string.qa_conversation_settings_block,
            onClick = ::confirmBlockUser
        )
    }

    private val optionUnblock: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.blockUnblock),
            icon = R.drawable.ic_user_round_tick,
            qaTag = R.string.qa_conversation_settings_block,
            onClick = ::confirmUnblockUser
        )
    }

    private val optionClearMessages: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.clearMessages),
            icon = R.drawable.ic_message_trash_custom,
            qaTag = R.string.qa_conversation_settings_clear_messages,
            onClick = ::confirmClearMessages
        )
    }

    private val optionDeleteConversation: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.conversationsDelete),
            icon = R.drawable.ic_trash_2,
            qaTag = R.string.qa_conversation_settings_delete_conversation,
            onClick = ::confirmDeleteConversation
        )
    }

    private val optionDeleteContact: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.contactDelete),
            icon = R.drawable.ic_user_round_trash,
            qaTag = R.string.qa_conversation_settings_delete_contact,
            onClick = ::confirmDeleteContact
        )
    }

    private val optionHideNTS: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.noteToSelfHide),
            icon = R.drawable.ic_eye_off,
            qaTag = R.string.qa_conversation_settings_hide_nts,
            onClick = ::confirmHideNTS
        )
    }

    private val optionShowNTS: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.showNoteToSelf),
            icon = R.drawable.ic_eye,
            qaTag = R.string.qa_conversation_settings_hide_nts,
            onClick = ::confirmShowNTS
        )
    }

    // Groups
    private val optionGroupMembers: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.groupMembers),
            icon = R.drawable.ic_users_round,
            qaTag = R.string.qa_conversation_settings_group_members,
            onClick = {
                groupV2?.let {
                    navigateTo(ConversationSettingsDestination.RouteGroupMembers(
                        Address.Group(AccountId(it.groupAccountId))
                    ))
                }
            }
        )
    }

    private val optionInviteMembers: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.membersInvite),
            icon = R.drawable.ic_user_round_plus,
            qaTag = R.string.qa_conversation_settings_invite_contacts,
            onClick = {
                navigateTo(ConversationSettingsDestination.RouteInviteToCommunity(
                    communityUrl = community?.joinURL ?: ""
                ))
            }
        )
    }

    private val optionManageMembers: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.manageMembers),
            icon = R.drawable.ic_user_round_pen,
            qaTag = R.string.qa_conversation_settings_manage_members,
            onClick = {
                groupV2?.let {
                    navigateTo(ConversationSettingsDestination.RouteManageMembers(
                        Address.Group(AccountId(it.groupAccountId))
                    ))
                }
            }
        )
    }

    private val optionLeaveGroup: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.groupLeave),
            icon = R.drawable.ic_log_out,
            qaTag = R.string.qa_conversation_settings_leave_group,
            onClick = ::confirmLeaveGroup
        )
    }

    private val optionDeleteGroup: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.groupDelete),
            icon = R.drawable.ic_trash_2,
            qaTag = R.string.qa_conversation_settings_delete_group,
            onClick = ::confirmLeaveGroup
        )
    }

    // Community
    private val optionCopyCommunityURL: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.communityUrlCopy),
            icon = R.drawable.ic_copy,
            qaTag = R.string.qa_conversation_settings_copy_community_url,
            onClick = ::copyCommunityUrl
        )
    }

    private val optionLeaveCommunity: OptionsItem by lazy{
        OptionsItem(
            name = context.getString(R.string.communityLeave),
            icon = R.drawable.ic_log_out,
            qaTag = R.string.qa_conversation_settings_leave_community,
            onClick = ::confirmLeaveCommunity
        )
    }

    init {
        // update data when we have a recipient and update when there are changes from the thread or recipient
        viewModelScope.launch {
            recipientRepository.observeRecipient(address)
                .filterNotNull()
                .collect {
                    recipient = it
                    getStateFromRecipient(it)
                }
        }
    }

    fun onResume(){
        // check the mute timing in case it has changed when coming back to the screen
        val conversation = recipient ?: return

        // Check if notification item exists first
        val hasNotificationItem = _uiState.value.categories
            .flatMap { it.items }
            .flatMap { it.items }
            .firstOrNull { it.qaTag == R.string.qa_conversation_settings_notifications }

        // no need to do anything if the state doesn't have any notification item
        if (hasNotificationItem == null) return

        // get the new values
        val (notificationIconRes, notificationSubtitle) = getNotificationsData(conversation)

        // if they are the same as what we already have, no need to go further
        if (notificationIconRes == hasNotificationItem.icon &&
            notificationSubtitle == hasNotificationItem.subtitle) return

        // otherwise update the text
        _uiState.update { currentState ->
            // Update the item
            currentState.copy(
                categories = currentState.categories.map { category ->
                    category.copy(
                        items = category.items.map { subCategory ->
                            subCategory.copy(
                                items = subCategory.items.map { item ->
                                    if (item.qaTag == R.string.qa_conversation_settings_notifications) {
                                        item.copy(subtitle = notificationSubtitle, icon = notificationIconRes)
                                    } else item
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    private fun getStateFromRecipient(conversation: Recipient){
        val configContact = configFactory.withUserConfigs { configs ->
            configs.contacts.get(conversation.address.toString())
        }

        groupV2 = if(conversation.isGroupV2Recipient) configFactory.getGroup(AccountId(conversation.address.toString()))
        else null

        // admin
        val isAdmin: Boolean = conversation.takeIf { it.isGroupV2Recipient || it.isCommunityRecipient }
            ?.currentUserRole?.canModerate == true

        // edit name - Can edit name for 1on1, or if admin of a groupV2
        val editCommand = when {
            conversation.is1on1 -> Commands.ShowNicknameDialog
            conversation.isGroupV2Recipient && isAdmin -> Commands.ShowGroupEditDialog
            else -> null
        }

        // description / display name with QA tags
        val (description: String?, descriptionQaTag: String?) = when{
            // for 1on1, if the user has a nickname it should be displayed as the
            // main name, and the description should show the real name in parentheses
            conversation.is1on1 -> {
                if(configContact?.nickname?.isNotEmpty() == true && configContact.name.isNotEmpty()) {
                    (
                        "(${configContact.name})" to // description
                        context.getString(R.string.qa_conversation_settings_description_1on1) // description qa tag
                    )
                } else (null to null)
            }

            conversation.isGroupV2Recipient -> {
                if(groupV2 == null) (null to null)
                else {
                    (
                        configFactory.withGroupConfigs(AccountId(groupV2!!.groupAccountId)){
                            it.groupInfo.getDescription()
                        } to // description
                        context.getString(R.string.qa_conversation_settings_description_groups) // description qa tag
                    )
                }
            }

            conversation.isCommunityRecipient -> {
                (
                    community?.description to // description
                    context.getString(R.string.qa_conversation_settings_description_community) // description qa tag
                )
            }

            else -> (null to null)
        }

        // name
        val name = when {
            conversation.isLocalNumber -> context.getString(R.string.noteToSelf)
            else -> conversation.displayName()
        }

        // account ID
        val (accountId, accountIdHeader) = when {
            conversation.isLocalNumber -> conversation.address.toString() to context.getString(R.string.accountIdYours)
            conversation.address is Address.WithAccountId &&
                    !conversation.address.isGroupOrCommunity -> conversation.address.accountId.hexString to context.getString(R.string.accountId)
            else -> null to null
        }

        // QR Account ID
        val qrAddress = when {
            conversation.is1on1 -> conversation.address.toString()
            conversation.isCommunityRecipient -> community?.joinURL
            else -> null
        }

        // disappearing message type
        val expiryMode = recipient?.expiryMode
        val disappearingSubtitle = if(expiryMode != null && expiryMode != ExpiryMode.NONE) {
            // Get the type of disappearing message and the abbreviated duration..
            val dmTypeString = when (expiryMode) {
                is ExpiryMode.AfterRead -> R.string.disappearingMessagesDisappearAfterReadState
                else -> R.string.disappearingMessagesDisappearAfterSendState
            }
            val durationAbbreviated =
                ExpirationUtil.getExpirationAbbreviatedDisplayValue(expiryMode.expirySeconds)

            // ..then substitute into the string..
            context.getSubbedString(
                dmTypeString,
                TIME_KEY to durationAbbreviated
            )
        } else context.getString(R.string.off)

        val pinned = recipient?.isPinned == true

        val (notificationIconRes, notificationSubtitle) = getNotificationsData(conversation)

        // organise the setting options
        val optionData = options@when {
            conversation.isLocalNumber -> {
                val mainOptions = mutableListOf<OptionsItem>()
                val dangerOptions = mutableListOf<OptionsItem>()

                val ntsHidden = conversation.priority == PRIORITY_HIDDEN

                mainOptions.addAll(listOf(
                    optionCopyAccountId,
                    optionSearch,
                    optionDisappearingMessage(disappearingSubtitle),
                    if(pinned) optionUnpin else optionPin,
                    optionAttachments,
                ))

                if(ntsHidden) mainOptions.add(optionShowNTS)
                else dangerOptions.add(optionHideNTS)

                dangerOptions.addAll(listOf(
                    optionClearMessages,
                ))

                listOf(
                    OptionsCategory(
                        items = listOf(
                            OptionsSubCategory(items = mainOptions),
                            OptionsSubCategory(
                                danger = true,
                                items = dangerOptions
                            )
                        )
                    )
                )
            }

            conversation.is1on1 -> {
                val mainOptions = mutableListOf<OptionsItem>()
                val dangerOptions = mutableListOf<OptionsItem>()

                mainOptions.addAll(listOf(
                    optionCopyAccountId,
                    optionSearch
                ))

                // these options are only for users who aren't blocked
                if(!conversation.blocked) {
                    mainOptions.addAll(listOf(
                        optionDisappearingMessage(disappearingSubtitle),
                        if(pinned) optionUnpin else optionPin,
                        optionNotifications(notificationIconRes, notificationSubtitle),
                    ))
                }

                // finally add attachments
                mainOptions.add(optionAttachments)

                dangerOptions.addAll(listOf(
                    if(recipient?.blocked == true) optionUnblock else optionBlock,
                    optionClearMessages,
                    optionDeleteConversation,
                    optionDeleteContact
                ))

                listOf(
                    OptionsCategory(
                        items = listOf(
                            OptionsSubCategory(items = mainOptions),
                            OptionsSubCategory(
                                danger = true,
                                items = dangerOptions
                            )
                        )
                    )
                )
            }

            conversation.isGroupV2Recipient -> {
                // if the user is kicked or the group destroyed, only show "Delete Group"
                if(groupV2 != null && groupV2?.shouldPoll == false){
                    listOf(
                            OptionsCategory(
                                items = listOf(
                                    OptionsSubCategory(
                                        danger = true,
                                        items = listOf(optionDeleteGroup)
                                    )
                                )
                            )
                    )
                } else {
                    val mainOptions = mutableListOf<OptionsItem>()
                    val adminOptions = mutableListOf<OptionsItem>()
                    val dangerOptions = mutableListOf<OptionsItem>()

                    mainOptions.add(optionSearch)

                    // for non admins, disappearing messages is in the non admin section
                    if (!isAdmin) {
                        mainOptions.add(optionDisappearingMessage(disappearingSubtitle))
                    }

                    mainOptions.addAll(
                        listOf(
                            if (pinned) optionUnpin else optionPin,
                            optionNotifications(notificationIconRes, notificationSubtitle),
                            optionGroupMembers,
                            optionAttachments,
                        )
                    )

                    // apply different options depending on admin status
                    if (isAdmin) {
                        dangerOptions.addAll(
                            listOf(
                                optionClearMessages,
                                optionDeleteGroup
                            )
                        )

                        // admin options
                        adminOptions.addAll(
                            listOf(
                                optionManageMembers,
                                optionDisappearingMessage(disappearingSubtitle)
                            )
                        )

                        // the returned options for group admins
                        listOf(
                            OptionsCategory(
                                items = listOf(
                                    OptionsSubCategory(items = mainOptions),
                                )
                            ),
                            OptionsCategory(
                                name = context.getString(R.string.adminSettings),
                                items = listOf(
                                    OptionsSubCategory(items = adminOptions),
                                    OptionsSubCategory(
                                        danger = true,
                                        items = dangerOptions
                                    )
                                )
                            )
                        )
                    } else {
                        dangerOptions.addAll(
                            listOf(
                                optionClearMessages,
                                optionLeaveGroup
                            )
                        )

                        // the returned options for group non-admins
                        listOf(
                            OptionsCategory(
                                items = listOf(
                                    OptionsSubCategory(items = mainOptions),
                                    OptionsSubCategory(
                                        danger = true,
                                        items = dangerOptions
                                    )
                                )
                            )
                        )
                    }
                }
            }

            conversation.isCommunityRecipient -> {
                val mainOptions = mutableListOf<OptionsItem>()
                val dangerOptions = mutableListOf<OptionsItem>()

                mainOptions.addAll(listOf(
                    optionCopyCommunityURL,
                    optionSearch,
                    if(pinned) optionUnpin else optionPin,
                    optionNotifications(notificationIconRes, notificationSubtitle),
                    optionInviteMembers,
                    optionAttachments,
                ))

                dangerOptions.addAll(listOf(
                    optionClearMessages,
                    optionLeaveCommunity
                ))

                listOf(
                    OptionsCategory(
                        items = listOf(
                            OptionsSubCategory(items = mainOptions),
                            OptionsSubCategory(
                                danger = true,
                                items = dangerOptions
                            )
                        )
                    )
                )
            }

            else -> emptyList()
        }

        val showProBadge = proStatusManager.shouldShowProBadge(conversation.address)
                && !conversation.isLocalNumber

        // if it's a one on one convo and the user isn't pro themselves
        val proBadgeClickable = if(conversation.is1on1 && proStatusManager.isCurrentUserPro()) false
        else showProBadge // otherwise whenever the badge is shown

        val avatarData = avatarUtils.getUIDataFromRecipient(conversation)
        _uiState.update {
            _uiState.value.copy(
                name = name,
                nameQaTag = when {
                    conversation.isLocalNumber -> context.getString(R.string.qa_conversation_settings_display_name_nts)
                    conversation.is1on1 -> context.getString(R.string.qa_conversation_settings_display_name_1on1)
                    conversation.isGroupV2Recipient -> context.getString(R.string.qa_conversation_settings_display_name_groups)
                    conversation.isCommunityRecipient -> context.getString(R.string.qa_conversation_settings_display_name_community)
                    else -> null
                },
                editCommand = editCommand,
                description = description,
                descriptionQaTag = descriptionQaTag,
                displayAccountId = accountId,
                avatarUIData = avatarData,
                categories = optionData,
                showProBadge = showProBadge,
                proBadgeClickable = proBadgeClickable,
                displayAccountIdHeader = accountIdHeader,
                qrAddress = qrAddress,
            )
        }
    }

    private fun getNotificationsData(conversation: Recipient): Pair<Int, String> {
        return when{
            conversation.isMuted() -> R.drawable.ic_volume_off to context.getString(R.string.notificationsMuted)
            conversation.notifyType == NotifyType.MENTIONS ->
                R.drawable.ic_at_sign to context.getString(R.string.notificationsMentionsOnly)
            else -> R.drawable.ic_volume_2 to context.getString(R.string.notificationsAllMessages)
        }
    }

    private fun copyAccountId(){
        val accountID = recipient?.address?.toString() ?: ""
        val clip = ClipData.newPlainText("Account ID", accountID)
        val manager = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(clip)
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyCommunityUrl(){
        val url = community?.joinURL ?: return
        val clip = ClipData.newPlainText(context.getString(R.string.communityUrl), url)
        val manager = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(clip)
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun pinConversation(){
        // check the pin limit before continuing
        val totalPins = storage.getTotalPinned()
        val maxPins = proStatusManager.getPinnedConversationLimit()
        if(totalPins >= maxPins){
            // the user has reached the pin limit, show the CTA
            _dialogState.update {
                it.copy(pinCTA = PinProCTA(overTheLimit = totalPins > maxPins))
            }
        } else {
            viewModelScope.launch {
                storage.setPinned(address, true)
            }
        }
    }

    private fun unpinConversation(){
        storage.setPinned(address, false)
    }

    private fun confirmBlockUser(){
        _dialogState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.block),
                    message = Phrase.from(context, R.string.blockDescription)
                        .put(NAME_KEY, recipient?.displayName().orEmpty())
                        .format(),
                    positiveText = context.getString(R.string.block),
                    negativeText = context.getString(R.string.cancel),
                    positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_block_confirm),
                    negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_block_cancel),
                    onPositive = ::blockUser,
                    onNegative = {}
                )
            )
        }
    }

    private fun confirmUnblockUser(){
        _dialogState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.blockUnblock),
                    message = Phrase.from(context, R.string.blockUnblockName)
                        .put(NAME_KEY, recipient?.displayName().orEmpty())
                        .format(),
                    positiveText = context.getString(R.string.blockUnblock),
                    negativeText = context.getString(R.string.cancel),
                    positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_unblock_confirm),
                    negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_unblock_cancel),
                    onPositive = ::unblockUser,
                    onNegative = {}
                )
            )
        }
    }

    private fun blockUser() {
        val conversation = recipient ?: return
        viewModelScope.launch {
            if (conversation.isStandardRecipient || conversation.isGroupV2Recipient) {
                repository.setBlocked(conversation.address, true)
            }

            if (conversation.isGroupV2Recipient) {
                groupManagerV2.onBlocked(AccountId(conversation.address.toString()))
            }
        }
    }

    private fun unblockUser() {
        if(recipient == null) return
        viewModelScope.launch {
            repository.setBlocked(recipient!!.address, false)
        }
    }

    private fun confirmHideNTS(){
        _dialogState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.noteToSelfHide),
                    message = context.getText(R.string.hideNoteToSelfDescription),
                    positiveText = context.getString(R.string.hide),
                    negativeText = context.getString(R.string.cancel),
                    positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_hide_nts_confirm),
                    negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_hide_nts_cancel),
                    onPositive = ::hideNoteToSelf,
                    onNegative = {}
                )
            )
        }
    }

    private fun confirmShowNTS(){
        _dialogState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.showNoteToSelf),
                    message = context.getText(R.string.showNoteToSelfDescription),
                    positiveText = context.getString(R.string.show),
                    negativeText = context.getString(R.string.cancel),
                    positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_show_nts_confirm),
                    negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_show_nts_cancel),
                    positiveStyleDanger = false,
                    onPositive = ::showNoteToSelf,
                    onNegative = {}
                )
            )
        }
    }

    private fun hideNoteToSelf() {
        configFactory.withMutableUserConfigs {
            it.userProfile.setNtsPriority(PRIORITY_HIDDEN)
        }
    }

    fun showNoteToSelf() {
        configFactory.withMutableUserConfigs {
            it.userProfile.setNtsPriority(PRIORITY_VISIBLE)
        }
    }

    private fun confirmDeleteContact(){
        _dialogState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.contactDelete),
                    message = Phrase.from(context, R.string.deleteContactDescription)
                        .put(NAME_KEY, recipient?.displayName().orEmpty())
                        .format(),
                    positiveText = context.getString(R.string.delete),
                    negativeText = context.getString(R.string.cancel),
                    positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_delete_contact_confirm),
                    negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_delete_contact_cancel),
                    onPositive = ::deleteContact,
                    onNegative = {}
                )
            )
        }
    }

    private fun deleteContact() {
        val contact = address as? Address.WithAccountId ?: return
        viewModelScope.launch {
            showLoading()
            configFactory.removeContactOrBlindedContact(contact)
            hideLoading()
            goBackHome()
        }
    }

    private fun confirmDeleteConversation(){
        _dialogState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.conversationsDelete),
                    message = Phrase.from(context, R.string.deleteConversationDescription)
                        .put(NAME_KEY, recipient?.displayName().orEmpty())
                        .format(),
                    positiveText = context.getString(R.string.delete),
                    negativeText = context.getString(R.string.cancel),
                    positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_delete_conversation_confirm),
                    negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_delete_conversation_cancel),
                    onPositive = ::deleteConversation,
                    onNegative = {}
                )
            )
        }
    }

    private fun deleteConversation() {
        viewModelScope.launch {
            if (address is Address.Standard) {
                configFactory.withMutableUserConfigs { configs ->
                    configs.contacts.updateContact(address) {
                        priority = PRIORITY_HIDDEN
                    }
                }
            }

            goBackHome()
        }
    }

    private fun confirmLeaveCommunity(){
        _dialogState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.communityLeave),
                    message = Phrase.from(context, R.string.groupLeaveDescription)
                        .put(GROUP_NAME_KEY, recipient?.displayName().orEmpty())
                        .format(),
                    positiveText = context.getString(R.string.leave),
                    negativeText = context.getString(R.string.cancel),
                    positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_leave_community_confirm),
                    negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_leave_community_cancel),
                    onPositive = ::leaveCommunity,
                    onNegative = {}
                )
            )
        }
    }

    private fun leaveCommunity() {
        viewModelScope.launch {
            val address = requireNotNull(address as? Address.Community) {
                "Address must be a Community address to leave a community"
            }

            openGroupManager.delete(address.serverUrl, address.room)
            goBackHome()
        }
    }

    private fun confirmClearMessages(){
        val conversation = recipient ?: return

        // default to 1on1
        var message: CharSequence = Phrase.from(context, R.string.clearMessagesChatDescriptionUpdated)
            .put(NAME_KEY,conversation.displayName())
            .format()

        when{
            conversation.isGroupV2Recipient -> {
                if(groupV2?.hasAdminKey() == true){
                    // group admin clearing messages have a dedicated custom dialog
                    _dialogState.update { it.copy(groupAdminClearMessagesDialog = GroupAdminClearMessageDialog(conversation.displayName())) }
                    return

                } else {
                    message = Phrase.from(context, R.string.clearMessagesGroupDescriptionUpdated)
                        .put(GROUP_NAME_KEY, conversation.displayName())
                        .format()
                }
            }

            conversation.isCommunityRecipient -> {
                message = Phrase.from(context, R.string.clearMessagesCommunityUpdated)
                    .put(COMMUNITY_NAME_KEY, conversation.displayName())
                    .format()
            }

            conversation.isLocalNumber -> {
                message = context.getText(R.string.clearMessagesNoteToSelfDescriptionUpdated)
            }
        }

        _dialogState.update {
            it.copy(
                showSimpleDialog = SimpleDialogData(
                    title = context.getString(R.string.clearMessages),
                    message = message,
                    positiveText = context.getString(R.string.clear),
                    negativeText = context.getString(R.string.cancel),
                    positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_clear_messages_confirm),
                    negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_clear_messages_cancel),
                    onPositive = { clearMessages(false) },
                    onNegative = {}
                )
            )
        }
    }

    private fun clearMessages(clearForEveryoneGroupsV2: Boolean) {
        viewModelScope.launch {
            showLoading()
            try {
                withContext(Dispatchers.Default) {
                    conversationRepository.clearAllMessages(
                        threadId,
                        if (clearForEveryoneGroupsV2 && groupV2 != null) AccountId(groupV2!!.groupAccountId) else null
                    )
                }

                Toast.makeText(context, context.resources.getQuantityString(
                    R.plurals.deleteMessageDeleted,
                    2, // as per the ACs, we decided to always show this message as plural
                    2
                ), Toast.LENGTH_LONG).show()
            } catch (e: Exception){
                Toast.makeText(context, context.resources.getQuantityString(
                    R.plurals.deleteMessageFailed,
                    2, // we don't care about the number, just that it is multiple messages since we are doing "Clear All"
                    2
                ), Toast.LENGTH_LONG).show()
            }

            hideLoading()
        }
    }

    private fun confirmLeaveGroup(){
        val groupData = groupV2 ?: return
        _dialogState.update { state ->
            val dialogData = groupManager.getLeaveGroupConfirmationDialogData(
                AccountId(groupData.groupAccountId),
                _uiState.value.name
            ) ?: return

            state.copy(
                showSimpleDialog = SimpleDialogData(
                    title = dialogData.title,
                    message = dialogData.message,
                    positiveText = context.getString(dialogData.positiveText),
                    negativeText = context.getString(dialogData.negativeText),
                    positiveQaTag = dialogData.positiveQaTag?.let{ context.getString(it) },
                    negativeQaTag = dialogData.negativeQaTag?.let{ context.getString(it) },
                    onPositive = ::leaveGroup,
                    onNegative = {}
                )
            )
        }
    }

    private fun leaveGroup() {
        val conversation = recipient ?: return
        viewModelScope.launch {
            showLoading()

            try {
                withContext(Dispatchers.Default) {
                    groupManagerV2.leaveGroup(AccountId(conversation.address.toString()))
                }
                hideLoading()
                goBackHome()
            } catch (e: Exception){
                hideLoading()

                val txt = Phrase.from(context, R.string.groupLeaveErrorFailed)
                    .put(GROUP_NAME_KEY, conversation.displayName())
                    .format().toString()
                Toast.makeText(context, txt, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun goBackHome(){
        navigator.navigateToIntent(
            Intent(context, HomeActivity::class.java).apply {
                // pop back to home activity
                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
    }

    private fun goBackToSearch(){
        viewModelScope.launch {
            navigator.returnResult(ConversationActivityV2.SHOW_SEARCH, true)
        }
    }

    /**
     * This returns the number of visible glyphs in a string, instead of its underlying length
     * For example: 👨🏻‍❤️‍💋‍👨🏻 has a length of 15 as a string, but would return 1 here as it is only one visible element
     */
    private fun getDisplayedCharacterSize(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) {
            count++
        }
        return count
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.CopyAccountId -> copyAccountId()

            is Commands.HideSimpleDialog -> _dialogState.update {
                it.copy(showSimpleDialog = null)
            }

            is Commands.HideGroupAdminClearMessagesDialog -> _dialogState.update {
                it.copy(groupAdminClearMessagesDialog = null)
            }

            is Commands.ClearMessagesGroupDeviceOnly -> clearMessages(false)
            is Commands.ClearMessagesGroupEveryone -> clearMessages(true)

            is Commands.HideNicknameDialog -> hideNicknameDialog()

            is Commands.ShowNicknameDialog -> showNicknameDialog()

            is Commands.ShowGroupEditDialog -> showGroupEditDialog()

            is Commands.HideGroupEditDialog -> hideGroupEditDialog()

            is Commands.HidePinCTADialog -> {
                _dialogState.update { it.copy(pinCTA = null) }
            }

            is Commands.RemoveNickname -> {
                setNickname(null)

                hideNicknameDialog()
            }

            is Commands.SetNickname -> {
                setNickname(_dialogState.value.nicknameDialog?.inputNickname?.trim())

                hideNicknameDialog()
            }

            is Commands.UpdateNickname -> {
                val trimmedName = command.nickname.trim()

                val error: String? = when {
                    trimmedName.textSizeInBytes() > MAX_NAME_BYTES -> context.getString(R.string.nicknameErrorShorter)

                    else -> null
                }

                _dialogState.update {
                    it.copy(
                        nicknameDialog = it.nicknameDialog?.copy(
                            inputNickname = command.nickname,
                            setEnabled = trimmedName.isNotEmpty() && // can save if we have an input
                                    trimmedName != it.nicknameDialog.currentNickname && // ... and it isn't the same as what is already saved
                                error == null, // ... and there are no errors
                            error = error
                        )
                    )
                }
            }

            is Commands.UpdateGroupName -> {
                val trimmedName = command.name.trim()

                val error: String? = when {
                    trimmedName.textSizeInBytes() > MAX_NAME_BYTES -> context.getString(R.string.groupNameEnterShorter)

                    else -> null
                }

                _dialogState.update {
                    it.copy(
                        groupEditDialog = it.groupEditDialog?.copy(
                            inputName = command.name,
                            saveEnabled = trimmedName.isNotEmpty() && // can save if we have an input
                                    trimmedName != it.groupEditDialog.currentName && // ... and it isn't the same as what is already saved
                                    error == null && // ... and there are no name errors
                                    it.groupEditDialog.errorDescription == null, // ... and there are no description errors
                            errorName = error
                        )
                    )
                }
            }

            is Commands.UpdateGroupDescription -> {
                val trimmedDescription = command.description.trim()

                val error: String? = when {
                    // description should be less than 200 characters
                    getDisplayedCharacterSize(trimmedDescription) > 200 -> context.getString(R.string.updateGroupInformationEnterShorterDescription)

                    // description should be less than max bytes
                    trimmedDescription.textSizeInBytes() > MAX_GROUP_DESCRIPTION_BYTES -> context.getString(R.string.updateGroupInformationEnterShorterDescription)

                    else -> null
                }

                _dialogState.update {
                    it.copy(
                        groupEditDialog = it.groupEditDialog?.copy(
                            inputtedDescription = command.description,
                            saveEnabled = trimmedDescription != it.groupEditDialog.currentName && // ... and it isn't the same as what is already saved
                                    error == null && // ... and there are no description errors
                                    it.groupEditDialog.inputName?.trim()?.isNotEmpty() ==  true && // ... and there is a name input
                                    it.groupEditDialog.errorName == null, // ... and there are no name errors
                            errorDescription = error
                        )
                    )
                }
            }

            is Commands.SetGroupText -> {
                val groupData = groupV2 ?: return
                val dialogData = _dialogState.value.groupEditDialog ?: return

                showLoading()
                hideGroupEditDialog()
                viewModelScope.launch {
                    // save name if needed
                    if(dialogData.inputName != dialogData.currentName) {
                        groupManager.setName(
                            AccountId(groupData.groupAccountId),
                            dialogData.inputName ?: dialogData.currentName
                        )
                    }

                    // save description if needed
                    if(dialogData.inputtedDescription != dialogData.currentDescription) {
                        groupManager.setDescription(
                            AccountId(groupData.groupAccountId),
                            dialogData.inputtedDescription ?: ""
                        )
                    }

                    hideLoading()
                }
            }

            is Commands.ToggleQR -> {
                _uiState.update {
                    it.copy(showQR = !it.showQR)
                }
            }

            is Commands.ToggleAvatarExpand -> {
                _uiState.update {
                    it.copy(expandedAvatar = !it.expandedAvatar)
                }
            }

            is Commands.ShowProBadgeCTA -> {
                _dialogState.update {
                    it.copy(
                        proBadgeCTA = if(recipient?.isGroupV2Recipient == true) ProBadgeCTA.Group
                        else ProBadgeCTA.Generic
                    )
                }
            }

            is Commands.HideProBadgeCTA -> {
                _dialogState.update { it.copy(proBadgeCTA = null) }
            }
        }
    }

    private fun setNickname(nickname: String?){
        val conversation = recipient ?: return

        if (!conversation.isSelf && conversation.address is Address.Standard) {
            configFactory.withMutableUserConfigs { configs ->
                configs.contacts.upsertContact(conversation.address) {
                    this.nickname = nickname.orEmpty()
                }
            }
        }
    }

    private fun showNicknameDialog(){
        val conversation = recipient ?: return

        val configContact = configFactory.withUserConfigs { configs ->
            configs.contacts.get(conversation.address.toString())
        }

        _dialogState.update {
            it.copy(
                nicknameDialog = NicknameDialogData(
                    name = configContact?.name ?: "",
                    currentNickname = configContact?.nickname,
                    inputNickname = configContact?.nickname,
                    setEnabled = false,
                    removeEnabled = configContact?.nickname?.isEmpty() == false,  // can only remove is we have a nickname already
                    error = null
                ),
                groupEditDialog = null
            )
        }
    }

    private fun showGroupEditDialog(){
        val groupName = _uiState.value.name
        val groupDescription = _uiState.value.description

        _dialogState.update {
            it.copy(groupEditDialog = GroupEditDialog(
                currentName = groupName,
                inputName = groupName,
                currentDescription = groupDescription,
                inputtedDescription = groupDescription,
                saveEnabled = false,
                errorName = null,
                errorDescription = null
            ))
        }
    }

    private fun hideNicknameDialog(){
        _dialogState.update {
            it.copy(nicknameDialog = null)
        }
    }

    private fun hideGroupEditDialog(){
        _dialogState.update {
            it.copy(groupEditDialog = null)
        }
    }

    private fun showLoading(){
        _uiState.update {
            it.copy(showLoading = true)
        }
    }

    private fun hideLoading(){
        _uiState.update {
            it.copy(showLoading = false)
        }
    }

    private fun navigateTo(destination: ConversationSettingsDestination){
        viewModelScope.launch {
            navigator.navigate(destination)
        }
    }

    fun inviteContactsToCommunity(contacts: Set<Address>) {
        showLoading()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    repository.inviteContactsToCommunity(threadId, contacts)
                }

                hideLoading()

                // show confirmation toast
                Toast.makeText(context, context.resources.getQuantityString(
                    R.plurals.groupInviteSending,
                    contacts.size,
                    contacts.size
                ), Toast.LENGTH_LONG).show()
            } catch (e: Exception){
                Log.w("", "Error sending community invites", e)
                hideLoading()
                Toast.makeText(context, R.string.errorUnknown, Toast.LENGTH_LONG).show()
            }
        }
    }

    sealed interface Commands {
        data object CopyAccountId : Commands
        data object HideSimpleDialog : Commands
        data object HideGroupAdminClearMessagesDialog : Commands
        data object ClearMessagesGroupDeviceOnly : Commands
        data object ClearMessagesGroupEveryone : Commands

        // dialogs
        data object ShowNicknameDialog : Commands
        data object HideNicknameDialog : Commands
        data object RemoveNickname : Commands
        data object SetNickname: Commands
        data class UpdateNickname(val nickname: String): Commands
        data class UpdateGroupName(val name: String): Commands
        data class UpdateGroupDescription(val description: String): Commands
        data object SetGroupText: Commands

        data object ShowGroupEditDialog : Commands
        data object HideGroupEditDialog : Commands

        data object HidePinCTADialog: Commands

        object ToggleAvatarExpand: Commands
        object ToggleQR: Commands

        object ShowProBadgeCTA: Commands
        object HideProBadgeCTA: Commands
    }

    @AssistedFactory
    interface Factory {
        fun create(address: Address.Conversable): ConversationSettingsViewModel
    }

    data class UIState(
        val avatarUIData: AvatarUIData,
        val name: String = "",
        val nameQaTag: String? = null,
        val description: String? = null,
        val descriptionQaTag: String? = null,
        val displayAccountId: String? = null, // account id to display directly on the screen
        val showLoading: Boolean = false,
        val showProBadge: Boolean = false,
        val proBadgeClickable: Boolean = false,
        val editCommand: Commands? = null,

        val displayAccountIdHeader: String? = null,
        val qrAddress: String? = null, // address to display as a qr code
        val expandedAvatar: Boolean = false,
        val showQR: Boolean = false,

        val categories: List<OptionsCategory> = emptyList()
    )

    data class OptionsCategory(
        val name: String? = null,
        val items: List<OptionsSubCategory> = emptyList()
    )

    data class OptionsSubCategory(
        val danger: Boolean = false,
        val items: List<OptionsItem> = emptyList()
    )

    data class OptionsItem(
        val name: String,
        @DrawableRes val icon: Int,
        @StringRes val qaTag: Int? = null,
        val subtitle: String? = null,
        @StringRes val subtitleQaTag: Int? = null,
        val enabled: Boolean = true,
        val onClick: () -> Unit
    )

    data class DialogsState(
        val pinCTA: PinProCTA? = null,
        val showSimpleDialog: SimpleDialogData? = null,
        val nicknameDialog: NicknameDialogData? = null,
        val groupEditDialog: GroupEditDialog? = null,
        val groupAdminClearMessagesDialog: GroupAdminClearMessageDialog? = null,
        val proBadgeCTA: ProBadgeCTA? = null
    )

    data class PinProCTA(
        val overTheLimit: Boolean
    )

    sealed interface ProBadgeCTA {
        data object Generic: ProBadgeCTA
        data object Group: ProBadgeCTA
    }

    data class NicknameDialogData(
        val name: String,
        val currentNickname: String?, // the currently saved nickname, if any
        val inputNickname: String?, // the nickname being inputted
        val setEnabled: Boolean,
        val removeEnabled: Boolean,
        val error: String?
    )

    data class GroupEditDialog(
        val currentName: String, // the currently saved name
        val inputName: String?, // the name being inputted
        val currentDescription: String?,
        val inputtedDescription: String?,
        val saveEnabled: Boolean,
        val errorName: String?,
        val errorDescription: String?,
    )

    data class GroupAdminClearMessageDialog(
        val groupName: String
    )
}
