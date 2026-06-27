package io.github.l2hyunwoo.tosspayments

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import platform.WebKit.WKWebView

/**
 * Surfaces the iOS WKWebView region for [slot] into the Compose tree via [UIKitView]
 * (the official Compose Multiplatform UIKit-interop composable).
 *
 * STUB: returns an empty WKWebView for now. Milestone 2 will route each [slot] to its inline
 * region under one controller and apply the JS-driven height.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal actual fun TossWidgetSurface(
    widget: TossPaymentWidget,
    slot: TossWidgetSlot,
    modifier: Modifier,
) {
    UIKitView(
        modifier = modifier,
        factory = { WKWebView() },
    )
}
