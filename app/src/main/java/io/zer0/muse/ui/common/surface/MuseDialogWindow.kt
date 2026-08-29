@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.common.surface

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Configures Muse dialog windows without the platform dim layer. */
internal fun clearMuseWindowDim(window: Window?) {
    window ?: return
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.setDimAmount(0f)
}

@Composable
internal fun MuseDialogWindowEffect(
    forceFullScreen: Boolean = false,
    bottomAligned: Boolean = false,
) {
    val localView = LocalView.current
    val dialogWindow = (localView.parent as? DialogWindowProvider)?.window

    DisposableEffect(localView, dialogWindow, forceFullScreen) {
        if (dialogWindow == null) {
            onDispose { }
        } else {
            val originalFlags = dialogWindow.attributes.flags
            val originalDimAmount = dialogWindow.attributes.dimAmount
            val originalWidth = dialogWindow.attributes.width
            val originalHeight = dialogWindow.attributes.height
            val originalCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            val originalGravity = dialogWindow.attributes.gravity
            val originalSoftInputMode = dialogWindow.attributes.softInputMode
            val originalBackground: Drawable? = dialogWindow.decorView.background

            clearMuseWindowDim(dialogWindow)
            if (bottomAligned) {
                // Bottom menus use the platform decor boundary as the single source of
                // truth. In particular, do not read Compose WindowInsets and move the
                // panel again: on Android 15/16 Dialog insets can already be consumed.
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, true)
                dialogWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    gravity = Gravity.BOTTOM
                }
            }
            if (forceFullScreen) {
                dialogWindow.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            onDispose {
                if (originalFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                } else {
                    dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
                dialogWindow.setDimAmount(originalDimAmount)
                if (forceFullScreen) {
                    dialogWindow.setLayout(originalWidth, originalHeight)
                }
                if (bottomAligned) {
                    dialogWindow.setBackgroundDrawable(originalBackground)
                    dialogWindow.setSoftInputMode(originalSoftInputMode)
                    dialogWindow.attributes = dialogWindow.attributes.apply {
                        gravity = originalGravity
                    }
                }
                if (originalCutoutMode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dialogWindow.attributes = dialogWindow.attributes.apply {
                        layoutInDisplayCutoutMode = originalCutoutMode
                    }
                }
            }
        }
    }
}
