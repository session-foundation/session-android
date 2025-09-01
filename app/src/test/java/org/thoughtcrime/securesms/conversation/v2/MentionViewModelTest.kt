package org.thoughtcrime.securesms.conversation.v2

import android.text.Selection
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.recipients.ProStatus
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.truncateIdForDisplay
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.BaseViewModelTest
import org.thoughtcrime.securesms.MainCoroutineRule
import org.thoughtcrime.securesms.conversation.v2.mention.MentionViewModel
import org.thoughtcrime.securesms.util.AvatarUIData

@RunWith(RobolectricTestRunner::class)
class MentionViewModelTest : BaseViewModelTest() {
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private lateinit var mentionViewModel: MentionViewModel

    private val threadID = 123L

    private data class MemberInfo(
        val name: String,
        val pubKey: String,
        val role: GroupMemberRole,
        val isMe: Boolean
    )

    private val myId = AccountId.fromStringOrNull(
        "151234567890123456789012345678901234567890123456789012345678901234"
    )!!

    private val threadMembers = listOf(
        MemberInfo("You", myId.hexString, GroupMemberRole.STANDARD, isMe = true),
        MemberInfo("Alice", "151234567890123456789012345678901234567890123456789012345678901235", GroupMemberRole.ADMIN, isMe = false),
        MemberInfo("Bob", "151234567890123456789012345678901234567890123456789012345678901236", GroupMemberRole.STANDARD, isMe = false),
        MemberInfo("Charlie", "151234567890123456789012345678901234567890123456789012345678901237", GroupMemberRole.MODERATOR, isMe = false),
        MemberInfo("David", "151234567890123456789012345678901234567890123456789012345678901238", GroupMemberRole.HIDDEN_ADMIN, isMe = false),
        MemberInfo("Eve", "151234567890123456789012345678901234567890123456789012345678901239", GroupMemberRole.HIDDEN_MODERATOR, isMe = false),
        MemberInfo("李云海", "151234567890123456789012345678901234567890123456789012345678901240", GroupMemberRole.ZOOMBIE, isMe = false),
    )

    private val openGroup = OpenGroup(
        server = "http://url",
        room = "room",
        name = "Open Group",
        publicKey = "",
        imageId = null,
        infoUpdates = 0,
        canWrite = true,
        description = ""
    )

    private val communityRecipient = Recipient(
        address = Address.Community(openGroup),
        data = RecipientData.Community(openGroup = openGroup, priority = PRIORITY_VISIBLE, roles = threadMembers.associate { AccountId(it.pubKey) to it.role })
    )

    @Before
    fun setUp() {
        @Suppress("UNCHECKED_CAST")
        mentionViewModel = MentionViewModel(
            threadDatabase = mock {
                on { getRecipientForThreadId(threadID) } doReturn communityRecipient.address
                on { getThreadIdIfExistsFor(communityRecipient.address) } doReturn threadID
            },
            groupDatabase = mock {
            },
            storage = mock {
                on { getOpenGroup(threadID) } doReturn openGroup
                on { getUserBlindedAccountId(any()) } doReturn myId
                on { getUserPublicKey() } doReturn myId.hexString
            },
            application = InstrumentationRegistry.getInstrumentation().context as android.app.Application,
            mmsSmsDatabase = mock {
                on { getRecentChatMemberAddresses(eq(threadID), any())} doAnswer {
                    val limit = it.arguments[1] as Int
                    threadMembers.take(limit).map { m -> m.pubKey }
                }
            },
            address = communityRecipient.address,
            recipientRepository = mock {
                on { getRecipientSync(communityRecipient.address) } doReturn communityRecipient
                on { getRecipientSync(any()) } doAnswer {
                    val address = it.arguments[0] as Address
                    if (address == communityRecipient.address) {
                        communityRecipient
                    } else {
                        threadMembers.firstOrNull { m -> m.pubKey == address.address }
                            ?.let { m ->
                                Recipient(
                                    address = m.pubKey.toAddress(),
                                    data = RecipientData.Generic(displayName = m.name)
                                )
                            }
                    }
                }
                on { observeRecipient(communityRecipient.address) } doAnswer {
                    flowOf(communityRecipient)
                }
                on { getSelf() } doReturn Recipient(
                    address = myId.toAddress(),
                    data = RecipientData.Self(
                        name = "Myself",
                        avatar = null,
                        expiryMode = ExpiryMode.NONE,
                        priority = 0,
                        proStatus = ProStatus.None,
                        profileUpdatedAt = null,
                    )
                )
            },
            avatarUtils = mock {
                on { getUIDataFromRecipient(any<Recipient>()) } doReturn AvatarUIData(emptyList())
                onBlocking { getUIDataFromAccountId(any()) } doReturn AvatarUIData(emptyList())
            }
        )
    }

    @Test
    fun `should show candidates after 'at' symbol`() = runTest {
        mentionViewModel.autoCompleteState.test {
            assertThat(awaitItem())
                .isEqualTo(MentionViewModel.AutoCompleteState.Idle)

            val editable = mentionViewModel.editableFactory.newEditable("")
            editable.append("Hello @")
            expectNoEvents() // Nothing should happen before cursor is put after @
            Selection.setSelection(editable, editable.length)

            assertThat(awaitItem())
                .isEqualTo(MentionViewModel.AutoCompleteState.Loading)

            // Should show all the candidates
            awaitItem().let { result ->
                assertThat(result)
                    .isInstanceOf(MentionViewModel.AutoCompleteState.Result::class.java)
                result as MentionViewModel.AutoCompleteState.Result

                assertThat(result.members).isEqualTo(threadMembers.map { m ->
                    val name = if (m.isMe) "You" else "${m.name} (${truncateIdForDisplay(m.pubKey)})"

                    MentionViewModel.Candidate(
                        MentionViewModel.Member(
                            publicKey = m.pubKey,
                            name = name,
                            showAdminCrown = m.role.shouldShowAdminCrown,
                            isMe = m.isMe,
                            avatarData = AvatarUIData(emptyList())
                        ),
                        name,
                        0
                    )
                })
            }


            // Continue typing to filter candidates
            editable.append("li")
            Selection.setSelection(editable, editable.length)

            // Should show only Alice and Charlie
            awaitItem().let { result ->
                assertThat(result)
                    .isInstanceOf(MentionViewModel.AutoCompleteState.Result::class.java)
                result as MentionViewModel.AutoCompleteState.Result

                assertThat(result.members[0].member.name).isEqualTo("Alice (1512…1235)")
                assertThat(result.members[1].member.name).isEqualTo("Charlie (1512…1237)")
            }
        }
    }

    @Test
    fun `should have normalised message with candidates selected`() = runTest {
        mentionViewModel.autoCompleteState.test {
            assertThat(awaitItem())
                .isEqualTo(MentionViewModel.AutoCompleteState.Idle)

            val editable = mentionViewModel.editableFactory.newEditable("")
            editable.append("Hi @")
            Selection.setSelection(editable, editable.length)

            assertThat(awaitItem())
                .isEqualTo(MentionViewModel.AutoCompleteState.Loading)

            // Select a candidate now
            assertThat(awaitItem())
                .isInstanceOf(MentionViewModel.AutoCompleteState.Result::class.java)
            val key = threadMembers[0].pubKey // Select the first candidate (You)
            mentionViewModel.onCandidateSelected(key)

            // Should have normalised message with selected candidate
            assertThat(mentionViewModel.normalizeMessageBody())
                .isEqualTo("Hi @$key")

            // Should have correct normalised message even with the last space deleted
            editable.delete(editable.length - 1, editable.length)
            assertThat(mentionViewModel.normalizeMessageBody())
                .isEqualTo("Hi @$key")
        }
    }
}