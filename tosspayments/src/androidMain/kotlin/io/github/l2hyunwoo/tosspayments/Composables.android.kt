package io.github.l2hyunwoo.tosspayments

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Surfaces the single Android WebView (built/configured by the controller) into the Compose tree.
 * The WebView renders both the payment-method selector and the agreement; its height is driven
 * by the JS SDK (the WebView wraps its content).
 */
@Composable
internal actual fun TossWidgetHost(widget: TossPaymentWidget, modifier: Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context -> widget.controller.attach(context) },
    )
}
