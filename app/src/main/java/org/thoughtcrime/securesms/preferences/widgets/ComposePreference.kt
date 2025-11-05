package org.thoughtcrime.securesms.preferences.widgets

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.doOnAttach
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.setThemedContent

/**
 * A Preference row that hosts Compose content
 */
class ComposePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var content: (@Composable () -> Unit)? = null

    init {
        widgetLayoutResource = R.layout.compose_preference
        isSelectable = false
    }

    fun setContent(block: @Composable () -> Unit) {
        content = block
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val container = holder.findViewById(R.id.compose) as ComposeView

        container.setThemedContent {
            content?.invoke()
        }
    }
}