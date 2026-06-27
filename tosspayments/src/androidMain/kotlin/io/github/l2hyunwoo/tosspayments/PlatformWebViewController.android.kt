package io.github.l2hyunwoo.tosspayments

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android WebView host. Verified parity target: Toss's own SDK uses
 * `PaymentWebView extends android.webkit.WebView` loading a bundled HTML page that pulls in
 * the JS SDK; completion is signaled over a @JavascriptInterface bridge (not URL interception).
 *
 * STUB: real wiring (WebView creation, addJavascriptInterface, WebViewClient with
 * shouldOverrideUrlLoading for `intent://` → Intent.parseUri + `market://` fallback,
 * WebChromeClient.onCreateWindow for 3DS popups, JS-driven height) lands in the next milestone.
 * Everything here must run on the main thread; [scope] marshals back from any off-thread callback.
 */
internal actual class PlatformWebViewController actual constructor(
    private val config: PaymentWidgetConfig,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Set by the Compose host (AndroidView factory) once a WebView exists. */
    internal var webView: WebView? = null

    private var onMessage: ((String) -> Unit)? = null
    private var onStatus: ((WidgetStatus) -> Unit)? = null

    actual fun mount(onMessage: (String) -> Unit, onStatus: (WidgetStatus) -> Unit) {
        this.onMessage = onMessage
        this.onStatus = onStatus
        // TODO(milestone-2): load the bundled host HTML pinned to config.jsSdkUrl,
        //  attach @JavascriptInterface(Bridge.NATIVE_CHANNEL) forwarding to onMessage,
        //  and emit onStatus(READY) from WebViewClient.onPageFinished + JS 'ready' event.
    }

    @SuppressLint("JavascriptInterface")
    actual fun evaluate(js: String) {
        scope.launch { webView?.evaluateJavascript(js, null) }
    }

    actual fun dispose() {
        scope.launch {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
        onMessage = null
        onStatus = null
    }

    /** Bridge entry the JS page calls via window.tossNative.postMessage(json). */
    internal fun receiveFromJs(json: String) {
        scope.launch { onMessage?.invoke(json) }
    }

    /** Used by the Compose host to build a WebView with the right Context. */
    internal fun newWebView(context: Context): WebView = WebView(context).also { webView = it }
}
