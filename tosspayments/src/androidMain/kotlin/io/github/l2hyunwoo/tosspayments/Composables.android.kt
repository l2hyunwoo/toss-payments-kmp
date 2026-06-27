package io.github.l2hyunwoo.tosspayments

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * controller가 만든 단일 WebView를 올린다(결제수단·약관을 한 세션에서 렌더).
 * 높이는 JS SDK가 결정한다 — WebView가 콘텐츠를 wrap.
 */
@Composable
internal actual fun TossWidgetHost(widget: TossPaymentWidget, modifier: Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context -> widget.controller.attach(context) },
    )
}
