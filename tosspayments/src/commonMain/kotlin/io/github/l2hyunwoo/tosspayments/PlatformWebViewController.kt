package io.github.l2hyunwoo.tosspayments

/**
 * The single per-platform seam. Everything user-facing (composables, config, error model,
 * JS bridge protocol) lives in commonMain; only the WebView host differs:
 *  - androidMain → android.webkit.WebView + @JavascriptInterface + shouldOverrideUrlLoading
 *  - iosMain     → platform.WebKit.WKWebView + WKScriptMessageHandler + decidePolicyFor
 *                  (consumed directly from Kotlin/Native — zero Swift, because WebKit is an
 *                  Obj-C framework with cinterop headers, unlike Toss's pure-Swift SDK).
 *
 * Implementations MUST:
 *  - load the bundled host HTML (pinned [PaymentWidgetConfig.jsSdkUrl]),
 *  - deliver inbound JSON over [onMessage] (decoded via [Bridge] into [GuestMessage]),
 *  - report load/fail readiness over [onStatus],
 *  - run all WebView operations on the main thread and marshal callbacks back to it,
 *  - intercept app-scheme / intent redirects so bank/easy-pay apps launch and return.
 */
internal expect class PlatformWebViewController(config: PaymentWidgetConfig) {

    /**
     * Begin loading the host page.
     *
     * @param onMessage receives raw JSON the page posts back (decoded via [Bridge] into a
     *   [GuestMessage]); also receives native-synthesized success/fail from redirect interception.
     * @param onStatus receives readiness transitions.
     * @param onPageReady fired once the page (and its inline script) finishes loading
     *   (Android `onPageFinished` / iOS `didFinishNavigation`). The JS SDK is `evaluate`-able only
     *   after this — the controller defers init/render commands until it fires.
     *
     * Called once per widget lifecycle (from [TossPaymentWidget.start] via the composable's
     * DisposableEffect); the platform WebView itself is created lazily by the Compose host.
     */
    fun mount(
        onMessage: (String) -> Unit,
        onStatus: (WidgetStatus) -> Unit,
        onPageReady: () -> Unit,
    )

    /** Kotlin → JS. Safe to call only after the page is loaded; no-op before mount. */
    fun evaluate(js: String)

    /** Tear down the WebView and detach bridges. */
    fun dispose()
}
