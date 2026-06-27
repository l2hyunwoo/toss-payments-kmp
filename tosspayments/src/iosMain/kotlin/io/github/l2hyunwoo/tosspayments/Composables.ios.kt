package io.github.l2hyunwoo.tosspayments

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Surfaces the single iOS WKWebView (built/configured by the controller) into the Compose tree via
 * the new [UIKitView] interop (CMP 1.11, generic over UIView). The WebView renders both the
 * payment-method selector and the agreement.
 *
 * NonCooperative interaction mode is required, or Compose would eat the WebView's scroll/tap gestures.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun TossWidgetHost(widget: TossPaymentWidget, modifier: Modifier) {
    UIKitView(
        factory = { widget.controller.ensureWebView() },
        modifier = modifier.fillMaxWidth(),
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
}
