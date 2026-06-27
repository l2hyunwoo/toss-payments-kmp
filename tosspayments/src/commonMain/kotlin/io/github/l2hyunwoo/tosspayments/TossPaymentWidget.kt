package io.github.l2hyunwoo.tosspayments

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 결제 위젯 인스턴스에 대한 명령형 핸들. [rememberTossPaymentWidget]로 얻고
 * [TossPaymentWidgetSurface]가 mount한다. 호출자의 결제 버튼을 구동한다.
 *
 * [PlatformWebViewController]를 보유하고, 들어오는 [GuestMessage]에서 파생된
 * readiness/agreement/selection 상태를 소유한다. 모든 상태는 [StateFlow]로 관찰 가능해
 * Compose가 올바르게 recompose하며, 변경은 main thread에서 일어나야 한다(controller가
 * platform 콜백을 그쪽으로 marshal한다).
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

    /** JS가 구동하는 콘텐츠 높이(px, 첫 render 전까지는 0). surface가 이 값에 맞춰 크기를 잡는다. */
    private val _heightPx = MutableStateFlow(0)
    val heightPx: StateFlow<Int> = _heightPx.asStateFlow()

    /** requestPayment 호출이 결과를 기다리는 동안 설정된다. */
    private var pending: CompletableDeferred<PaymentResult>? = null

    /** deprecated split shim들이 단일 WebView를 이중으로 mount하지 않도록 하는 one-shot 가드. */
    private var primaryClaimed = false

    internal fun start() {
        controller.mount(
            onMessage = { raw -> handleMessage(raw) },
            onStatus = { s -> _status.value = s },
            onPageReady = {
                // 페이지(와 동기 JS SDK script 태그)가 로드됐다. 이제서야 JS surface를
                // evaluate할 수 있다. 세션을 만든 뒤 methods+agreement를 render한다
                // (페이지가 agreement를 renderPaymentMethods에 접어 넣는다 — 하나의 JS 세션).
                controller.evaluate(HostCommand.Init(config).toJs())
                controller.evaluate(HostCommand.RenderPaymentMethods(amount, renderOptions).toJs())
            },
        )
    }

    /** surface를 mount하는 첫 composable이 claim한다. 이후 shim들은 no-op이 된다. */
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

    /** render된 금액을 갱신한다(예: coupon 적용 후). [WidgetStatus.READY] 이후 안전하다. */
    fun updateAmount(amount: PaymentAmount, description: String? = null) {
        controller.evaluate(HostCommand.UpdateAmount(amount, description).toJs())
    }

    fun getSelectedPaymentMethod(): SelectedPaymentMethod? = _selectedMethod.value

    /**
     * 선택된 method로 결제를 트리거한다. JS bridge가 성공 또는 실패로 resolve할 때까지
     * suspend한다. 위젯이 [WidgetStatus.READY]가 되기 전에 호출하면 throw하지 않고
     * [TossPaymentError.Configuration]으로 곧바로 실패한다 — SDK의 async render gate와 맞춘다.
     *
     * NOTE: [PaymentResult.UnverifiedSuccess]는 확정된 결제가 아니다. 이행 전에 server-side에서
     * 확인하라(paymentKey + orderId + amount → /v1/payments/confirm).
     */
    suspend fun requestPayment(order: PaymentOrder): PaymentResult {
        // `pending` 접근은 모두 main dispatcher에 가둬, 결과 전달(controller의 main scope로 도착)과
        // race하지 않게 한다. 호출자는 어떤 context에서든 호출할 수 있다. 변경을 위해 Main으로
        // 점프한 뒤 critical section 밖에서 await한다.
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
                PaymentResult.UnverifiedSuccess(
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
