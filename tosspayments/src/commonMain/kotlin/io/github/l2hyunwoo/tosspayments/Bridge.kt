package io.github.l2hyunwoo.tosspayments

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire protocol between the bundled HTML page (which hosts the TossPayments JS SDK)
 * and the Kotlin controller.
 *
 * Kotlin → JS: the controller calls [PlatformWebViewController.evaluate] with a JS
 * expression built from [HostCommand].toJs(); the page exposes `window.__toss` functions.
 *
 * JS → Kotlin: the page posts a JSON string back over the platform bridge
 * (Android @JavascriptInterface, iOS WKScriptMessageHandler); the controller decodes it
 * into a [GuestMessage].
 *
 * Both legs are common Kotlin — only the transport (evaluate / receive) is per-platform.
 */
internal object Bridge {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Name of the JS namespace the host page must expose (window.__toss.*). */
    const val JS_NAMESPACE: String = "__toss"

    /** Name the native side registers its inbound channel under. */
    const val NATIVE_CHANNEL: String = "tossNative"

    /**
     * Sentinel origin the host HTML is loaded under (baseURL). It MUST match the origin of
     * [SUCCESS_URL] / [FAIL_URL] so the redirect navigation stays same-origin and reaches the
     * platform interceptor (Android shouldOverrideUrlLoading / iOS decidePolicyForNavigationAction).
     * The page is never actually fetched from this host — it is loaded from an in-memory string.
     */
    const val BASE_URL: String = "https://tosspayments.local/"

    /**
     * requestPayment redirects here on success (the Promise path is unavailable on mobile).
     * The native host cancels the load and parses paymentKey/orderId/amount from the query.
     */
    const val SUCCESS_URL: String = "https://tosspayments.local/widget/success"

    /** requestPayment redirects here on failure; native parses code/message/orderId from the query. */
    const val FAIL_URL: String = "https://tosspayments.local/widget/fail"

    /** Placeholder in the bundled HTML; native replaces it with [PaymentWidgetConfig.jsSdkUrl]. */
    const val JS_SDK_URL_TOKEN: String = "__TOSS_JS_SDK_URL__"
}

// ----- JS → Kotlin -----

/** Discriminated message envelope the host page posts to native. */
@Serializable
internal data class GuestMessage(
    val event: GuestEvent,
    val payload: GuestPayload? = null,
)

@Serializable
internal enum class GuestEvent {
    /** Page loaded and the widget finished its first render — safe to requestPayment. */
    @SerialName("ready") READY,

    /** First render or SDK init failed. */
    @SerialName("failed") FAILED,

    /** JS-driven height change for the inline widget; native resizes the WebView. */
    @SerialName("height") HEIGHT,

    /** Required-terms agreement checkbox toggled. */
    @SerialName("agreement") AGREEMENT,

    /** Selected payment method changed. */
    @SerialName("methodChange") METHOD_CHANGE,

    /**
     * Payment succeeded. Produced NATIVE-SIDE by intercepting the redirect to [Bridge.SUCCESS_URL]
     * (not by JS — the requestPayment Promise does not resolve with a paymentKey on mobile).
     */
    @SerialName("paymentSuccess") PAYMENT_SUCCESS,

    /**
     * Payment failed. Produced native-side from a redirect to [Bridge.FAIL_URL], OR by JS for a
     * synchronous validation / user-abort error before any redirect happens.
     */
    @SerialName("paymentFail") PAYMENT_FAIL,
}

@Serializable
internal data class GuestPayload(
    // height
    val height: Double? = null,
    // agreement
    val agreedRequiredTerms: Boolean? = null,
    // methodChange
    val methodType: String? = null,
    val method: String? = null,
    val easyPay: String? = null,
    // paymentSuccess
    val paymentKey: String? = null,
    val orderId: String? = null,
    val amount: Long? = null,
    val additionalParameters: Map<String, String>? = null,
    // paymentFail
    val code: String? = null,
    val message: String? = null,
)

/**
 * Parses a requestPayment redirect (to [Bridge.SUCCESS_URL] / [Bridge.FAIL_URL]) into a
 * [GuestMessage], shared by both platform interceptors so the query-parsing logic is written
 * and tested once. Returns null if the URL is not one of our sentinels.
 *
 * Hand-rolled query parsing avoids pulling a URL library into commonMain and works identically
 * on every target. Values are percent-decoded.
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

    /** Exact-path match: the sentinel must be followed by end-of-string, '?', or '#' — not an
     *  arbitrary same-prefix path like `.../success-summary`. */
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
     * Percent-decode (+ → space, %XX → byte). Accumulates raw bytes so multi-byte UTF-8 sequences
     * (e.g. Korean) decode correctly — appending each %XX byte as a Char would mangle them.
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
 * Commands the controller issues to the host page. [toJs] renders the JS call against the
 * `window.__toss` namespace; arguments are JSON-encoded so strings/payloads are escaped safely.
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
            // setAmount requires {currency, value} — currency must be carried, not just the value.
            val args = Bridge.json.encodeToString(
                UpdateAmountArgs.serializer(),
                UpdateAmountArgs(amount.value, amount.currency.name, description),
            )
            return "window.${Bridge.JS_NAMESPACE}.updateAmount($args)"
        }
    }

    data class RequestPayment(val order: PaymentOrder) : HostCommand {
        override fun toJs(): String {
            // successUrl/failUrl are the sentinels the native host intercepts; orderId/orderName
            // plus optional customer fields are the verified v2 widget requestPayment params.
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
