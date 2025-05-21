package org.thoughtcrime.securesms.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.squareup.phrase.Phrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.ui.theme.SessionMaterialTheme

fun Activity.setComposeContent(content: @Composable () -> Unit) {
    ComposeView(this)
        .apply { setThemedContent(content) }
        .let(::setContentView)
}

fun Fragment.createThemedComposeView(content: @Composable () -> Unit): ComposeView = requireContext().createThemedComposeView(content)
fun Context.createThemedComposeView(content: @Composable () -> Unit): ComposeView = ComposeView(this).apply {
    setThemedContent(content)
}

// Extension method to use the Phrase library to substitute strings & return a CharSequence.
// The pair is the key name, such as APP_NAME_KEY and the value is the localised string, such as context.getString(R.string.app_name).
// Note: We cannot have Pair<String, Int> versions of this or the `getSubbedString` method because the JVM sees the signatures as identical.
fun Context.getSubbedCharSequence(stringId: Int, vararg substitutionPairs: Pair<String, String>): CharSequence {
    val phrase = Phrase.from(this, stringId)
    for ((key, value) in substitutionPairs) { phrase.put(key, value) }
    return phrase.format()
}

// Extension method to use the Phrase library to substitute strings & return the substituted String.
// The pair is the key name, such as APP_NAME_KEY and the value is the localised string, such as context.getString(R.string.app_name).
fun Context.getSubbedString(stringId: Int, vararg substitutionPairs: Pair<String, String>): String {
    return getSubbedCharSequence(stringId, *substitutionPairs).toString()
}

fun ComposeView.setThemedContent(content: @Composable () -> Unit) = setContent {
    SessionMaterialTheme {
        content()
    }
}

fun Context.findActivity(): Activity {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    throw IllegalStateException("Permissions should be called in the context of an Activity")
}

inline fun <T : View> T.afterMeasured(crossinline block: T.() -> Unit) {
    viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            if (measuredWidth > 0 && measuredHeight > 0) {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                block()
            }
        }
    })
}

/**
 * This is used to set the test tag that the QA team can use to retrieve an element in appium
 * In order to do so we need to set the testTagsAsResourceId to true, which ideally should be done only once
 * in the root composable, but our app is currently made up of  multiple isolated composables
 * set up in the old activity/fragment view system
 * As such we need to repeat it for every component that wants to use testTag, until such
 * a time as we have one root composable
 */
@Composable
fun Modifier.qaTag(tag: String?): Modifier {
    if (tag == null) return this
    return this.semantics { testTagsAsResourceId = true }.testTag(tag)
}

@Composable
fun Modifier.qaTag(@StringRes tagResId: Int?): Modifier {
    if (tagResId == null) return this
    return this.semantics { testTagsAsResourceId = true }.testTag(stringResource(tagResId))
}

/**
 * helper function to observe flows as events properly
 * Including not losing events when the lifecycle gets destroyed by using Dispatchers.Main.immediate
 */
@Composable
fun <T> ObserveAsEvents(
    flow: Flow<T>,
    key1: Any? = null,
    key2: Any? = null,
    onEvent: (T) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(key1 = lifecycleOwner.lifecycle, key1, key2) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                flow.collect(onEvent)
            }
        }
    }
}

@Composable
fun AnimateFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    fadeInAnimationSpec: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow),
    fadeOutAnimationSpec: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow),
    content: @Composable() AnimatedVisibilityScope.() -> Unit
){
    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter = fadeIn(animationSpec = fadeInAnimationSpec),
        exit = fadeOut(animationSpec = fadeOutAnimationSpec)
    ) {
        content()
    }
}