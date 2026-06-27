package io.github.l2hyunwoo.tosspayments

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Imperative handle to a payment widget instance, obtained from [rememberTossPaymentWidget]
 * and mounted by [TossPaymentWidgetSurface]. Drives the caller's pay button.
 *
 * Holds the [PlatformWebViewController] and owns the readiness/agreement/selection state
 * derived from inbound [GuestMessage]s. All state is observable as [StateFlow] so Compose
 * recomposes correctly; mutations are expected on the main thread (the controller marshals
 * platform callbacks there).
 */
@Stable
class TossPaymentWidget internal constructor(
    internal val controller: PlatformWebViewController,
    private val config: PaymentWidgetConfig,
    private val amount: PaymentAmount,
    private val renderOptions: RenderOptions,
) {
    private val _status = MutableStateFlow(WidgetStatus.LOADING)
    val status: StateFlow<WidgetStatus> = _status.asStateFlow()

    private val _agreedRequiredTerms = MutableStateFlow(false)
    val agreedRequiredTerms: StateFlow<Boolean> = _agreedRequiredTerms.asStateFlow()

    private val _selectedMethod = MutableStateFlow<SelectedPaymentMethod?>(null)
    val selectedMethod: StateFlow<SelectedPaymentMethod?> = _selectedMethod.asStateFlow()

    /** JS-driven content height in px (0 until first render). The surface sizes itself to this. */
    private val _heightPx = MutableStateFlow(0)
    val heightPx: StateFlow<Int> = _heightPx.asStateFlow()

    /** Set while a requestPayment call is awaiting its result. */
    private var pending: CompletableDeferred<PaymentResult>? = null

    /** One-shot guard so the deprecated split shims don't double-mount the single WebView. */
    private var primaryClaimed = false

    internal fun start() {
        controller.mount(
            onMessage = { raw -> handleMessage(raw) },
            onStatus = { s -> _status.value = s },
            onPageReady = {
                // The page (and the synchronous JS SDK script tag) is loaded; only now is the
                // JS surface evaluate-able. Create the session, then render methods+agreement
                // (the page folds the agreement into renderPaymentMethods — one JS session).
                controller.evaluate(HostCommand.Init(config).toJs())
                controller.evaluate(HostCommand.RenderPaymentMethods(amount, renderOptions).toJs())
            },
        )
    }

    /** Claimed by the first composable that mounts the surface; later shims become no-ops. */
    internal fun claimPrimarySurface(): Boolean {
        if (primaryClaimed) return false
        primaryClaimed = true
        return true
    }

    internal fun dispose() {
        pending?.takeIf { !it.isCompleted }?.complete(
            PaymentResult.Failure(
                TossPaymentError.Unknown("DISPOSED", "Widget disposed before payment resolved"),
            ),
        )
        pending = null
        primaryClaimed = false
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
        // All `pending` access is confined to the main dispatcher so it can't race with the
        // result delivery (which arrives via the controller's main scope). The caller may invoke
        // this from any context; we hop to Main for the mutation, then await off the critical section.
        val deferred = withContext(Dispatchers.Main.immediate) {
            when {
                _status.value != WidgetStatus.READY -> failure(
                    "Widget is not ready (status=${_status.value}); render must finish before requestPayment.",
                    order.orderId,
                )
                pending?.takeIf { !it.isCompleted } != null -> failure(
                    "A payment request is already in progress.",
                    order.orderId,
                )
                else -> CompletableDeferred<PaymentResult>().also {
                    pending = it
                    controller.evaluate(HostCommand.RequestPayment(order).toJs())
                }
            }
        }
        return deferred.await()
    }

    private fun failure(message: String, orderId: String?): CompletableDeferred<PaymentResult> =
        CompletableDeferred(
            PaymentResult.Failure(TossPaymentError.Configuration("NO_ACTIVE_PAYMENT_REQUEST", message, orderId)),
        )

    private fun handleMessage(raw: String) {
        val msg = runCatching { Bridge.json.decodeFromString(GuestMessage.serializer(), raw) }.getOrNull()
            ?: return
        val p = msg.payload
        when (msg.event) {
            GuestEvent.READY -> _status.value = WidgetStatus.READY
            GuestEvent.FAILED -> _status.value = WidgetStatus.FAILED
            GuestEvent.HEIGHT -> p?.height?.let { _heightPx.value = it.toInt() }
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
