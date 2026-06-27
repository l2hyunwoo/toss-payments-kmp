package io.github.l2hyunwoo.tosspayments

import toss_payments_kmp.tosspayments.generated.resources.Res

/**
 * 번들된 host 페이지(toss_widget.html)를 로드하고 설정된 JS SDK URL을 주입한다.
 *
 * HTML은 Compose Multiplatform 리소스(commonMain/composeResources/files)로 제공되므로 하나의
 * 사본으로 모든 타깃에 대응한다. [Res.readBytes]가 suspend라 이 함수도 suspend다. 각 플랫폼
 * host는 WebView를 로드하기 전에 main-bound 코루틴에서 이를 호출한다.
 */
internal object HtmlResource {
    private const val PATH = "files/toss_widget.html"

    suspend fun load(config: PaymentWidgetConfig): String {
        val raw = Res.readBytes(PATH).decodeToString()
        return raw.replace(Bridge.JS_SDK_URL_TOKEN, config.jsSdkUrl)
    }
}
