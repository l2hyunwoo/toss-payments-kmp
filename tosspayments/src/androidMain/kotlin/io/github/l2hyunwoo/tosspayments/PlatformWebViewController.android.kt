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
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
        attachBridge(wv)
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

    /**
     * JS→Kotlin 브리지를 sentinel origin([Bridge.BASE_URL])에만 노출한다. 이게 핵심 보안 장치다:
     * 결제 도중 WebView에는 원격 PG/3DS/은행 페이지가 같은 WebView에 로드되는데, 전역
     * @JavascriptInterface는 그 모든 페이지에서 `window.tossNative`에 도달해 위조 paymentSuccess를
     * 주입할 수 있다. WebMessageListener는 onPostMessage의 sourceOrigin을 allowlist로 막아 우리
     * host 페이지에서 온 메시지만 신뢰한다.
     *
     * WEB_MESSAGE_LISTENER 미지원(구형 WebView APK) 시 @JavascriptInterface로 폴백한다 — origin
     * 제한이 없으므로 그 경우 인-프로세스 결과를 더더욱 신뢰하면 안 되고, 서버 confirm이 유일한 권위다.
     */
    @SuppressLint("RequiresFeature")
    private fun attachBridge(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                wv,
                Bridge.NATIVE_CHANNEL,
                setOf(Bridge.ORIGIN), // only the sentinel origin may use the bridge
            ) { _: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, _: JavaScriptReplyProxy ->
                // Defense in depth: the allowlist already enforces origin, but re-check.
                if (isMainFrame && "${sourceOrigin.scheme}://${sourceOrigin.host}" == Bridge.ORIGIN) {
                    message.data?.let(::deliver)
                }
            }
        } else {
            wv.addJavascriptInterface(LegacyJsBridge(), Bridge.NATIVE_CHANNEL)
        }
    }

    /** Fallback bridge for WebView versions without WEB_MESSAGE_LISTENER (no origin restriction). */
    private inner class LegacyJsBridge {
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
        // intent-redirection(confused deputy) 방어: 웹이 제어하는 intent:// 문자열은 명시적 component·
        // selector·임의 flag를 품을 수 있어, 그대로 startActivity하면 호스트 앱 권한으로 비공개 컴포넌트를
        // 띄울 수 있다. 외부 앱 전환에 필요한 것만 남긴다 — component/selector 제거, BROWSABLE 강제,
        // 위험 flag(GRANT_URI*) 제거.
        intent.component = null
        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.flags = intent.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION).inv()
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
        // 커스텀 스킴도 ACTION_VIEW + BROWSABLE로만 띄운다(특정 컴포넌트 지정 불가, 표준 앱 전환과 동일).
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching { context.startActivity(intent) }
    }

    /**
     * window.open()/target=_blank 팝업을 호스팅한다 (일부 3DS/간편결제 flow). WebKit은 팝업의
     * target URL을 전달하려면 transport를 통해 실제 WebView를 요구한다; 첫 navigation을 캡처해
     * main view로 라우팅한 뒤 누수되지 않도록 스스로 destroy하는 일회용 child를 사용한다
     * (나머지 flow는 main view가 주도한다).
     */
    private inner class TossWebChromeClient : WebChromeClient() {
        // 위젯 페이지의 JS console을 logcat("TossWidget" 태그)으로 흘려, JS 에러·SDK 경고를
        // 네이티브에서 진단할 수 있게 한다(WebView 안은 평소 깜깜하다).
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
            android.util.Log.d(
                "TossWidget",
                "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
            )
            return true
        }

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
