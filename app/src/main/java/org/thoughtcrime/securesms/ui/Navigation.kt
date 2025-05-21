package org.thoughtcrime.securesms.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val ANIM_TIME = 300

inline fun <reified T : Any> NavGraphBuilder.horizontalSlideComposable(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
){
    composable<T>(
        enterTransition = {
            fadeIn(
                animationSpec = tween(
                    ANIM_TIME, easing = LinearEasing
                )
            ) + slideIntoContainer(
                animationSpec = tween(ANIM_TIME, easing = EaseIn),
                towards = AnimatedContentTransitionScope.SlideDirection.Start
            )
        },
        exitTransition = {
            fadeOut(
                animationSpec = tween(
                    ANIM_TIME, easing = LinearEasing
                )
            ) + slideOutOfContainer(
                animationSpec = tween(ANIM_TIME, easing = EaseOut),
                towards = AnimatedContentTransitionScope.SlideDirection.Start
            )
        },
        popEnterTransition = {
            fadeIn(
                animationSpec = tween(
                    ANIM_TIME, easing = LinearEasing
                )
            ) + slideIntoContainer(
                animationSpec = tween(ANIM_TIME, easing = EaseIn),
                towards = AnimatedContentTransitionScope.SlideDirection.End
            )
        },
        popExitTransition = {
            fadeOut(
                animationSpec = tween(
                    ANIM_TIME, easing = LinearEasing
                )
            ) + slideOutOfContainer(
                animationSpec = tween(ANIM_TIME, easing = EaseOut),
                towards = AnimatedContentTransitionScope.SlideDirection.End
            )
        },
        content = content
    )
}

inline fun <reified T : Any>  NavGraphBuilder.verticalSlideComposable(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
){
    composable<T>(
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up)
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down)
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up)
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down)
        },
        content = content
    )
}