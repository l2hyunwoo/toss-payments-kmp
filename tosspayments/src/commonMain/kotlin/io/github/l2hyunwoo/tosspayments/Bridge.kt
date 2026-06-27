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

    /** requestPayment resolved successfully. */
    @SerialName("paymentSuccess") PAYMENT_SUCCESS,

    /** requestPayment rejected. */
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
            val args = Bridge.json.encodeToString(
                UpdateAmountArgs.serializer(),
                UpdateAmountArgs(amount.value, description),
            )
            return "window.${Bridge.JS_NAMESPACE}.updateAmount($args)"
        }
    }

    data class RequestPayment(val order: PaymentOrder) : HostCommand {
        override fun toJs(): String {
            val args = Bridge.json.encodeToString(
                RequestPaymentArgs.serializer(),
                RequestPaymentArgs(
                    order.orderId, order.orderName, order.customerEmail,
                    order.customerName, order.taxFreeAmount,
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
    val description: String? = null,
)

@Serializable
private data class RequestPaymentArgs(
    val orderId: String,
    val orderName: String,
    val customerEmail: String? = null,
    val customerName: String? = null,
    val taxFreeAmount: Long? = null,
)
