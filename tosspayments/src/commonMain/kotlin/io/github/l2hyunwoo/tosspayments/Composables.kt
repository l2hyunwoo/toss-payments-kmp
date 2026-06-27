package io.github.l2hyunwoo.tosspayments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * 새로 마운트된 WebView host에 바인딩된 [TossPaymentWidget]를 생성(하고 remember)한다.
 * 반환된 핸들은 [TossPaymentWidgetSurface]를 구동하며 준비 상태 / 약관 동의 /
 * 선택 상태와 [TossPaymentWidget.requestPayment]를 노출한다.
 *
 * [amount]는 선언적이다: WebView는 한 번만 생성되고([config]/[renderOptions]를 key로),
 * [amount]가 바뀌면 준비된 뒤 setAmount로 다시 적용하므로, 렌더링/청구되는 금액은
 * WebView를 재생성하지 않고도 항상 composition 입력을 따라간다.
 *
 * 위젯은 composition에서 벗어나면 자동으로 dispose된다.
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
    // 위젯이 준비되면 변경된 amount를 다시 적용한다(최초 amount는 start()가 렌더링한다).
    val status by widget.status.collectAsState()
    LaunchedEffect(widget, amount, status) {
        if (status == WidgetStatus.READY) widget.updateAmount(amount)
    }
    return widget
}

/**
 * 결제 위젯을 위한 단일 inline surface. 결제수단 선택기와 필수 약관 동의를 함께(세로로 쌓아)
 * 렌더링하는 WebView 하나 / JS session 하나를 host한다.
 *
 * 왜 surface 하나인가(결제수단/약관을 별도 composable로 두지 않는 이유): v2 JS SDK는
 * 두 렌더와 `setAmount`, `requestPayment`를 모두 하나의 `widgets` 인스턴스가 소유하도록 요구한다.
 * 그 단일 JS session은 WebView 하나 안에 살고, Compose interop은 native view를 정확히 한 slot에만
 * 붙이므로, JS session을 쪼개지 않고서는 두 영역을 두 composable로 나눌 수 없다.
 *
 * 높이는 JS SDK가 자동으로 구동하므로, 세로로 wrapping되는 slot에 배치한다.
 */
@Composable
fun TossPaymentWidgetSurface(widget: TossPaymentWidget, modifier: Modifier = Modifier) {
    TossWidgetHost(widget, modifier)
}

/**
 * 단일 WebView를 임베드하고(Android → AndroidView, iOS → UIKitView) 그 안에 두 위젯 영역을
 * 렌더링하는 플랫폼별 Compose host.
 */
@Composable
internal expect fun TossWidgetHost(widget: TossPaymentWidget, modifier: Modifier)

/**
 * Deprecated: v2 위젯은 결제수단 + 약관을 WebView session 하나에서 렌더링하므로 별도의 두 surface가
 * 될 수 없다. 둘 중 먼저 composition에 들어오는 쪽이 통합된 [TossPaymentWidgetSurface]를 렌더링하고,
 * 나머지는 no-op이라 기존의 두 번 호출하는 코드도 여전히 컴파일된다.
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
