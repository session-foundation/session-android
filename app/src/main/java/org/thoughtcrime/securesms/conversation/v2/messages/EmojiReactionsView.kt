package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.google.android.flexbox.JustifyContent
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewEmojiReactionsBinding
import org.session.libsession.utilities.ThemeUtil
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.components.emoji.EmojiImageView
import org.thoughtcrime.securesms.components.emoji.EmojiUtil
import org.thoughtcrime.securesms.conversation.v2.ViewUtil
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.util.NumberUtil.getFormattedNumber
import java.util.Date

class EmojiReactionsView : ConstraintLayout, OnTouchListener {
    companion object {
        private const val DEFAULT_THRESHOLD = 5
        private const val longPressDurationThreshold: Long = 250
        private const val maxDoubleTapInterval: Long = 200
    }

    private val binding: ViewEmojiReactionsBinding by lazy { ViewEmojiReactionsBinding.bind(this) }

    // Normally 6dp, but we have 1dp left+right margin on the pills themselves
    private val OUTER_MARGIN = ViewUtil.dpToPx(2)
    private var records: MutableList<ReactionRecord>? = null
    private var messageId: MessageId? = null
    private var delegate: VisibleMessageViewDelegate? = null
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var pressCallback: Runnable? = null
    private var longPressCallback: Runnable? = null
    private var onDownTimestamp: Long = 0
    private var extended = false

    private val overflowItemSize = ViewUtil.dpToPx(24)

    constructor(context: Context) : super(context) { init(null) }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { init(attrs) }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init(attrs) }

    private fun init(attrs: AttributeSet?) {
        records = ArrayList()

        if (attrs != null) {
            val typedArray = context.theme.obtainStyledAttributes(attrs, R.styleable.EmojiReactionsView, 0, 0)
            typedArray.recycle()
        }
    }

    fun clear() {
        records!!.clear()
        binding.layoutEmojiContainer.removeAllViews()
    }

    fun setReactions(messageId: MessageId, records: List<ReactionRecord>, outgoing: Boolean, delegate: VisibleMessageViewDelegate?) {
        this.delegate = delegate
        if (records == this.records) {
            return
        }

        binding.layoutEmojiContainer.justifyContent = if (outgoing) JustifyContent.FLEX_END else JustifyContent.FLEX_START
        this.records!!.clear()
        this.records!!.addAll(records)
        if (this.messageId != messageId) {
            extended = false
        }
        this.messageId = messageId
        displayReactions(messageId, if (extended) Int.MAX_VALUE else DEFAULT_THRESHOLD)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (v.tag == null) return false
        val reaction = v.tag as Reaction
        val action = event.action
        if (action == MotionEvent.ACTION_DOWN) onDown(reaction.messageId, reaction.emoji)
        else if (action == MotionEvent.ACTION_CANCEL) removeLongPressCallback()
        else if (action == MotionEvent.ACTION_UP) onUp(reaction)
        return true
    }

    private fun displayReactions(messageId: MessageId, threshold: Int) {
        val userPublicKey = (context.applicationContext as ApplicationContext).loginStateRepository
            .get()
            .getLocalNumber()

        val reactions = buildSortedReactionsList(messageId, records!!, userPublicKey, threshold)
        binding.layoutEmojiContainer.removeAllViews()
        val overflowContainer = LinearLayout(context)
        overflowContainer.orientation = LinearLayout.HORIZONTAL
        val pixelSize = ViewUtil.dpToPx(1)
        reactions.forEachIndexed { index, reaction ->
            if (binding.layoutEmojiContainer.childCount + 1 >= DEFAULT_THRESHOLD && threshold != Int.MAX_VALUE && reactions.size > threshold) {
                if (overflowContainer.parent == null) {
                    binding.layoutEmojiContainer.addView(overflowContainer)
                    val overflowParams = overflowContainer.layoutParams as MarginLayoutParams
                    overflowParams.height = MarginLayoutParams.WRAP_CONTENT
                    overflowParams.setMargins(pixelSize, pixelSize, pixelSize, pixelSize)
                    overflowContainer.layoutParams = overflowParams
                }
                val pill = buildPill(context, this, reaction, true)
                pill.setOnClickListener { v: View? ->
                    extended = true
                    displayReactions(messageId, Int.MAX_VALUE)
                }
                pill.findViewById<View>(R.id.reactions_pill_count).visibility = GONE
                pill.findViewById<View>(R.id.reactions_pill_spacer).visibility = GONE
                pill.z = reaction.count - index.toFloat() // make sure the overflow is stacked properly
                overflowContainer.addView(pill)
            } else {
                val pill = buildPill(context, this, reaction, false)
                pill.tag = reaction
                pill.setOnTouchListener(this)
                val params = pill.layoutParams as MarginLayoutParams
                params.setMargins(pixelSize, pixelSize, pixelSize, pixelSize)
                pill.layoutParams = params
                binding.layoutEmojiContainer.addView(pill)
            }
        }
        val overflowChildren = overflowContainer.childCount
        val negativeMargin = ViewUtil.dpToPx(-8)
        for (i in 0 until overflowChildren) {
            val child = overflowContainer.getChildAt(i)
            val childParams = child.layoutParams as MarginLayoutParams
            if (i == 0 && overflowChildren > 1 || i + 1 < overflowChildren) {
                // if first and there is more than one child, or we are not the last child then set negative right margin
                childParams.setMargins(0, 0, negativeMargin, 0)
                child.layoutParams = childParams
            }
        }
        if (threshold == Int.MAX_VALUE) {
            binding.groupShowLess.visibility = VISIBLE
            for (id in binding.groupShowLess.referencedIds) {
                findViewById<View>(id).setOnClickListener { view: View? ->
                    extended = false
                    displayReactions(messageId, DEFAULT_THRESHOLD)
                }
            }
        } else {
            binding.groupShowLess.visibility = GONE
        }
    }

    private fun buildSortedReactionsList(
        messageId: MessageId,
        records: List<ReactionRecord>,
        userPublicKey: String?,
        threshold: Int
    ): List<Reaction> {
        val reactions = arrayListOf<Reaction>()

        // First go through reaction records and create a sorted list of reaction data based on emoji
        for (record in records) {
            val baseEmoji = EmojiUtil.getCanonicalRepresentation(record.emoji)
            val index = reactions.binarySearchBy(baseEmoji) { it.emoji }
            if (index >= 0) {
                reactions[index].update(
                    count = record.count,
                    lastSeen = record.dateReceived,
                    userWasSender = record.author == userPublicKey
                )
            } else {
                val reaction = Reaction(
                    messageId = messageId,
                    emoji = record.emoji,
                    count = record.count,
                    sortIndex = record.sortId,
                    lastSeen = record.dateReceived,
                    userWasSender = record.author == userPublicKey
                )
                reactions.add(-index - 1, reaction)
            }
        }

        // Once we have a list of unique reactions, sort them by our desired criteria
        reactions.sort()

        return if (threshold != Int.MAX_VALUE) {
            reactions.take(threshold + 2)
        } else {
            reactions
        }
    }

    private fun buildPill(context: Context, parent: ViewGroup, reaction: Reaction, isCompact: Boolean): View {
        val root = LayoutInflater.from(context).inflate(R.layout.reactions_pill, parent, false)
        val emojiView = root.findViewById<EmojiImageView>(R.id.reactions_pill_emoji)
        val countView = root.findViewById<TextView>(R.id.reactions_pill_count)
        val spacer = root.findViewById<View>(R.id.reactions_pill_spacer)
        if (isCompact) {
            root.setPadding(0)
            val layoutParams = root.layoutParams
            layoutParams.height = overflowItemSize
            layoutParams.width = overflowItemSize
            root.layoutParams = layoutParams
        }
        emojiView.setImageEmoji(reaction.emoji)
        if (reaction.count >= 1) {
            countView.text = getFormattedNumber(reaction.count)
        } else {
            countView.visibility = GONE
            spacer.visibility = GONE
        }
        if (reaction.userWasSender && !isCompact) {
            root.background = ContextCompat.getDrawable(context, R.drawable.reaction_pill_background_selected)
            countView.setTextColor(ThemeUtil.getThemedColor(context, R.attr.reactionsPillSelectedTextColor))
        } else {
            root.background = if(isCompact) ContextCompat.getDrawable(context, R.drawable.reaction_pill_background_bordered)
                else ContextCompat.getDrawable(context, R.drawable.reaction_pill_background)
        }
        return root
    }

    private fun onReactionClicked(reaction: Reaction) {
        val messageId = this.messageId ?: return
        delegate!!.onReactionClicked(reaction.emoji!!, messageId, reaction.userWasSender)
    }

    private fun onDown(messageId: MessageId, emoji: String?) {
        removeLongPressCallback()
        val newLongPressCallback = Runnable {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (delegate != null) {
                delegate!!.onReactionLongClicked(messageId, emoji)
            }
        }
        longPressCallback = newLongPressCallback
        gestureHandler.postDelayed(newLongPressCallback, longPressDurationThreshold)
        onDownTimestamp = Date().time
    }

    private fun removeLongPressCallback() {
        if (longPressCallback != null) {
            gestureHandler.removeCallbacks(longPressCallback!!)
        }
    }

    private fun onUp(reaction: Reaction) {
        if (Date().time - onDownTimestamp < longPressDurationThreshold) {
            removeLongPressCallback()
            if (pressCallback != null) {
                gestureHandler.removeCallbacks(pressCallback!!)
                pressCallback = null
            } else {
                val newPressCallback = Runnable {
                    onReactionClicked(reaction)
                    pressCallback = null
                }
                pressCallback = newPressCallback
                gestureHandler.postDelayed(newPressCallback, maxDoubleTapInterval)
            }
        }
    }

    class Reaction(
        val messageId: MessageId,
        val emoji: String,
        var count: Long,
        val sortIndex: Long,
        var lastSeen: Long,
        var userWasSender: Boolean,
    ) : Comparable<Reaction> {
        fun update(count: Long, lastSeen: Long, userWasSender: Boolean) {
            this.count = this.count.coerceAtLeast(count)
            this.lastSeen = this.lastSeen.coerceAtLeast(lastSeen)
            this.userWasSender = this.userWasSender || userWasSender
        }

        override fun compareTo(other: Reaction): Int {
            var rc = this.sortIndex.compareTo(other.sortIndex)
            if (rc == 0) {
                rc = other.count.compareTo(count)
            }

            if (rc == 0) {
                rc = other.lastSeen.compareTo(lastSeen)
            }

            if (rc == 0) {
                rc = this.emoji.compareTo(other.emoji)
            }

            return rc
        }
    }
}