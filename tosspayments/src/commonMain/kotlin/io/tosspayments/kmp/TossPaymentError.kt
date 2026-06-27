package io.tosspayments.kmp

/**
 * Unified error model across the JS / Android / iOS SDKs.
 *
 * Verified design constraints (docs.tosspayments.com/sdk/v2/error-codes + both SDK repos):
 *  - The error CODE string is a server-originated passthrough, identical on every platform.
 *    Key on the code, never on the message.
 *  - The MESSAGE varies by platform for the same code (iOS uses a different cancel message
 *    than the web), so it is informational only.
 *  - The set is open: SDKs pass arbitrary/new/platform-internal codes through (e.g. iOS's
 *    internal "102" WKWebView frame-load interruption). Unmapped codes degrade to [Unknown]
 *    rather than crashing an exhaustive `when`.
 *  - Both cancel channels — USER_CANCEL (promise) and PAY_PROCESS_CANCELED (failUrl) — unify
 *    into [UserCanceled] so callers test one case.
 *
 * [code] is always retained verbatim for diagnostics even when the variant is [Unknown].
 */
sealed class TossPaymentError(
    open val code: String,
    open val message: String,
    open val orderId: String? = null,
) {
    /** USER_CANCEL | PAY_PROCESS_CANCELED — the buyer backed out; not a real failure. */
    data class UserCanceled(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** Developer/configuration error — fix in code (bad key, malformed amount, bad URL…). */
    data class Configuration(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** Needs a user action to proceed (terms not agreed, method not selected, password…). */
    data class UserFlow(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** NETWORK_ERROR — transient, generally retryable. */
    data class Network(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** Provider/issuer/process failure — a genuine payment failure. */
    data class ProviderFailure(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** Card-state problem — stopped, lost/stolen, or needs re-registration. */
    data class CardState(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** Documented UNKNOWN, any unmapped code, or any platform-internal code. */
    data class Unknown(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    companion object {
        /** Normalize a raw (code, message) pair from any platform into a typed error. */
        fun from(code: String, message: String, orderId: String? = null): TossPaymentError =
            when (code) {
                "USER_CANCEL", "PAY_PROCESS_CANCELED" -> UserCanceled(code, message, orderId)
                "NETWORK_ERROR" -> Network(code, message, orderId)
                in CONFIG_CODES -> Configuration(code, message, orderId)
                in USER_FLOW_CODES -> UserFlow(code, message, orderId)
                in PROVIDER_CODES -> ProviderFailure(code, message, orderId)
                in CARD_STATE_CODES -> CardState(code, message, orderId)
                else -> Unknown(code, message, orderId) // includes "UNKNOWN", iOS "102", future codes
            }

        private val CONFIG_CODES = setOf(
            "INVALID_CLIENT_KEY", "INVALID_CUSTOMER_KEY", "INVALID_PARAMETER", "INVALID_PARAMETERS",
            "INVALID_AMOUNT_VALUE", "INVALID_AMOUNT_CURRENCY", "BELOW_ZERO_AMOUNT", "NOT_SETUP_AMOUNT",
            "INVALID_SELECTOR", "INVALID_VARIANT_KEY", "INCORRECT_SUCCESS_URL_FORMAT",
            "INCORRECT_FAIL_URL_FORMAT", "INSECURE_KEY_USAGE", "V1_METHOD_NOT_SUPPORTED",
            "NOT_SUPPORTED_PROMISE", "INVALID_METADATA", "NOT_SUPPORTED_WIDGET_KEY",
            "NOT_REGISTERED_REDIRECT_URL", "NO_ACTIVE_PAYMENT_REQUEST", "INVALID_METHOD_TRANSACTION",
            "NOT_SUPPORTED_API_INDIVIDUAL_KEY", "NOT_SUPPORTED_METHOD",
        )
        private val USER_FLOW_CODES = setOf(
            "NEED_AGREEMENT_WITH_REQUIRED_TERMS", "NEED_AGREEMENT_WITH_TERMS", "NEED_CARD_PAYMENT_DETAIL",
            "NEED_REFUND_ACCOUNT_DETAIL", "NOT_SELECTED_PAYMENT_METHOD", "NEED_MERCHANT_ONE_TOUCH_SETTING",
            "INVALID_PASSWORD",
        )
        private val PROVIDER_CODES = setOf(
            "PAY_PROCESS_ABORTED", "REJECT_CARD_COMPANY", "PROVIDER_STATUS_UNHEALTHY", "FAILED_CARD_COMPANY",
            "PAYMENT_REQUEST_ABORTED", "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "UNKNOWN_PAYMENT_ERROR",
            "COMMON_ERROR",
        )
        private val CARD_STATE_CODES = setOf(
            "INVALID_STOPPED_CARD", "INVALID_CARD_LOST_OR_STOLEN", "INVALID_CARD_INFO_RE_REGISTER",
        )
    }
}
