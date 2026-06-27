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
 * controller가 만들고 설정한 단일 iOS WKWebView를 새로운 [UIKitView] interop(CMP 1.11, UIView에 대해
 * generic)을 통해 Compose 트리에 노출한다. 이 WebView가 결제수단 선택기와 약관을 모두 렌더링한다.
 *
 * NonCooperative interaction mode가 필요하다. 그렇지 않으면 Compose가 WebView의 scroll/tap 제스처를 먹어버린다.
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
