package io.github.l2hyunwoo.tosspayments

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 번들된 HTML 페이지(TossPayments JS SDK 호스팅)와 Kotlin 컨트롤러 사이의 wire protocol.
 * 양쪽 모두 common Kotlin이고 transport(evaluate / receive)만 플랫폼별이다.
 */
internal object Bridge {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    const val JS_NAMESPACE: String = "__toss"

    const val NATIVE_CHANNEL: String = "tossNative"

    /**
     * host HTML이 로드되는 sentinel origin (baseURL). redirect navigation이 same-origin으로 유지돼
     * 플랫폼 interceptor에 도달하도록 [SUCCESS_URL] / [FAIL_URL]의 origin과 반드시 일치해야 한다.
     * 실제로 이 host에서 fetch되지 않는다 — in-memory 문자열에서 로드된다.
     */
    const val BASE_URL: String = "https://tosspayments.local/"

    /**
     * JS→Kotlin 브리지를 이 origin에서 온 메시지로만 제한하는 데 쓴다(Android WebMessageListener
     * allowlist / iOS frameInfo 검증). 원격 PG 페이지에서 브리지가 닿아 success가 위조되는 것을 막는다.
     */
    const val ORIGIN: String = "https://tosspayments.local"

    /** iOS securityOrigin 검증용. */
    const val ORIGIN_SCHEME: String = "https"

    /** iOS securityOrigin 검증용. */
    const val ORIGIN_HOST: String = "tosspayments.local"

    /**
     * requestPayment 성공 시 여기로 redirect된다 (mobile에서는 Promise 경로를 쓸 수 없다).
     * native host가 로드를 취소하고 query에서 paymentKey/orderId/amount를 파싱한다.
     */
    const val SUCCESS_URL: String = "https://tosspayments.local/widget/success"

    /** requestPayment 실패 시 여기로 redirect된다 (Promise 경로 불가). */
    const val FAIL_URL: String = "https://tosspayments.local/widget/fail"

    const val JS_SDK_URL_TOKEN: String = "__TOSS_JS_SDK_URL__"
}

// ----- JS → Kotlin -----

/** host 페이지가 native로 post하는 discriminated message envelope. */
@Serializable
internal data class GuestMessage(
    val event: GuestEvent,
    val payload: GuestPayload? = null,
)

@Serializable
internal enum class GuestEvent {
    /** first render 완료 — 이제 requestPayment 해도 안전하다. */
    @SerialName("ready") READY,

    @SerialName("failed") FAILED,

    @SerialName("height") HEIGHT,

    @SerialName("agreement") AGREEMENT,

    @SerialName("methodChange") METHOD_CHANGE,

    /**
     * 결제 성공. [Bridge.SUCCESS_URL] redirect를 가로채 NATIVE 쪽에서 생성한다
     * (JS가 아님 — mobile requestPayment Promise는 paymentKey로 resolve되지 않는다).
     */
    @SerialName("paymentSuccess") PAYMENT_SUCCESS,

    /**
     * 결제 실패. [Bridge.FAIL_URL] redirect로 native가 생성하거나, redirect 전
     * 동기 validation / 사용자 중단 오류는 JS가 생성한다.
     */
    @SerialName("paymentFail") PAYMENT_FAIL,
}

@Serializable
internal data class GuestPayload(
    // height 이벤트
    val height: Double? = null,
    // agreement 이벤트
    val agreedRequiredTerms: Boolean? = null,
    // methodChange 이벤트
    val methodType: String? = null,
    val method: String? = null,
    val easyPay: String? = null,
    // paymentSuccess 이벤트
    val paymentKey: String? = null,
    val orderId: String? = null,
    val amount: Long? = null,
    val additionalParameters: Map<String, String>? = null,
    // paymentFail 이벤트
    val code: String? = null,
    val message: String? = null,
)

/**
 * requestPayment redirect([Bridge.SUCCESS_URL] / [Bridge.FAIL_URL])를 [GuestMessage]로 파싱한다.
 * 두 플랫폼 interceptor가 공유하며, sentinel이 아니면 null을 반환한다.
 * query 파싱을 직접 구현해 commonMain에 URL 라이브러리를 끌어오지 않는다.
 */
internal object RedirectParser {
    fun parse(url: String): GuestMessage? {
        val isSuccess = matchesSentinel(url, Bridge.SUCCESS_URL)
        val isFail = matchesSentinel(url, Bridge.FAIL_URL)
        if (!isSuccess && !isFail) return null

        val query = url.substringAfter('?', missingDelimiterValue = "")
        val params = parseQuery(query)
        return if (isSuccess) {
            GuestMessage(
                event = GuestEvent.PAYMENT_SUCCESS,
                payload = GuestPayload(
                    paymentKey = params["paymentKey"],
                    orderId = params["orderId"],
                    amount = params["amount"]?.toLongOrNull(),
                ),
            )
        } else {
            GuestMessage(
                event = GuestEvent.PAYMENT_FAIL,
                payload = GuestPayload(
                    code = params["code"] ?: "UNKNOWN",
                    message = params["message"].orEmpty(),
                    orderId = params["orderId"],
                ),
            )
        }
    }

    /** 정확한 경로 매칭: sentinel 뒤엔 끝/'?'/'#'만 허용 — `.../success-summary` 같은 prefix 일치 차단. */
    private fun matchesSentinel(url: String, sentinel: String): Boolean {
        if (!url.startsWith(sentinel)) return false
        val next = url.getOrNull(sentinel.length) ?: return true
        return next == '?' || next == '#'
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i < 0) null else decode(pair.substring(0, i)) to decode(pair.substring(i + 1))
            }
            .toMap()

    /**
     * Percent-decode. raw byte를 모아 디코딩해야 multi-byte UTF-8(예: 한글)이 살아남는다 —
     * 각 %XX byte를 Char로 그대로 붙이면 깨진다.
     */
    private fun decode(s: String): String {
        if ('%' !in s && '+' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { bytes.add(' '.code.toByte()); i++ }
                c == '%' && i + 3 <= s.length -> {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) { bytes.add(hex.toByte()); i += 3 } else { bytes.add(c.code.toByte()); i++ }
                }
                else -> { bytes.add(c.code.toByte()); i++ }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}

// ----- Kotlin → JS -----

/**
 * 컨트롤러가 host 페이지에 발행하는 command들. [toJs]가 `window.__toss` 호출로 렌더링하며,
 * 인자는 JSON으로 인코딩해 문자열/payload가 안전하게 escape된다.
 */
internal sealed interface HostCommand {
    fun toJs(): String

    data class Init(val config: PaymentWidgetConfig) : HostCommand {
        override fun toJs(): String {
            val args = Bridge.json.encodeToString(
                InitArgs.serializer(),
                InitArgs(config.clientKey.value, config.customerKey.value, config.brandPayRedirectUrl),
            )
            return "window.${Bridge.JS_NAMESPACE}.init($args)"
        }
    }

    data class RenderPaymentMethods(val amount: PaymentAmount, val options: RenderOptions) : HostCommand {
        override fun toJs(): String {
            val args = Bridge.json.encodeToString(
                RenderArgs.serializer(),
                RenderArgs(amount.value, amount.currency.name, amount.country, options.variantKey),
            )
            return "window.${Bridge.JS_NAMESPACE}.renderPaymentMethods($args)"
        }
    }

    data object RenderAgreement : HostCommand {
        override fun toJs(): String = "window.${Bridge.JS_NAMESPACE}.renderAgreement()"
    }

    data class UpdateAmount(val amount: PaymentAmount, val description: String?) : HostCommand {
        override fun toJs(): String {
            // setAmount은 {currency, value}를 요구한다 — value만이 아니라 currency도 함께 실어야 한다.
            val args = Bridge.json.encodeToString(
                UpdateAmountArgs.serializer(),
                UpdateAmountArgs(amount.value, amount.currency.name, description),
            )
            return "window.${Bridge.JS_NAMESPACE}.updateAmount($args)"
        }
    }

    data class RequestPayment(val order: PaymentOrder) : HostCommand {
        override fun toJs(): String {
            // successUrl/failUrl은 native host가 가로채는 sentinel이다.
            val args = Bridge.json.encodeToString(
                RequestPaymentArgs.serializer(),
                RequestPaymentArgs(
                    orderId = order.orderId,
                    orderName = order.orderName,
                    successUrl = Bridge.SUCCESS_URL,
                    failUrl = Bridge.FAIL_URL,
                    customerEmail = order.customerEmail,
                    customerName = order.customerName,
                    customerMobilePhone = order.customerMobilePhone,
                ),
            )
            return "window.${Bridge.JS_NAMESPACE}.requestPayment($args)"
        }
    }
}

@Serializable
private data class InitArgs(
    val clientKey: String,
    val customerKey: String,
    val brandPayRedirectUrl: String? = null,
)

@Serializable
private data class RenderArgs(
    val value: Long,
    val currency: String,
    val country: String,
    val variantKey: String? = null,
)

@Serializable
private data class UpdateAmountArgs(
    val value: Long,
    val currency: String,
    val description: String? = null,
)

@Serializable
private data class RequestPaymentArgs(
    val orderId: String,
    val orderName: String,
    val successUrl: String,
    val failUrl: String,
    val customerEmail: String? = null,
    val customerName: String? = null,
    val customerMobilePhone: String? = null,
)
