package org.thoughtcrime.securesms.conversation.v3

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v3.compose.message.ClusterPosition
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.DateUtils

class ConversationUIRulesTest {

    private val dateUtils: DateUtils = mockk()

    @Test
    fun `date break is shown above first message of newer chunk`() {
        val olderChunkNewestMessage = record(timestamp = 10_000L)
        val newerChunkOldestMessage = record(timestamp = 10_000L + 6 * 60 * 1000L)
        val newerChunkNewestMessage = record(timestamp = newerChunkOldestMessage.timestamp + 60_000L)

        every { dateUtils.isSameDay(any(), any()) } returns true

        assertThat(
            ConversationUIRules.shouldShowDateBreakAbove(
                current = newerChunkNewestMessage,
                older = newerChunkOldestMessage,
                dateUtils = dateUtils,
            )
        ).isFalse()

        assertThat(
            ConversationUIRules.shouldShowDateBreakAbove(
                current = newerChunkOldestMessage,
                older = olderChunkNewestMessage,
                dateUtils = dateUtils,
            )
        ).isTrue()
    }

    @Test
    fun `cluster position uses visual top and bottom in reversed list`() {
        val oldestInCluster = record(timestamp = 10_000L, isOutgoing = true)
        val newestInCluster = record(timestamp = 11_000L, isOutgoing = true)

        every { dateUtils.isSameHour(any(), any()) } returns true
        every { dateUtils.isSameDay(any(), any()) } returns true

        assertThat(
            ConversationUIRules.clusterPosition(
                current = oldestInCluster,
                newer = newestInCluster,
                older = null,
                isGroupThread = false,
                dateUtils = dateUtils,
            )
        ).isEqualTo(ClusterPosition.TOP)

        assertThat(
            ConversationUIRules.clusterPosition(
                current = newestInCluster,
                newer = null,
                older = oldestInCluster,
                isGroupThread = false,
                dateUtils = dateUtils,
            )
        ).isEqualTo(ClusterPosition.BOTTOM)
    }

    @Test
    fun `group clustering splits on the individual sender`() {
        val senderA = mockk<Address>(relaxed = true)
        val senderB = mockk<Address>(relaxed = true)
        val newerFromSenderA = record(timestamp = 12_000L, individualAddress = senderA)
        val currentFromSenderA = record(timestamp = 11_000L, individualAddress = senderA)
        val olderFromSenderB = record(timestamp = 10_000L, individualAddress = senderB)

        every { dateUtils.isSameHour(any(), any()) } returns true
        every { dateUtils.isSameDay(any(), any()) } returns true

        assertThat(
            ConversationUIRules.clusterPosition(
                current = currentFromSenderA,
                newer = newerFromSenderA,
                older = olderFromSenderB,
                isGroupThread = true,
                dateUtils = dateUtils,
            )
        ).isEqualTo(ClusterPosition.TOP)
    }

    @Test
    fun `clustering splits when messages are more than five minutes apart`() {
        val olderInChunk = record(timestamp = 10_000L, isOutgoing = true)
        val currentAtChunkEnd = record(timestamp = 11_000L, isOutgoing = true)
        val newerAfterGap = record(timestamp = 11_000L + 6 * 60 * 1000L, isOutgoing = true)

        every { dateUtils.isSameHour(any(), any()) } returns true
        every { dateUtils.isSameDay(any(), any()) } returns true

        assertThat(
            ConversationUIRules.clusterPosition(
                current = currentAtChunkEnd,
                newer = newerAfterGap,
                older = olderInChunk,
                isGroupThread = false,
                dateUtils = dateUtils,
            )
        ).isEqualTo(ClusterPosition.BOTTOM)
    }

    @Test
    fun `author name is shown above first message in incoming group chunk`() {
        val senderA = mockk<Address>(relaxed = true)
        val senderB = mockk<Address>(relaxed = true)
        val current = record(timestamp = 11_000L, isOutgoing = false, individualAddress = senderA)
        val olderDifferentSender = record(timestamp = 10_000L, isOutgoing = false, individualAddress = senderB)

        assertThat(
            ConversationUIRules.shouldShowAuthorNameAbove(
                current = current,
                older = olderDifferentSender,
                isGroupThread = true,
                showDateBreakAbove = false,
            )
        ).isTrue()
    }

    private fun record(
        timestamp: Long,
        isOutgoing: Boolean = false,
        isControl: Boolean = false,
        individualAddress: Address = mockk(relaxed = true),
    ): MessageRecord {
        val individualRecipient = mockk<Recipient>()
        every { individualRecipient.address } returns individualAddress

        return mockk {
            every { this@mockk.timestamp } returns timestamp
            every { this@mockk.isOutgoing } returns isOutgoing
            every { this@mockk.isControlMessage } returns isControl
            every { this@mockk.individualRecipient } returns individualRecipient
        }
    }
}
