package io.github.l2hyunwoo.tosspayments

/**
 * 플랫폼마다 갈리는 유일한 이음매(seam). 공개 API는 모두 commonMain에 있고 WebView host만 다르다
 * (androidMain: WebView, iosMain: WKWebView를 Kotlin/Native에서 직접 소비 — Swift 0줄).
 *
 * 구현체 불변식:
 *  - 모든 WebView 작업과 콜백은 main thread,
 *  - app-scheme / intent redirect를 가로채 은행/간편결제 앱이 실행되고 돌아오게 한다.
 */
internal expect class PlatformWebViewController(config: PaymentWidgetConfig) {

    /**
     * host 페이지 로드를 시작한다.
     *
     * @param onMessage 페이지가 post back하는 원시 JSON; redirect 가로채기로 네이티브가 합성한 success/fail도 받는다.
     * @param onStatus readiness 전이.
     * @param onPageReady 페이지 로딩 완료 시 한 번. JS SDK는 이 시점 이후에야 `evaluate` 가능 —
     *   controller는 이게 발생할 때까지 init/render 커맨드를 미룬다.
     */
    fun mount(
        onMessage: (String) -> Unit,
        onStatus: (WidgetStatus) -> Unit,
        onPageReady: () -> Unit,
    )

    /** Kotlin → JS. 페이지 로드 이후에만 호출해야 안전하다; mount 전에는 no-op. */
    fun evaluate(js: String)

    /** WebView를 해제한다. */
    fun dispose()
}
