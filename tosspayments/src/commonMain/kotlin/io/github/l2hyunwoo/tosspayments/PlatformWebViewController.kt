package io.github.l2hyunwoo.tosspayments

/**
 * 플랫폼마다 갈리는 유일한 이음매(seam). 사용자에게 노출되는 모든 것(composable, config, error model,
 * JS bridge 프로토콜)은 commonMain에 있고, WebView host만 다르다:
 *  - androidMain → android.webkit.WebView + @JavascriptInterface + shouldOverrideUrlLoading
 *  - iosMain     → platform.WebKit.WKWebView + WKScriptMessageHandler + decidePolicyFor
 *                  (Kotlin/Native에서 직접 소비 — Swift 0줄. WebKit은 cinterop 헤더가 있는
 *                  Obj-C 프레임워크라, Toss의 순수 Swift SDK와 달리 가능하다.)
 *
 * 구현체는 반드시 다음을 지킨다:
 *  - 번들된 host HTML을 로드(pinned [PaymentWidgetConfig.jsSdkUrl]),
 *  - 들어오는 JSON을 [onMessage]로 전달([Bridge]를 통해 [GuestMessage]로 디코드),
 *  - load/fail readiness를 [onStatus]로 보고,
 *  - 모든 WebView 작업을 main thread에서 실행하고 콜백도 main thread로 되돌리며,
 *  - app-scheme / intent redirect를 가로채 은행/간편결제 앱이 실행되고 돌아오게 한다.
 */
internal expect class PlatformWebViewController(config: PaymentWidgetConfig) {

    /**
     * host 페이지 로드를 시작한다.
     *
     * @param onMessage 페이지가 post back하는 원시 JSON을 받는다([Bridge]를 통해 [GuestMessage]로
     *   디코드); redirect 가로채기로 네이티브가 합성한 success/fail도 받는다.
     * @param onStatus readiness 전이를 받는다.
     * @param onPageReady 페이지(와 인라인 script)의 로딩이 끝나면 한 번 발생
     *   (Android `onPageFinished` / iOS `didFinishNavigation`). JS SDK는 이 시점 이후에야
     *   `evaluate` 가능하다 — controller는 이게 발생할 때까지 init/render 커맨드를 미룬다.
     *
     * 위젯 생명주기마다 한 번 호출된다(composable의 DisposableEffect를 통해 [TossPaymentWidget.start]에서);
     * 플랫폼 WebView 자체는 Compose host가 lazy하게 생성한다.
     */
    fun mount(
        onMessage: (String) -> Unit,
        onStatus: (WidgetStatus) -> Unit,
        onPageReady: () -> Unit,
    )

    /** Kotlin → JS. 페이지 로드 이후에만 호출해야 안전하다; mount 전에는 no-op. */
    fun evaluate(js: String)

    /** WebView를 해제하고 bridge를 분리한다. */
    fun dispose()
}
