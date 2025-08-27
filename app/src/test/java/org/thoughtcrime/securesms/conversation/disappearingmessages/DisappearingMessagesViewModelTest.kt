package org.thoughtcrime.securesms.conversation.disappearingmessages

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.recipients.ProStatus
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.thoughtcrime.securesms.BaseViewModelTest
import org.thoughtcrime.securesms.MainCoroutineRule
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.ExpiryRadioOption
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.UiState
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.OptionsCardData
import org.thoughtcrime.securesms.ui.UINavigator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val STANDARD_ADDRESS = "0538e63512fd78c04d45b83ec7f0f3d593f60276ce535d1160eb589a00cca7db59".toAddress()
private val GROUP_ADDRESS = "0338e63512fd78c04d45b83ec7f0f3d593f60276ce535d1160eb589a00cca7db59".toAddress()

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class DisappearingMessagesViewModelTest : BaseViewModelTest() {

    @get:Rule
    var mainCoroutineRule = MainCoroutineRule()

    @Mock lateinit var application: Application
    @Mock lateinit var disappearingMessages: DisappearingMessages
    @Mock lateinit var navigator: UINavigator<ConversationSettingsDestination>

    @Test
    fun `note to self, off, new config`() = runTest {
        val viewModel = createViewModel(Recipient(
            address = STANDARD_ADDRESS,
            data = RecipientData.Self(name = "Myself", avatar = null, expiryMode = ExpiryMode.NONE, priority = 1, proStatus = ProStatus.None,  profileUpdatedAt = null),
        ))

        advanceUntilIdle()

        assertThat(
            viewModel.state.value
        ).isEqualTo(
            State(
                isGroup = false,
                isSelfAdmin = true,
                address = STANDARD_ADDRESS,
                isNoteToSelf = true,
                expiryMode = ExpiryMode.NONE,
                isNewConfigEnabled = true,
                persistedMode = ExpiryMode.NONE,
                showDebugOptions = false
            )
        )

        assertThat(
            viewModel.uiState.value
        ).isEqualTo(
            UiState(
                OptionsCardData(
                    R.string.disappearingMessagesTimer,
                    typeOption(ExpiryMode.NONE, selected = true),
                    timeOption(ExpiryType.AFTER_SEND, 12.hours),
                    timeOption(ExpiryType.AFTER_SEND, 1.days),
                    timeOption(ExpiryType.AFTER_SEND, 7.days),
                    timeOption(ExpiryType.AFTER_SEND, 14.days)
                ),
                disableSetButton = true,
                subtitle = GetString(R.string.disappearingMessagesDisappearAfterSendDescription)
            )
        )
    }

    @Test
    fun `group, off, admin, new config`() = runTest {
        val recipient = Recipient(
            address = GROUP_ADDRESS,
            data = RecipientData.Group(
                partial = RecipientData.PartialGroup(
                    name = "Group Name",
                    avatar = null,
                    expiryMode = ExpiryMode.NONE,
                    approved = true,
                    priority = 1,
                    isAdmin = true,
                    destroyed = false,
                    kicked = false,
                    proStatus = ProStatus.None,
                    members = listOf()
                ),
                firstMember = Recipient(
                    address = STANDARD_ADDRESS,
                    data = RecipientData.Self(
                        name = "Myself",
                        avatar = null,
                        expiryMode = ExpiryMode.NONE,
                        priority = 1,
                        proStatus = ProStatus.None,
                        profileUpdatedAt = null
                    )
                ),
                secondMember = null,
            ),
        )

        val viewModel = createViewModel(recipient)

        advanceUntilIdle()

        assertThat(
            viewModel.state.value
        ).isEqualTo(
            State(
                isGroup = true,
                isSelfAdmin = true,
                address = GROUP_ADDRESS,
                isNoteToSelf = false,
                expiryMode = ExpiryMode.NONE,
                isNewConfigEnabled = true,
                persistedMode = ExpiryMode.NONE,
                showDebugOptions = false
            )
        )

        assertThat(
            viewModel.uiState.value
        ).isEqualTo(
            UiState(
                OptionsCardData(
                    title = R.string.disappearingMessagesTimer,
                    typeOption(ExpiryMode.NONE, selected = true),
                    timeOption(ExpiryType.AFTER_SEND, 12.hours),
                    timeOption(ExpiryType.AFTER_SEND, 1.days),
                    timeOption(ExpiryType.AFTER_SEND, 7.days),
                    timeOption(ExpiryType.AFTER_SEND, 14.days)
                ),
                disableSetButton = true,
                showGroupFooter = true,
                subtitle = GetString(R.string.disappearingMessagesDisappearAfterSendDescription)
            )
        )
    }

    @Test
    fun `group, off, not admin, new config`() = runTest {
        val recipient = Recipient(
            address = GROUP_ADDRESS,
            data = RecipientData.Group(
                partial = RecipientData.PartialGroup(
                    name = "Group Name",
                    avatar = null,
                    expiryMode = ExpiryMode.NONE,
                    approved = true,
                    priority = 1,
                    isAdmin = false,
                    destroyed = false,
                    kicked = false,
                    proStatus = ProStatus.None,
                    members = listOf()
                ),
                firstMember = Recipient(
                    address = STANDARD_ADDRESS,
                    data = RecipientData.Self(
                        name = "Myself",
                        avatar = null,
                        expiryMode = ExpiryMode.NONE,
                        priority = 1,
                        proStatus = ProStatus.None,
                        profileUpdatedAt = null
                    )
                ),
                secondMember = null,
            ),
        )

        val viewModel = createViewModel(recipient)

        advanceUntilIdle()

        assertThat(
            viewModel.state.value
        ).isEqualTo(
            State(
                isGroup = true,
                isSelfAdmin = false,
                address = GROUP_ADDRESS,
                isNoteToSelf = false,
                expiryMode = ExpiryMode.NONE,
                isNewConfigEnabled = true,
                persistedMode = ExpiryMode.NONE,
                showDebugOptions = false
            )
        )

        assertThat(
            viewModel.uiState.value
        ).isEqualTo(
            UiState(
                OptionsCardData(
                    R.string.disappearingMessagesTimer,
                    typeOption(ExpiryMode.NONE, enabled = false, selected = true),
                    timeOption(ExpiryType.AFTER_SEND, 12.hours, enabled = false),
                    timeOption(ExpiryType.AFTER_SEND, 1.days, enabled = false),
                    timeOption(ExpiryType.AFTER_SEND, 7.days, enabled = false),
                    timeOption(ExpiryType.AFTER_SEND, 14.days, enabled = false)
                ),
                showGroupFooter = true,
                showSetButton = false,
                disableSetButton = true,
                subtitle = GetString(R.string.disappearingMessagesDisappearAfterSendDescription)
            )
        )
    }

    @Test
    fun `1-1 conversation, off, new config`() = runTest {
        val viewModel = createViewModel(Recipient(
            address = STANDARD_ADDRESS,
            data = RecipientData.Contact(
                name = "Contact",
                nickname = null,
                avatar = null,
                approved = true,
                approvedMe = true,
                blocked = false,
                expiryMode = ExpiryMode.NONE,
                priority = 1,
                proStatus = ProStatus.None,
                profileUpdatedAt = null
            )
        )
        )

        advanceUntilIdle()

        assertThat(
            viewModel.state.value
        ).isEqualTo(
            State(
                isGroup = false,
                isSelfAdmin = true,
                address = STANDARD_ADDRESS,
                isNoteToSelf = false,
                expiryMode = ExpiryMode.NONE,
                isNewConfigEnabled = true,
                persistedMode = ExpiryMode.NONE,
                showDebugOptions = false
            )
        )

        assertThat(
            viewModel.uiState.value
        ).isEqualTo(
            UiState(
                OptionsCardData(
                    R.string.disappearingMessagesDeleteType,
                    typeOption(ExpiryMode.NONE, selected = true),
                    typeOption(12.hours, ExpiryType.AFTER_READ),
                    typeOption(1.days, ExpiryType.AFTER_SEND)
                ),
                subtitle = GetString(R.string.disappearingMessagesDescription1),
                disableSetButton = true,
            )
        )
    }

    @Test
    fun `1-1 conversation, 12 hours after send, new config`() = runTest {
        val time = 12.hours

        val viewModel = createViewModel(Recipient(
            address = STANDARD_ADDRESS,
            data = RecipientData.Contact(
                name = "Contact",
                nickname = null,
                avatar = null,
                approved = true,
                approvedMe = true,
                blocked = false,
                expiryMode = ExpiryMode.AfterSend(time.inWholeSeconds),
                priority = 1,
                proStatus = ProStatus.None,
                profileUpdatedAt = null
            )
        )
        )

        advanceUntilIdle()

        assertThat(
            viewModel.state.value
        ).isEqualTo(
            State(
                isGroup = false,
                isSelfAdmin = true,
                address = STANDARD_ADDRESS,
                isNoteToSelf = false,
                expiryMode = ExpiryMode.AfterSend(12.hours.inWholeSeconds),
                isNewConfigEnabled = true,
                persistedMode = ExpiryMode.AfterSend(12.hours.inWholeSeconds),
                showDebugOptions = false
            )
        )

        assertThat(
            viewModel.uiState.value
        ).isEqualTo(
            UiState(
                OptionsCardData(
                    R.string.disappearingMessagesDeleteType,
                    typeOption(ExpiryMode.NONE),
                    typeOption(time, ExpiryType.AFTER_READ),
                    typeOption(time, ExpiryType.AFTER_SEND, selected = true)
                ),
                OptionsCardData(
                    R.string.disappearingMessagesTimer,
                    timeOption(ExpiryType.AFTER_SEND, 12.hours, selected = true),
                    timeOption(ExpiryType.AFTER_SEND, 1.days),
                    timeOption(ExpiryType.AFTER_SEND, 7.days),
                    timeOption(ExpiryType.AFTER_SEND, 14.days)
                ),
                disableSetButton = true,
                subtitle = GetString(R.string.disappearingMessagesDescription1)
            )
        )
    }

    @Test
    fun `1-1 conversation, 1 day after send, new config`() = runTest {
        val time = 1.days

        val viewModel = createViewModel(Recipient(
            address = STANDARD_ADDRESS,
            data = RecipientData.Contact(
                name = "Contact",
                nickname = null,
                avatar = null,
                approved = true,
                approvedMe = true,
                blocked = false,
                expiryMode = ExpiryMode.AfterSend(time.inWholeSeconds),
                priority = 1,
                proStatus = ProStatus.None,
                profileUpdatedAt = null
            )
        )
        )

        advanceUntilIdle()

        assertThat(
            viewModel.state.value
        ).isEqualTo(
            State(
                isGroup = false,
                isSelfAdmin = true,
                address = STANDARD_ADDRESS,
                isNoteToSelf = false,
                expiryMode = ExpiryMode.AfterSend(1.days.inWholeSeconds),
                isNewConfigEnabled = true,
                persistedMode = ExpiryMode.AfterSend(1.days.inWholeSeconds),
                showDebugOptions = false
            )
        )

        assertThat(
            viewModel.uiState.value
        ).isEqualTo(
            UiState(
                OptionsCardData(
                    R.string.disappearingMessagesDeleteType,
                    typeOption(ExpiryMode.NONE),
                    typeOption(12.hours, ExpiryType.AFTER_READ),
                    typeOption(time, ExpiryType.AFTER_SEND, selected = true)
                ),
                OptionsCardData(
                    R.string.disappearingMessagesTimer,
                    timeOption(ExpiryType.AFTER_SEND, 12.hours),
                    timeOption(ExpiryType.AFTER_SEND, 1.days, selected = true),
                    timeOption(ExpiryType.AFTER_SEND, 7.days),
                    timeOption(ExpiryType.AFTER_SEND, 14.days)
                ),
                disableSetButton = true,
                subtitle = GetString(R.string.disappearingMessagesDescription1)
            )
        )
    }

    @Test
    fun `1-1 conversation, 1 day after read, new config`() = runTest {
        val time = 1.days

        val viewModel = createViewModel(Recipient(
            address = STANDARD_ADDRESS,
            data = RecipientData.Contact(
                name = "Contact",
                nickname = null,
                avatar = null,
                approved = true,
                approvedMe = true,
                blocked = false,
                expiryMode = ExpiryMode.AfterRead(time.inWholeSeconds),
                priority = 1,
                proStatus = ProStatus.None,
                profileUpdatedAt = null
            )
        )
        )

        advanceUntilIdle()

        assertThat(
            viewModel.state.value
        ).isEqualTo(
            State(
                isGroup = false,
                isSelfAdmin = true,
                address = STANDARD_ADDRESS,
                isNoteToSelf = false,
                expiryMode = ExpiryMode.AfterRead(1.days.inWholeSeconds),
                isNewConfigEnabled = true,
                persistedMode = ExpiryMode.AfterRead(1.days.inWholeSeconds),
                showDebugOptions = false
            )
        )

        assertThat(
            viewModel.uiState.value
        ).isEqualTo(
            UiState(
                OptionsCardData(
                    R.string.disappearingMessagesDeleteType,
                    typeOption(ExpiryMode.NONE),
                    typeOption(1.days, ExpiryType.AFTER_READ, selected = true),
                    typeOption(time, ExpiryType.AFTER_SEND)
                ),
                OptionsCardData(
                    R.string.disappearingMessagesTimer,
                    timeOption(ExpiryType.AFTER_READ, 5.minutes),
                    timeOption(ExpiryType.AFTER_READ, 1.hours),
                    timeOption(ExpiryType.AFTER_READ, 12.hours),
                    timeOption(ExpiryType.AFTER_READ, 1.days, selected = true),
                    timeOption(ExpiryType.AFTER_READ, 7.days),
                    timeOption(ExpiryType.AFTER_READ, 14.days)
                ),
                disableSetButton = true,
                subtitle = GetString(R.string.disappearingMessagesDescription1)
            )
        )
    }

    @Test
    fun `1-1 conversation, init 12 hours after read, then select after send, new config`() = runTest {
        val time = 12.hours

        val viewModel = createViewModel(Recipient(
            address = STANDARD_ADDRESS,
            data = RecipientData.Contact(
                name = "Contact",
                nickname = null,
                avatar = null,
                approved = true,
                approvedMe = true,
                blocked = false,
                expiryMode = ExpiryMode.AfterRead(time.inWholeSeconds),
                priority = 1,
                proStatus = ProStatus.None,
                profileUpdatedAt = null
            )
        )
        )

        advanceUntilIdle()

        viewModel.onOptionSelected(afterSendMode(1.days))

        advanceUntilIdle()

        assertThat(
            viewModel.state.value
        ).isEqualTo(
            State(
                isGroup = false,
                isSelfAdmin = true,
                address = STANDARD_ADDRESS,
                isNoteToSelf = false,
                expiryMode = afterSendMode(1.days),
                isNewConfigEnabled = true,
                persistedMode = afterReadMode(12.hours),
                showDebugOptions = false
            )
        )

        assertThat(
            viewModel.uiState.value
        ).isEqualTo(
            UiState(
                OptionsCardData(
                    R.string.disappearingMessagesDeleteType,
                    typeOption(ExpiryMode.NONE),
                    typeOption(12.hours, ExpiryType.AFTER_READ),
                    typeOption(1.days, ExpiryType.AFTER_SEND, selected = true)
                ),
                OptionsCardData(
                    R.string.disappearingMessagesTimer,
                    timeOption(ExpiryType.AFTER_SEND, 12.hours),
                    timeOption(ExpiryType.AFTER_SEND, 1.days, selected = true),
                    timeOption(ExpiryType.AFTER_SEND, 7.days),
                    timeOption(ExpiryType.AFTER_SEND, 14.days)
                ),
                subtitle = GetString(R.string.disappearingMessagesDescription1)
            )
        )
    }

    private fun timeOption(
        type: ExpiryType,
        time: Duration,
        enabled: Boolean = true,
        selected: Boolean = false
    ) = ExpiryRadioOption(
        value = type.mode(time),
        title = GetString(time),
        qaTag = GetString(type.mode(time).duration),
        enabled = enabled,
        selected = selected
    )

    private fun afterSendMode(time: Duration) = ExpiryMode.AfterSend(time.inWholeSeconds)
    private fun afterReadMode(time: Duration) = ExpiryMode.AfterRead(time.inWholeSeconds)


    private fun createViewModel(recipient: Recipient) = DisappearingMessagesViewModel(
        address = recipient.address,
        context = application,
        disappearingMessages = disappearingMessages,
        navigator = navigator,
        isNewConfigEnabled = true,
        showDebugOptions = false,
        recipientRepository = mock {
            onBlocking { getRecipient(recipient.address) } doReturn recipient
        }
    )
}

fun typeOption(time: Duration, type: ExpiryType, selected: Boolean = false, enabled: Boolean = true) =
    typeOption(type.mode(time), selected, enabled)

fun typeOption(mode: ExpiryMode, selected: Boolean = false, enabled: Boolean = true) =
    ExpiryRadioOption(
        value = mode,
        title = GetString(mode.type.title),
        subtitle = mode.type.subtitle?.let(::GetString),
        qaTag = GetString(mode.type.contentDescription),
        selected = selected,
        enabled = enabled
    )
