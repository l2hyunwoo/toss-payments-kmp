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
 * controller의 단일 WKWebView를 [UIKitView]로 노출한다 — 이 한 WebView가 결제수단 선택기와 약관을 모두 렌더링한다.
 *
 * NonCooperative interaction mode 필수: 아니면 Compose가 WebView의 scroll/tap 제스처를 먹는다.
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
