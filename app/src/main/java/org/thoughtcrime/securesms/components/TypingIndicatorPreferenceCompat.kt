package org.thoughtcrime.securesms.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.flow.MutableStateFlow
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.thoughtcrime.securesms.conversation.v2.components.TypingIndicatorViewContainer
import org.thoughtcrime.securesms.ui.components.SessionSwitch
import org.thoughtcrime.securesms.ui.getSubbedCharSequence
import org.thoughtcrime.securesms.ui.setThemedContent

class TypingIndicatorPreferenceCompat : TwoStatePreference {
    private var listener: OnPreferenceClickListener? = null

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, androidx.preference.R.attr.switchPreferenceCompatStyle)
    constructor(context: Context) : this(context, null, androidx.preference.R.attr.switchPreferenceCompatStyle)

    private val checkState = MutableStateFlow(isChecked)
    private val enableState = MutableStateFlow(isEnabled)

    init {
        widgetLayoutResource = R.layout.typing_indicator_preference
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)

        checkState.value = checked
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)

        enableState.value = enabled
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val composeView = holder.findViewById(R.id.compose_preference) as ComposeView
        composeView.setThemedContent {
            SessionSwitch(
                checked = checkState.collectAsState().value,
                onCheckedChange = null,
                enabled = isEnabled
            )
        }

        val typingView = holder.findViewById(R.id.pref_typing_indicator_view) as TypingIndicatorViewContainer
        typingView.apply {
            startAnimation()

            // stop animation if the preference row is detached
            holder.itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    stopAnimation()
                    v.removeOnAttachStateChangeListener(this)
                }
            })
        }
    }

    override fun setOnPreferenceClickListener(listener: OnPreferenceClickListener?) {
        this.listener = listener
    }

    override fun onClick() {
        if (listener == null || !listener!!.onPreferenceClick(this)) {
            super.onClick()
        }
    }
}
