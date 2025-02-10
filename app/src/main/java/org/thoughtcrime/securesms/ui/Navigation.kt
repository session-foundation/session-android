package org.thoughtcrime.securesms.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

inline fun <reified T : Any> NavGraphBuilder.horizontalSlideComposable(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
){
    composable<T>(
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left)
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left)
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right)
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right)
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