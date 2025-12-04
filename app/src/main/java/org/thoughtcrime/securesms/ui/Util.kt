package org.thoughtcrime.securesms.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.squareup.phrase.Phrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
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

// Method to actually open a given URL via an Intent that will use the default browser
/**
 * Returns false if the phone was unable to open the link
 * Returns true otherwise
 */
fun Context.openUrl(url: String): Boolean {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return true
    } catch (e: Exception) {
        Toast.makeText(this, R.string.browserNotFound, Toast.LENGTH_LONG).show()
        Log.w("Dialog", "No browser found to open link", e)
    }

    return false
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

fun Context.isWhitelistedFromDoze(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

fun Activity.requestDozeWhitelist() {
    if (isWhitelistedFromDoze()) return

    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }
    try {
        startActivity(intent) // shows the system dialog for this specific app
    } catch (_: ActivityNotFoundException) {
        // Fallback to the general settings list
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
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