package org.thoughtcrime.securesms.reactions

import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import io.reactivex.schedulers.Schedulers
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.components.emoji.EmojiUtil
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReactionsRepository @Inject constructor(
    private val recipientRepository: RecipientRepository,
) {

    fun getReactions(messageId: MessageId): Observable<List<ReactionDetails>> {
        return Observable.create { emitter: ObservableEmitter<List<ReactionDetails>> ->
            emitter.onNext(fetchReactionDetails(messageId))
        }.subscribeOn(Schedulers.io())
    }

    private fun fetchReactionDetails(messageId: MessageId): List<ReactionDetails> {
        val context = MessagingModuleConfiguration.shared.context
        val reactions: List<ReactionRecord> = DatabaseComponent.get(context).reactionDatabase().getReactions(messageId)

        return reactions.map { reaction ->
            val authorAddress = Address.fromSerialized(reaction.author)
            ReactionDetails(
                sender = recipientRepository.getRecipientSync(authorAddress) ?: Recipient.empty(authorAddress),
                baseEmoji = EmojiUtil.getCanonicalRepresentation(reaction.emoji),
                displayEmoji = reaction.emoji,
                timestamp = reaction.dateReceived,
                serverId = reaction.serverId,
                localId = reaction.messageId,
                count = reaction.count.toInt()
            )
        }
    }
}
