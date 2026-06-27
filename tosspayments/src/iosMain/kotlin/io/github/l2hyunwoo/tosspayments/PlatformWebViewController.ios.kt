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
 * iOS WebView host. Toss 자체 iOS SDK는 순수 Swift(@objc 없음)라 Kotlin/Native cinterop으로 호출 불가 —
 * 대신 platform.WebKit(Obj-C 프레임워크) 바인딩으로 WKWebView를 직접 호스팅한다.
 * 모든 WKWebView 작업은 main thread 전용이며 [scope]가 main dispatcher다.
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

    // RETENTION: WKWebView는 delegate를 weak로 잡고 interop retain은 Kotlin wrapper를 살려두지 못한다.
    // WebView 수명 동안 strong 프로퍼티로 잡아둬야 하며, 아니면 콜백이 조용히 안 불린다.
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
        // WKWebView는 Compose host(ensureWebView)가 main thread에서 lazy 생성한다.
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

    /** UIKitView factory에서 main thread로 호출. WKWebView 생성 + host HTML 로딩 시작. */
    internal fun ensureWebView(): WKWebView {
        webView?.let { return it }
        val configuration = WKWebViewConfiguration().apply {
            // WKPreferences.javaScriptEnabled은 deprecated — allowsContentJavaScript가 최신 스위치.
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

    // delegate: NSObject()를 첫 supertype, protocol을 두 번째(괄호 없이) — cinterop 요구사항.

    private class MessageHandler(val onJson: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            // sentinel origin의 main frame에서 온 메시지만 신뢰한다. 결제 중 같은 WebView에 로드되는
            // 원격 PG/3DS 페이지가 위조 paymentSuccess를 post할 수 있고, handler는 모든 frame에 걸리므로
            // frameInfo로 직접 막는다.
            val origin = didReceiveScriptMessage.frameInfo.securityOrigin
            val trusted = didReceiveScriptMessage.frameInfo.isMainFrame() &&
                origin.protocol == Bridge.ORIGIN_SCHEME &&
                origin.host == Bridge.ORIGIN_HOST
            if (trusted) (didReceiveScriptMessage.body as? String)?.let(onJson)
        }
    }

    private inner class NavDelegate(
        val onResult: (String) -> Unit,
        val onReady: () -> Unit,
        val onLoadFailed: () -> Unit,
    ) : NSObject(), WKNavigationDelegateProtocol {

        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            // 여기선 init/render evaluation만 gate. READY status는 JS 'ready' 이벤트에서 온다.
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

        // override 모호성 회피를 위해 3-arg overload만 구현. decisionHandler는 정확히 한 번만 호출.
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
            val url = decidePolicyForNavigationAction.request.URL
            val s = url?.absoluteString
            // decisionHandler는 실패 가능한 delivery보다 먼저 호출한다 — 미호출 시 WK navigation deadlock.
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
                    // App-scheme(supertoss, ispmobile, kftc-bankpay, 카드 앱…) → 외부 앱으로 실행.
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
            // window.open()/target=_blank를 같은 WebView로 — 안 그러면 3DS 팝업이 막다른 길에 빠진다.
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
