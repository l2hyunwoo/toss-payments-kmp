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
 * Android WebView host. 번들된 host HTML(v2 JS SDK를 끌어옴)을 로드하고, @JavascriptInterface로
 * JS↔Kotlin을 브리지하며, requestPayment의 sentinel success/fail URL로의 redirect를 가로채고,
 * `intent://` / app-scheme URL로 외부 은행/간편결제 앱을 실행한다.
 *
 * 모든 WebView 연산은 main thread에서 실행된다; [scope]가 off-thread 콜백을 다시 마샬링한다.
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
        // WebView 자체는 Context를 들고 있는 Compose host(attach)가 생성한다.
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

    /** WebView를 생성·설정하고 로딩을 시작한다. AndroidView factory에서 호출된다. */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun attach(context: Context): WebView {
        val wv = WebView(context)
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // JS SDK가 DOM storage를 사용한다
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true) // 3DS 팝업을 위해 onCreateWindow를 활성화한다
        }
        wv.addJavascriptInterface(JsBridge(), Bridge.NATIVE_CHANNEL)
        wv.webViewClient = TossWebViewClient()
        wv.webChromeClient = TossWebChromeClient()

        scope.launch {
            val html = HtmlResource.load(config)
            // success/fail redirect가 same-origin이 되어 shouldOverrideUrlLoading에 도달하도록
            // baseURL은 sentinel origin을 공유해야 한다.
            wv.loadDataWithBaseURL(Bridge.BASE_URL, html, "text/html", "utf-8", null)
        }
        return wv
    }

    private fun deliver(json: String) {
        scope.launch { onMessage?.invoke(json) }
    }

    /** window.tossNative.postMessage(json) → 여기로 온다. */
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
            // sub-resource가 아니라 main document가 실패한 경우에만 위젯을 로드할 수 없다.
            if (request.isForMainFrame) scope.launch { onStatus?.invoke(WidgetStatus.FAILED) }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleUrl(view, request.url.toString())

        @Deprecated("Pre-API24 fallback")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrl(view, url)
    }

    /** navigation을 처리했으면(그리고 취소해야 하면) true를 반환한다. */
    private fun handleUrl(view: WebView, url: String): Boolean {
        // requestPayment 결과: native가 sentinel redirect를 GuestMessage로 파싱한다.
        RedirectParser.parse(url)?.let { msg ->
            deliver(Bridge.json.encodeToString(GuestMessage.serializer(), msg))
            return true
        }
        return when (Uri.parse(url).scheme?.lowercase()) {
            "http", "https" -> false // WebView가 로드하도록 둔다 (위젯 UI, 은행 3DS 페이지)
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
            // 앱 미설치 → intent에 package가 선언돼 있으면 그 앱의 Play Store 페이지로 폴백한다.
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
     * window.open()/target=_blank 팝업을 호스팅한다 (일부 3DS/간편결제 flow). WebKit은 팝업의
     * target URL을 전달하려면 transport를 통해 실제 WebView를 요구한다; 첫 navigation을 캡처해
     * main view로 라우팅한 뒤 누수되지 않도록 스스로 destroy하는 일회용 child를 사용한다
     * (나머지 flow는 main view가 주도한다).
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
                    if (!handleUrl(view, url)) view.loadUrl(url) // app-scheme 처리됨, 아니면 main에서 로드
                    child.destroy() // 일회용 팝업 view — 해제한다
                }
            }
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = child
            resultMsg.sendToTarget()
            return true
        }
    }
}
