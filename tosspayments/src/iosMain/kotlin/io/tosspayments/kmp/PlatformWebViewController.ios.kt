package io.tosspayments.kmp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.WebKit.WKWebView

/**
 * iOS WebView host — the decisive advantage of the self-hosted approach.
 *
 * Toss's own iOS SDK is pure Swift with NO @objc, so Kotlin/Native cinterop literally cannot
 * call it. This implementation depends on NONE of it: WKWebView, WKWebViewConfiguration,
 * WKUserContentController, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate are all
 * consumed directly from the platform.WebKit cinterop bindings, because WebKit IS an Obj-C
 * framework. Net result: zero Swift in the consumer app.
 *
 * STUB: real wiring (WKWebView creation with a userContentController message handler named
 * Bridge.NATIVE_CHANNEL, evaluateJavaScript for Kotlin→JS, WKNavigationDelegate.decidePolicyFor
 * to detect `tosspayments://success|fail` and non-http schemes → UIApplication.openURL with App
 * Store fallback, WKUIDelegate.createWebViewWith for window.open popups) lands in milestone 2.
 * All WebView ops are main-thread-only; [scope] marshals callbacks back to the main dispatcher.
 */
internal actual class PlatformWebViewController actual constructor(
    private val config: PaymentWidgetConfig,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Set by the Compose host (UIKitView factory) once a WKWebView exists. */
    internal var webView: WKWebView? = null

    private var onMessage: ((String) -> Unit)? = null
    private var onStatus: ((WidgetStatus) -> Unit)? = null

    actual fun mount(onMessage: (String) -> Unit, onStatus: (WidgetStatus) -> Unit) {
        this.onMessage = onMessage
        this.onStatus = onStatus
        // TODO(milestone-2): build WKWebViewConfiguration with a WKScriptMessageHandler under
        //  Bridge.NATIVE_CHANNEL forwarding to onMessage; load the bundled host HTML pinned to
        //  config.jsSdkUrl; emit onStatus(READY) from didFinishNavigation + JS 'ready' event.
    }

    actual fun evaluate(js: String) {
        scope.launch { webView?.evaluateJavaScript(js, null) }
    }

    actual fun dispose() {
        scope.launch {
            webView?.stopLoading()
            webView = null
        }
        onMessage = null
        onStatus = null
    }

    /** Bridge entry the WKScriptMessageHandler forwards JSON into. */
    internal fun receiveFromJs(json: String) {
        scope.launch { onMessage?.invoke(json) }
    }
}
