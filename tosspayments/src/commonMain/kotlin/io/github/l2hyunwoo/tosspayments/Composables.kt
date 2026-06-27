package io.github.l2hyunwoo.tosspayments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Creates (and remembers) a [TossPaymentWidget] bound to a freshly-mounted WebView host.
 * The returned handle drives [TossPaymentWidgetSurface] and exposes readiness / agreement /
 * selection state plus [TossPaymentWidget.requestPayment].
 *
 * [amount] is declarative: the WebView is created once (keyed on [config]/[renderOptions]); when
 * [amount] changes the widget re-applies it via setAmount once ready, so the rendered/charged
 * amount always tracks the composition input without rebuilding the WebView.
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
        TossPaymentWidget(PlatformWebViewController(config), config, amount, renderOptions)
    }
    DisposableEffect(widget) {
        widget.start()
        onDispose { widget.dispose() }
    }
    // Re-apply a changed amount once the widget is ready (the initial amount is rendered by start()).
    val status by widget.status.collectAsState()
    LaunchedEffect(widget, amount, status) {
        if (status == WidgetStatus.READY) widget.updateAmount(amount)
    }
    return widget
}

/**
 * The single inline surface for the payment widget. It hosts ONE WebView / ONE JS session that
 * renders both the payment-method selector and the required-terms agreement, stacked.
 *
 * Why one surface (not separate method/agreement composables): the v2 JS SDK requires one
 * `widgets` instance to own both renders plus `setAmount` and `requestPayment`. That single JS
 * session lives in one WebView, and Compose interop attaches a native view to exactly one slot —
 * so the two regions cannot be split across two composables without splitting the JS session.
 *
 * Height is auto-driven from the JS SDK, so place this in a vertically-wrapping slot.
 */
@Composable
fun TossPaymentWidgetSurface(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    TossWidgetHost(widget, modifier)
}

/**
 * The per-platform Compose host that embeds the single WebView (Android → AndroidView,
 * iOS → UIKitView) and renders both widget regions inside it.
 */
@Composable
internal expect fun TossWidgetHost(widget: TossPaymentWidget, modifier: Modifier)

/**
 * Deprecated: the v2 widget renders methods + agreement in one WebView session, so they cannot
 * be two separate surfaces. The first of these that enters composition renders the combined
 * [TossPaymentWidgetSurface]; the second is a no-op so legacy two-call code still compiles.
 */
@Deprecated(
    "The v2 widget renders methods and agreement in one WebView session. Call TossPaymentWidgetSurface(widget) once instead.",
    ReplaceWith("TossPaymentWidgetSurface(widget, modifier)"),
)
@Composable
fun TossPaymentMethods(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    if (widget.claimPrimarySurface()) TossPaymentWidgetSurface(widget, modifier)
}

/** Deprecated: agreement is part of [TossPaymentWidgetSurface]. See [TossPaymentMethods]. */
@Deprecated(
    "The v2 widget renders methods and agreement in one WebView session. Call TossPaymentWidgetSurface(widget) once instead.",
    ReplaceWith("TossPaymentWidgetSurface(widget, modifier)"),
)
@Composable
fun TossPaymentAgreement(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    if (widget.claimPrimarySurface()) TossPaymentWidgetSurface(widget, modifier)
}
