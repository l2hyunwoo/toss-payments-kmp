package io.github.l2hyunwoo.tosspayments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * [TossPaymentWidgetSurface]를 구동하는 [TossPaymentWidget]를 생성/remember한다.
 *
 * [amount]는 선언적이다: WebView는 [config]/[renderOptions]를 key로 한 번만 생성되고,
 * [amount]가 바뀌면 준비된 뒤 setAmount로 다시 적용하므로 청구 금액은 WebView 재생성 없이
 * 항상 composition 입력을 따라간다.
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
    // 최초 amount는 start()가 렌더링하므로, READY 이후 변경된 amount만 다시 적용한다.
    val status by widget.status.collectAsState()
    LaunchedEffect(widget, amount, status) {
        if (status == WidgetStatus.READY) widget.updateAmount(amount)
    }
    return widget
}

/**
 * 결제수단 선택기 + 필수 약관 동의를 함께 렌더링하는 단일 inline surface.
 *
 * 두 영역을 별도 composable로 쪼갤 수 없는 이유: v2 JS SDK는 두 렌더와 setAmount/requestPayment를
 * 모두 하나의 `widgets` 인스턴스(= WebView 하나 안의 JS session 하나)가 소유하도록 요구하는데,
 * Compose interop은 native view를 정확히 한 slot에만 붙이기 때문이다.
 *
 * 높이는 JS SDK가 자동으로 구동하므로 세로로 wrapping되는 slot에 배치한다.
 */
@Composable
fun TossPaymentWidgetSurface(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    TossWidgetHost(widget, modifier)
}

/** 단일 WebView를 임베드하는 플랫폼별 Compose host (Android → AndroidView, iOS → UIKitView). */
@Composable
internal expect fun TossWidgetHost(widget: TossPaymentWidget, modifier: Modifier)

/**
 * Deprecated: 둘 중 먼저 composition에 들어오는 쪽만 통합 [TossPaymentWidgetSurface]를 렌더링하고
 * 나머지는 no-op이라, 기존의 (methods + agreement) 두 번 호출 코드도 그대로 컴파일된다.
 */
@Deprecated(
    "The v2 widget renders methods and agreement in one WebView session. Call TossPaymentWidgetSurface(widget) once instead.",
    ReplaceWith("TossPaymentWidgetSurface(widget, modifier)"),
)
@Composable
fun TossPaymentMethods(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    if (widget.claimPrimarySurface()) TossPaymentWidgetSurface(widget, modifier)
}

/** Deprecated: 약관 동의는 [TossPaymentWidgetSurface]의 일부다. [TossPaymentMethods] 참조. */
@Deprecated(
    "The v2 widget renders methods and agreement in one WebView session. Call TossPaymentWidgetSurface(widget) once instead.",
    ReplaceWith("TossPaymentWidgetSurface(widget, modifier)"),
)
@Composable
fun TossPaymentAgreement(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    if (widget.claimPrimarySurface()) TossPaymentWidgetSurface(widget, modifier)
}
