package io.github.l2hyunwoo.tosspayments

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android WebView host. Loads the bundled host HTML (which pulls in the v2 JS SDK), bridges
 * JS↔Kotlin over a @JavascriptInterface, intercepts the requestPayment redirect to the sentinel
 * success/fail URLs, and launches external bank/easy-pay apps via `intent://` / app-scheme URLs.
 *
 * All WebView operations run on the main thread; [scope] marshals any off-thread callback back.
 */
internal actual class PlatformWebViewController actual constructor(
    private val config: PaymentWidgetConfig,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private var webView: WebView? = null
    private var onMessage: ((String) -> Unit)? = null
    private var onStatus: ((WidgetStatus) -> Unit)? = null
    private var onPageReady: (() -> Unit)? = null
    private var pageReadyFired = false

    actual fun mount(
        onMessage: (String) -> Unit,
        onStatus: (WidgetStatus) -> Unit,
        onPageReady: () -> Unit,
    ) {
        this.onMessage = onMessage
        this.onStatus = onStatus
        this.onPageReady = onPageReady
        onStatus(WidgetStatus.LOADING)
        // The WebView itself is created by the Compose host (attach) which holds a Context.
    }

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
        onPageReady = null
        pageReadyFired = false
    }

    /** Builds, configures, and starts loading the WebView. Called from the AndroidView factory. */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun attach(context: Context): WebView {
        val wv = WebView(context)
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // the JS SDK uses DOM storage
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true) // enables onCreateWindow for 3DS popups
        }
        wv.addJavascriptInterface(JsBridge(), Bridge.NATIVE_CHANNEL)
        wv.webViewClient = TossWebViewClient()
        wv.webChromeClient = TossWebChromeClient()

        scope.launch {
            val html = HtmlResource.load(config)
            // baseURL must share the sentinel origin so the success/fail redirect is same-origin
            // and reaches shouldOverrideUrlLoading.
            wv.loadDataWithBaseURL(Bridge.BASE_URL, html, "text/html", "utf-8", null)
        }
        return wv
    }

    private fun deliver(json: String) {
        scope.launch { onMessage?.invoke(json) }
    }

    /** window.tossNative.postMessage(json) → here. */
    private inner class JsBridge {
        @JavascriptInterface
        fun postMessage(json: String) = deliver(json)
    }

    private inner class TossWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            if (!pageReadyFired && url != null && url.startsWith(Bridge.BASE_URL)) {
                pageReadyFired = true
                onPageReady?.invoke()
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            // Only the main document failing (not a sub-resource) means the widget can't load.
            if (request.isForMainFrame) scope.launch { onStatus?.invoke(WidgetStatus.FAILED) }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleUrl(view, request.url.toString())

        @Deprecated("Pre-API24 fallback")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrl(view, url)
    }

    /** Returns true if the navigation was handled (and should be cancelled). */
    private fun handleUrl(view: WebView, url: String): Boolean {
        // requestPayment result: native parses the sentinel redirect into a GuestMessage.
        RedirectParser.parse(url)?.let { msg ->
            deliver(Bridge.json.encodeToString(GuestMessage.serializer(), msg))
            return true
        }
        return when (Uri.parse(url).scheme?.lowercase()) {
            "http", "https" -> false // let the WebView load it (widget UI, bank 3DS pages)
            "intent" -> { launchIntentScheme(view.context, url); true }
            null -> false
            else -> { launchAppScheme(view.context, url); true } // ispmobile, supertoss, kftc-bankpay, card apps…
        }
    }

    private fun launchIntentScheme(context: Context, url: String) {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // App not installed → fall back to its Play Store page if the intent declares a package.
            intent.`package`?.let { pkg ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                }
            }
        }
    }

    private fun launchAppScheme(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /**
     * Hosts window.open()/target=_blank popups (some 3DS/easy-pay flows). WebKit requires a real
     * WebView via the transport to deliver the popup's target URL; we use a throwaway child that
     * captures its first navigation, routes it into the main view, and then destroys itself so it
     * does not leak (the main view drives the rest of the flow).
     */
    private inner class TossWebChromeClient : WebChromeClient() {
        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val child = WebView(view.context)
            child.settings.javaScriptEnabled = true
            child.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    routeAndDispose(v, request.url.toString())
                    return true
                }

                @Deprecated("Pre-API24 fallback")
                override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                    routeAndDispose(v, url)
                    return true
                }

                private fun routeAndDispose(child: WebView, url: String) {
                    if (!handleUrl(view, url)) view.loadUrl(url) // app-scheme handled, else load in main
                    child.destroy() // throwaway popup view — release it
                }
            }
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = child
            resultMsg.sendToTarget()
            return true
        }
    }
}
