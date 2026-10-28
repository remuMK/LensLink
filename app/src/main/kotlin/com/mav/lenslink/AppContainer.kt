/*
 * AppContainer.kt — composables for reaching the app-wide dependency
 * container (LensLinkApplication, the supercharged Context) from the UI.
 */
package com.mav.lenslink

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Typed access to the app container. */
@Composable
fun appContainer(): LensLinkApplication =
    LocalContext.current.applicationContext as LensLinkApplication
