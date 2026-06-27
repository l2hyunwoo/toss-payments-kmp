package io.github.l2hyunwoo.tosspayments

import kotlin.jvm.JvmInline

/**
 * Merchant client key (test_ck_… / live_ck_…). Public by design — safe to embed in the
 * client. The authoritative secret key never leaves the merchant server.
 */
@JvmInline
value class ClientKey(val value: String)

/**
 * Identifies the buyer for the payment-widget / billing flows. Use [ANONYMOUS] for
 * one-off guest payments where no buyer identity is tracked.
 */
@JvmInline
value class CustomerKey(val value: String) {
    companion object {
        val ANONYMOUS: CustomerKey = CustomerKey("ANONYMOUS")
    }
}

/** Currencies the TossPayments JS SDK accepts. KRW amounts are whole won. */
enum class Currency { KRW, AUD, EUR, GBP, HKD, JPY, SGD, USD }

/**
 * The amount is bound at RENDER time on both official SDKs (and on the JS SDK), then
 * mutated via [TossPaymentWidget.updateAmount]; orderId/orderName arrive only at
 * requestPayment time — see [PaymentOrder]. Keeping the two phases separate mirrors the
 * SDK contract and avoids an API that fits neither platform.
 */
data class PaymentAmount(
    val value: Long,
    val currency: Currency = Currency.KRW,
    /** ISO-3166-1 alpha-2; defaults to KR. */
    val country: String = "KR",
)

/**
 * Order details supplied at requestPayment time.
 *
 * @param orderId merchant-unique, 6–64 chars of [a-zA-Z0-9-_=].
 * @param orderName shown to the buyer, ≤ 100 chars.
 */
data class PaymentOrder(
    val orderId: String,
    val orderName: String,
    val customerEmail: String? = null,
    val customerName: String? = null,
    val customerMobilePhone: String? = null,
)

/** Options applied when rendering the payment-method widget. */
data class RenderOptions(
    /** A/B-test or UI variant configured in the merchant console. */
    val variantKey: String? = null,
)

/**
 * Everything needed to construct the widget plus the per-platform host requirements the
 * integrator must satisfy. The library cannot register URL schemes on the app's behalf —
 * see README for the required Info.plist / AndroidManifest entries.
 */
data class PaymentWidgetConfig(
    val clientKey: ClientKey,
    val customerKey: CustomerKey,
    /**
     * iOS app-to-app return scheme declared in the host app's CFBundleURLTypes
     * (e.g. "myapp"). Required for easy-pay / bank-app round-trips on iOS. Android's
     * intent return is automatic and needs no value here.
     */
    val appScheme: String? = null,
    /** BrandPay only. */
    val brandPayRedirectUrl: String? = null,
    /** Pinned JS SDK endpoint. Default = official v2 standard. */
    val jsSdkUrl: String = DEFAULT_JS_SDK_URL,
) {
    companion object {
        const val DEFAULT_JS_SDK_URL: String = "https://js.tosspayments.com/v2/standard"
    }
}

/** The payment method currently highlighted in the widget. */
data class SelectedPaymentMethod(
    val type: String,
    val method: String,
    val easyPay: String? = null,
)
