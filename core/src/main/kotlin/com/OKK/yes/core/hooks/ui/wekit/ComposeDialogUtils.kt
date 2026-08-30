package com.OKK.yes.core.hooks.ui.wekit

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toDrawable
import com.OKK.yes.core.hooks.ui.wekit.theme.WkInjectedTheme

class ShowComposeDialogScope(
    val context: Context,
    val dialog: Dialog,
    val window: Window,
    val onDismiss: () -> Unit
)

fun showComposeDialog(
    context: Context,
    directlyDismissable: Boolean = true,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    runCatching {
        val lifecycleOwner = XposedLifecycleOwner.create()
        val dialog = ComponentDialog(
            context,
            android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
        )

        dialog.apply {
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                requestFeature(Window.FEATURE_NO_TITLE)
            }

            setCancelable(directlyDismissable)

            val scope = ShowComposeDialogScope(context, this, window!!, ::dismiss)

            val composeView = ComposeView(context).apply {
                setWkLifecycleOwner(lifecycleOwner)
                setContent {
                    WkInjectedTheme {
                        Box(
                            modifier = Modifier.wrapContentSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            scope.content()
                        }
                    }
                }
            }
            setContentView(composeView)
            window?.decorView?.setWkLifecycleOwner(lifecycleOwner)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            show()
        }
    }.onFailure {
        android.util.Log.e("OKK-Dialog", "showComposeDialog failed", it)
    }
}
