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
 * iOS WebView host — self-hosted 방식의 결정적 이점.
 *
 * Toss 자체 iOS SDK는 @objc가 전혀 없는 순수 Swift라 Kotlin/Native cinterop으로 호출할 수 없다.
 * 이 구현은 그것에 전혀 의존하지 않는다: WKWebView, WKWebViewConfiguration, WKUserContentController,
 * WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate 모두 platform.WebKit cinterop 바인딩에서
 * 직접 가져온다 — WebKit 자체가 Obj-C 프레임워크이기 때문이다. Swift는 전혀 쓰지 않는다.
 *
 * 모든 WKWebView 작업은 main thread 전용이며, [scope]가 main dispatcher다.
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

    // RETENTION: WKWebView는 navigationDelegate/UIDelegate를 weak로 잡고, addScriptMessageHandler의
    // retain은 interop 경계를 넘어 Kotlin wrapper를 살려두지 못한다. WebView 수명 동안 strong Kotlin
    // 프로퍼티로 잡아둬야 하며, 그렇지 않으면 콜백이 조용히 안 불린다.
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
        // WKWebView는 Compose host(ensureWebView)가 main thread에서 lazy하게 생성한다.
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

    /** WKWebView를 만들고(main thread) host HTML 로딩을 시작한다. UIKitView factory에서 호출한다. */
    internal fun ensureWebView(): WKWebView {
        webView?.let { return it }
        val configuration = WKWebViewConfiguration().apply {
            // WKPreferences.javaScriptEnabled은 iOS에서 deprecated다. 최신 스위치는
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

    // ----- delegate들 (NSObject()를 첫 supertype으로, protocol을 두 번째로, 괄호 없이) -----

    private class MessageHandler(val onJson: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            // sentinel origin의 main frame에서 온 메시지만 신뢰한다. 결제 도중 원격 PG/3DS 페이지가
            // 같은 WebView에 로드되며, origin 검증이 없으면 그 페이지가 위조 paymentSuccess를 post할 수
            // 있다. WKWebView의 handler는 모든 frame에 걸리므로 frameInfo로 직접 막는다.
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
            // READY는 여전히 JS 'ready' 이벤트에서 온다. 여기서는 init/render evaluation만 gate한다.
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

        // override 모호성을 피하려고 3-arg overload만 구현한다. decisionHandler는 한 번만 호출한다.
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
            val url = decidePolicyForNavigationAction.request.URL
            val s = url?.absoluteString
            // redirect 결과는 한 번만 파싱한다. decisionHandler는 실패할 수 있는 delivery 작업보다
            // 먼저 호출해 절대 건너뛰지 않게 한다(호출 안 된 handler는 WK navigation을 deadlock시킨다).
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
                    // App-scheme(supertoss, ispmobile, kftc-bankpay, 카드 앱…) → 외부에서 실행.
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
            // window.open()/target=_blank를 같은 WebView로 보내 3DS 팝업이 막다른 길에 안 빠지게 한다.
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
