package io.github.l2hyunwoo.tosspayments

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

/**
 * iOS WebView host — the decisive advantage of the self-hosted approach.
 *
 * Toss's own iOS SDK is pure Swift with NO @objc, so Kotlin/Native cinterop cannot call it. This
 * implementation depends on NONE of it: WKWebView, WKWebViewConfiguration, WKUserContentController,
 * WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate are all consumed directly from the
 * platform.WebKit cinterop bindings, because WebKit IS an Obj-C framework. Zero Swift.
 *
 * All WKWebView operations are main-thread-only; [scope] is the main dispatcher.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class PlatformWebViewController actual constructor(
    private val config: PaymentWidgetConfig,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    internal var webView: WKWebView? = null
        private set

    private var onMessage: ((String) -> Unit)? = null
    private var onStatus: ((WidgetStatus) -> Unit)? = null
    private var onPageReady: (() -> Unit)? = null
    private var pageReadyFired = false

    // RETENTION: WKWebView holds navigationDelegate/UIDelegate weakly, and addScriptMessageHandler's
    // retain doesn't keep the Kotlin wrapper alive across the interop boundary. These MUST be strong
    // Kotlin properties for the WebView's lifetime, or callbacks silently stop firing.
    private val messageHandler = MessageHandler { json -> deliver(json) }
    private val navDelegate = NavDelegate(
        onResult = { json -> deliver(json) },
        onReady = { if (!pageReadyFired) { pageReadyFired = true; onPageReady?.invoke() } },
        onLoadFailed = { scope.launch { onStatus?.invoke(WidgetStatus.FAILED) } },
    )
    private val uiDelegate = UiDelegate()

    actual fun mount(
        onMessage: (String) -> Unit,
        onStatus: (WidgetStatus) -> Unit,
        onPageReady: () -> Unit,
    ) {
        this.onMessage = onMessage
        this.onStatus = onStatus
        this.onPageReady = onPageReady
        onStatus(WidgetStatus.LOADING)
        // The WKWebView is created lazily by the Compose host (ensureWebView) on the main thread.
    }

    actual fun evaluate(js: String) {
        scope.launch { webView?.evaluateJavaScript(js, null) }
    }

    actual fun dispose() {
        scope.launch {
            webView?.apply {
                navigationDelegate = null
                UIDelegate = null
                configuration.userContentController.removeScriptMessageHandlerForName(Bridge.NATIVE_CHANNEL)
                stopLoading()
            }
            webView = null
        }
        onMessage = null
        onStatus = null
        onPageReady = null
        pageReadyFired = false
    }

    /** Builds the WKWebView (main thread) and starts loading the host HTML. Called from UIKitView factory. */
    internal fun ensureWebView(): WKWebView {
        webView?.let { return it }
        val configuration = WKWebViewConfiguration().apply {
            // WKPreferences.javaScriptEnabled is deprecated on iOS; the modern switch is
            // defaultWebpagePreferences.allowsContentJavaScript.
            defaultWebpagePreferences.allowsContentJavaScript = true
            userContentController.addScriptMessageHandler(messageHandler, Bridge.NATIVE_CHANNEL)
        }
        val wv = WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
            navigationDelegate = navDelegate
            UIDelegate = uiDelegate
        }
        webView = wv
        scope.launch {
            val html = HtmlResource.load(config)
            wv.loadHTMLString(html, baseURL = NSURL.URLWithString(Bridge.BASE_URL))
        }
        return wv
    }

    private fun deliver(json: String) {
        scope.launch { onMessage?.invoke(json) }
    }

    // ----- delegates (NSObject() first supertype, protocol second, no parens) -----

    private class MessageHandler(val onJson: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            (didReceiveScriptMessage.body as? String)?.let(onJson)
        }
    }

    private inner class NavDelegate(
        val onResult: (String) -> Unit,
        val onReady: () -> Unit,
        val onLoadFailed: () -> Unit,
    ) : NSObject(), WKNavigationDelegateProtocol {

        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            // READY still comes from the JS 'ready' event; this only gates init/render evaluation.
            onReady()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
            onLoadFailed()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
            onLoadFailed()
        }

        // Implement ONLY the 3-arg overload to avoid override ambiguity. Call decisionHandler once.
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
            val url = decidePolicyForNavigationAction.request.URL
            val s = url?.absoluteString
            // The redirect result is parsed ONCE; decisionHandler is invoked before any fallible
            // delivery work so it can never be skipped (an un-called handler deadlocks WK navigation).
            val redirect = s?.let { RedirectParser.parse(it) }
            when {
                s == null -> decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
                redirect != null -> {
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                    onResult(Bridge.json.encodeToString(GuestMessage.serializer(), redirect))
                }
                url.scheme?.lowercase() in HTTP_SCHEMES -> {
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
                }
                else -> {
                    // App-scheme (supertoss, ispmobile, kftc-bankpay, card apps…) → launch externally.
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                    UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
                }
            }
        }
    }

    private class UiDelegate : NSObject(), WKUIDelegateProtocol {
        override fun webView(
            webView: WKWebView,
            createWebViewWithConfiguration: WKWebViewConfiguration,
            forNavigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures,
        ): WKWebView? {
            // Route window.open()/target=_blank into the same WebView so 3DS popups don't dead-end.
            if (forNavigationAction.targetFrame == null) {
                webView.loadRequest(forNavigationAction.request)
            }
            return null
        }
    }

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
    }
}
