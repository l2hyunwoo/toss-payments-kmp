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
     * Begin loading the host page. [onMessage] receives raw JSON the page posts back;
     * [onStatus] receives readiness transitions. Idempotent — calling twice is a no-op.
     */
    fun mount(onMessage: (String) -> Unit, onStatus: (WidgetStatus) -> Unit)

    /** Kotlin → JS. Safe to call only after the page is loaded; no-op before mount. */
    fun evaluate(js: String)

    /** Tear down the WebView and detach bridges. */
    fun dispose()
}
