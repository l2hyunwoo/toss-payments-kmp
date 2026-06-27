package io.github.l2hyunwoo.tosspayments

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * controller가 만들고 설정한 단일 Android WebView를 Compose 트리에 올린다.
 * 이 WebView는 결제수단 선택 UI와 약관 동의를 함께 렌더링하며, 높이는
 * JS SDK가 결정한다(WebView가 콘텐츠를 wrap).
 */
@Composable
internal actual fun TossWidgetHost(widget: TossPaymentWidget, modifier: Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context -> widget.controller.attach(context) },
    )
}
