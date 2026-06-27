package io.tosspayments.kmp

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Imperative handle to a payment widget instance, obtained from [rememberTossPaymentWidget]
 * and shared between [TossPaymentMethods], [TossPaymentAgreement], and the caller's pay button.
 *
 * Holds the [PlatformWebViewController] and owns the readiness/agreement/selection state
 * derived from inbound [GuestMessage]s. All state is observable as [StateFlow] so Compose
 * recomposes correctly; mutations are expected on the main thread (the controller marshals
 * platform callbacks there).
 */
@Stable
class TossPaymentWidget internal constructor(
    private val controller: PlatformWebViewController,
    private val amount: PaymentAmount,
    private val renderOptions: RenderOptions,
) {
    private val _status = MutableStateFlow(WidgetStatus.LOADING)
    val status: StateFlow<WidgetStatus> = _status.asStateFlow()

    private val _agreedRequiredTerms = MutableStateFlow(false)
    val agreedRequiredTerms: StateFlow<Boolean> = _agreedRequiredTerms.asStateFlow()

    private val _selectedMethod = MutableStateFlow<SelectedPaymentMethod?>(null)
    val selectedMethod: StateFlow<SelectedPaymentMethod?> = _selectedMethod.asStateFlow()

    /** Set while a requestPayment call is awaiting its bridge resolution. */
    private var pending: CompletableDeferred<PaymentResult>? = null

    internal fun start() {
        controller.mount(
            onMessage = { raw -> handleMessage(raw) },
            onStatus = { s -> _status.value = s },
        )
        controller.evaluate(HostCommand.RenderPaymentMethods(amount, renderOptions).toJs())
        controller.evaluate(HostCommand.RenderAgreement.toJs())
    }

    internal fun dispose() {
        pending?.takeIf { !it.isCompleted }?.complete(
            PaymentResult.Failure(
                TossPaymentError.Unknown("DISPOSED", "Widget disposed before payment resolved"),
            ),
        )
        pending = null
        controller.dispose()
    }

    /** Updates the rendered amount (e.g. after applying a coupon). Safe after [WidgetStatus.READY]. */
    fun updateAmount(amount: PaymentAmount, description: String? = null) {
        controller.evaluate(HostCommand.UpdateAmount(amount, description).toJs())
    }

    fun getSelectedPaymentMethod(): SelectedPaymentMethod? = _selectedMethod.value

    /**
     * Triggers payment for the selected method. Suspends until the JS bridge resolves with a
     * success or failure. Calling before the widget is [WidgetStatus.READY] fails fast with a
     * [TossPaymentError.Configuration] rather than throwing — matching the SDK's async render gate.
     *
     * NOTE: [PaymentResult.Success] is not a settled payment. Confirm server-side
     * (paymentKey + orderId + amount → /v1/payments/confirm) before fulfilling.
     */
    suspend fun requestPayment(order: PaymentOrder): PaymentResult {
        if (_status.value != WidgetStatus.READY) {
            return PaymentResult.Failure(
                TossPaymentError.Configuration(
                    "NO_ACTIVE_PAYMENT_REQUEST",
                    "Widget is not ready (status=${_status.value}); render must finish before requestPayment.",
                    order.orderId,
                ),
            )
        }
        pending?.takeIf { !it.isCompleted }?.let {
            return PaymentResult.Failure(
                TossPaymentError.Configuration(
                    "NO_ACTIVE_PAYMENT_REQUEST",
                    "A payment request is already in progress.",
                    order.orderId,
                ),
            )
        }
        val deferred = CompletableDeferred<PaymentResult>()
        pending = deferred
        controller.evaluate(HostCommand.RequestPayment(order).toJs())
        return deferred.await()
    }

    private fun handleMessage(raw: String) {
        val msg = runCatching { Bridge.json.decodeFromString(GuestMessage.serializer(), raw) }.getOrNull()
            ?: return
        val p = msg.payload
        when (msg.event) {
            GuestEvent.READY -> _status.value = WidgetStatus.READY
            GuestEvent.FAILED -> _status.value = WidgetStatus.FAILED
            GuestEvent.HEIGHT -> { /* height is consumed by the platform host for resizing */ }
            GuestEvent.AGREEMENT -> _agreedRequiredTerms.value = p?.agreedRequiredTerms ?: false
            GuestEvent.METHOD_CHANGE -> _selectedMethod.value = p?.methodType?.let {
                SelectedPaymentMethod(it, p.method ?: it, p.easyPay)
            }
            GuestEvent.PAYMENT_SUCCESS -> resolvePending(
                PaymentResult.Success(
                    paymentKey = p?.paymentKey.orEmpty(),
                    orderId = p?.orderId.orEmpty(),
                    amount = p?.amount ?: 0L,
                    additionalParameters = p?.additionalParameters ?: emptyMap(),
                ),
            )
            GuestEvent.PAYMENT_FAIL -> resolvePending(
                PaymentResult.Failure(
                    TossPaymentError.from(
                        code = p?.code ?: "UNKNOWN",
                        message = p?.message.orEmpty(),
                        orderId = p?.orderId,
                    ),
                ),
            )
        }
    }

    private fun resolvePending(result: PaymentResult) {
        pending?.takeIf { !it.isCompleted }?.complete(result)
        pending = null
    }
}
