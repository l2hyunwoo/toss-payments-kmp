package io.tosspayments.kmp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Surfaces the Android WebView region for [slot] into the Compose tree via [AndroidView].
 *
 * STUB: returns an empty WebView for now. Milestone 2 will route each [slot] to its inline
 * region (Toss uses two WebView-backed FrameLayouts — payment-method + agreement — under one
 * controller) and apply the JS-driven height.
 */
@Composable
internal actual fun TossWidgetSurface(
    widget: TossPaymentWidget,
    slot: TossWidgetSlot,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context -> android.webkit.WebView(context) },
    )
}
