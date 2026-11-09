package dev.whistle.app.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** Test hook: forces the keyboard-open layout (Robolectric has no IME). Null = ask the window insets. */
val LocalImeVisibleOverride = compositionLocalOf<Boolean?> { null }

/** Whether the soft keyboard is up; screens compact their chrome while it is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun imeVisible(): Boolean = LocalImeVisibleOverride.current ?: WindowInsets.isImeVisible
