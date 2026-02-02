package org.thoughtcrime.securesms.home.search

import android.content.Context
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import network.loki.messenger.databinding.ViewGlobalSearchInputBinding
import org.thoughtcrime.securesms.util.SimpleTextWatcher

class GlobalSearchInputLayout @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs),
        View.OnFocusChangeListener,
        TextView.OnEditorActionListener {

    var binding: ViewGlobalSearchInputBinding = ViewGlobalSearchInputBinding.inflate(LayoutInflater.from(context), this, true)

    var listener: GlobalSearchInputLayoutListener? = null

    fun query() = channelFlow {
        val watcher = object : SimpleTextWatcher() {
            override fun onTextChanged(text: String?) {
                trySend(text.orEmpty())
            }
        }

        send(binding.searchInput.text.toString())
        binding.searchInput.addTextChangedListener(watcher)

        awaitClose {
            binding.searchInput.removeTextChangedListener(watcher)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binding.searchInput.onFocusChangeListener = this
        binding.searchInput.setOnEditorActionListener(this)
        binding.searchInput.filters = arrayOf<InputFilter>(LengthFilter(100)) // 100 char search limit
        binding.searchCancel.setOnClickListener {
            clearSearch()
            listener?.onCancelClicked()
        }
        binding.searchClear.setOnClickListener { clearSearch() }
    }

    override fun onFocusChange(v: View?, hasFocus: Boolean) {
        if (v === binding.searchInput) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).apply {
                if (hasFocus) showSoftInput(v, 0)
                else hideSoftInputFromWindow(windowToken, 0)
            }
        }
    }

    override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
        if (v !== binding.searchInput) return false

        return when (actionId) {
            EditorInfo.IME_ACTION_SEARCH -> {
                binding.searchInput.clearFocus()
                true
            }
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_PREVIOUS -> true
            else -> false
        }
    }

    fun clearSearch() {
        binding.searchInput.text = null
    }

    fun handleBackPressed(): Boolean {
        if (binding.searchInput.length() > 0) {
            clearSearch()
            return true
        }

        return false
    }

    interface GlobalSearchInputLayoutListener {
        fun onCancelClicked()
    }

}