package io.github.l2hyunwoo.tosspayments

import toss_payments_kmp.tosspayments.generated.resources.Res

/**
 * 번들된 host 페이지를 로드하고 JS SDK URL을 주입한다.
 *
 * [Res.readBytes]가 suspend라 이 함수도 suspend다. 각 플랫폼 host는 WebView 로드 전
 * main-bound 코루틴에서 호출한다.
 */
internal object HtmlResource {
    private const val PATH = "files/toss_widget.html"

    suspend fun load(config: PaymentWidgetConfig): String {
        val raw = Res.readBytes(PATH).decodeToString()
        return raw.replace(Bridge.JS_SDK_URL_TOKEN, config.jsSdkUrl)
    }
}
