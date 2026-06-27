package io.tosspayments.kmp

/**
 * Outcome of [TossPaymentWidget.requestPayment].
 *
 * IMPORTANT: [Success] is NOT proof the payment is settled. It only means the JS SDK
 * produced a paymentKey. The merchant server MUST call the TossPayments
 * `/v1/payments/confirm` API with paymentKey + orderId + amount before fulfilling the
 * order. The in-process result is advisory on every integration approach.
 */
sealed interface PaymentResult {
    data class Success(
        val paymentKey: String,
        val orderId: String,
        val amount: Long,
        /** Extra fields the JS SDK returns, e.g. additionalParameters["paymentType"] = "BRANDPAY". */
        val additionalParameters: Map<String, String> = emptyMap(),
    ) : PaymentResult

    data class Failure(val error: TossPaymentError) : PaymentResult
}

/**
 * Readiness of the widget. The JS SDK renders asynchronously; calling requestPayment
 * before [READY] is rejected (the native SDKs throw — we surface it as a typed error /
 * a gate the caller can observe). Drive the pay button's enabled state from this.
 */
enum class WidgetStatus { LOADING, READY, FAILED }
