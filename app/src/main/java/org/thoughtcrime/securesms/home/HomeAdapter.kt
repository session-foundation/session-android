package org.thoughtcrime.securesms.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_ID
import com.bumptech.glide.RequestManager
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewMessageRequestBannerBinding
import org.thoughtcrime.securesms.dependencies.ConfigFactory

class HomeAdapter(
    private val context: Context,
    private val configFactory: ConfigFactory,
    private val listener: ConversationClickListener,
    private val showMessageRequests: () -> Unit,
    private val hideMessageRequests: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val ITEM_TYPE_MESSAGE_REQUESTS = 0
        private const val ITEM_TYPE_CONVO = 1
    }

    var data: HomeViewModel.Data = HomeViewModel.Data(items = emptyList())
        set(newData) {
            if (field === newData) return

            val diff = HomeDiffUtil(field, newData, context, configFactory)
            val diffResult = DiffUtil.calculateDiff(diff)
            field = newData
            diffResult.dispatchUpdatesTo(this)
        }

    override fun getItemId(position: Int): Long  {
        return when (val item = data.items[position]) {
            is HomeViewModel.Item.MessageRequests -> NO_ID
            is HomeViewModel.Item.Thread          -> item.thread.threadId
        }
    }

    lateinit var glide: RequestManager

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            ITEM_TYPE_MESSAGE_REQUESTS -> {
                ViewMessageRequestBannerBinding.inflate(LayoutInflater.from(parent.context)).apply {
                    root.setOnClickListener { showMessageRequests() }
                    root.setOnLongClickListener { hideMessageRequests(); true }
                    root.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                }.let(::HeaderFooterViewHolder)
            }
            ITEM_TYPE_CONVO -> {
                val conversationView = LayoutInflater.from(parent.context).inflate(R.layout.view_conversation, parent, false) as ConversationView
                val viewHolder = ConversationViewHolder(conversationView)
                viewHolder.view.setOnClickListener { viewHolder.view.thread?.let { threadRecord ->
                    listener.onConversationClick(threadRecord) }
                }
                viewHolder.view.setOnLongClickListener {
                    viewHolder.view.thread?.let { listener.onLongConversationClick(it) }
                    true
                }
                viewHolder
            }
            else -> throw Exception("viewType $viewType isn't valid")
        }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderFooterViewHolder -> {
                val messageRequests = data.items[position] as HomeViewModel.Item.MessageRequests
                holder.binding.unreadCountTextView.text = messageRequests.count.toString()
            }
            is ConversationViewHolder -> {
                val item = data.items[position] as HomeViewModel.Item.Thread

                holder.view.bind(item.thread, item.isTyping)
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (data.items[position]) {
        is HomeViewModel.Item.MessageRequests -> ITEM_TYPE_MESSAGE_REQUESTS
        is HomeViewModel.Item.Thread -> ITEM_TYPE_CONVO
    }

    override fun getItemCount(): Int = data.items.size

    class ConversationViewHolder(val view: ConversationView) : RecyclerView.ViewHolder(view)

    class HeaderFooterViewHolder(val binding: ViewMessageRequestBannerBinding) : RecyclerView.ViewHolder(binding.root)
}
