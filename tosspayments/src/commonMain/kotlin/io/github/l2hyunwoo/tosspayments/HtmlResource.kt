package io.github.l2hyunwoo.tosspayments

import toss_payments_kmp.tosspayments.generated.resources.Res

/**
 * Loads the bundled host page (toss_widget.html) and injects the configured JS SDK URL.
 *
 * The HTML ships as a Compose Multiplatform resource (commonMain/composeResources/files), so a
 * single copy serves every target. [Res.readBytes] is suspend, hence this is suspend; each
 * platform host calls it from its main-bound coroutine before loading the WebView.
 */
internal object HtmlResource {
    private const val PATH = "files/toss_widget.html"

    suspend fun load(config: PaymentWidgetConfig): String {
        val raw = Res.readBytes(PATH).decodeToString()
        return raw.replace(Bridge.JS_SDK_URL_TOKEN, config.jsSdkUrl)
    }
}
