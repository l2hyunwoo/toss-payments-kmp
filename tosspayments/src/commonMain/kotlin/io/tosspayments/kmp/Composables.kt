package io.tosspayments.kmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Creates (and remembers) a [TossPaymentWidget] bound to a freshly-mounted WebView host.
 * The returned handle drives [TossPaymentMethods] / [TossPaymentAgreement] and exposes
 * readiness / agreement / selection state plus [TossPaymentWidget.requestPayment].
 *
 * The widget is disposed automatically when it leaves composition.
 */
@Composable
fun rememberTossPaymentWidget(
    config: PaymentWidgetConfig,
    amount: PaymentAmount,
    renderOptions: RenderOptions = RenderOptions(),
): TossPaymentWidget {
    val widget = remember(config, renderOptions) {
        TossPaymentWidget(PlatformWebViewController(config), amount, renderOptions)
    }
    DisposableEffect(widget) {
        widget.start()
        onDispose { widget.dispose() }
    }
    return widget
}

/**
 * Inline payment-method selection widget. Its height is auto-driven from the JS SDK's
 * height events, so place it in a vertically-wrapping slot. Shares its WebView with
 * [TossPaymentAgreement] through the same [widget] handle.
 */
@Composable
fun TossPaymentMethods(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    TossWidgetSurface(widget, TossWidgetSlot.PAYMENT_METHODS, modifier)
}

/** Inline required-terms agreement widget. Drives [TossPaymentWidget.agreedRequiredTerms]. */
@Composable
fun TossPaymentAgreement(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    TossWidgetSurface(widget, TossWidgetSlot.AGREEMENT, modifier)
}

/** Which inline region of the host page a [TossWidgetSurface] projects. */
internal enum class TossWidgetSlot { PAYMENT_METHODS, AGREEMENT }

/**
 * The per-platform Compose host that embeds the native WebView region (Android → AndroidView,
 * iOS → UIKitView) for the given [slot]. Both slots are views into the same single WebView /
 * JS session owned by [widget]; the actual implementation surfaces the right sub-region.
 */
@Composable
internal expect fun TossWidgetSurface(
    widget: TossPaymentWidget,
    slot: TossWidgetSlot,
    modifier: Modifier,
)
