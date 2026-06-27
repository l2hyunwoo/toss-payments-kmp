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
 * [TossPaymentWidgetSurface]가 mount한다.
 *
 * 상태 변경은 main thread에서 일어나야 한다(controller가 platform 콜백을 그쪽으로 marshal한다).
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

    /** JS가 구동하는 콘텐츠 높이(px, 첫 render 전까지는 0). */
    private val _heightPx = MutableStateFlow(0)
    val heightPx: StateFlow<Int> = _heightPx.asStateFlow()

    private var pending: CompletableDeferred<PaymentResult>? = null

    /** deprecated split shim들이 단일 WebView를 이중으로 mount하지 않도록 하는 one-shot 가드. */
    private var primaryClaimed = false

    internal fun start() {
        controller.mount(
            onMessage = { raw -> handleMessage(raw) },
            onStatus = { s -> _status.value = s },
            onPageReady = {
                // 페이지(+동기 JS SDK script)가 로드된 뒤에야 JS를 evaluate할 수 있다.
                // agreement는 renderPaymentMethods 안에 접혀 있다 — 하나의 JS 세션.
                controller.evaluate(HostCommand.Init(config).toJs())
                controller.evaluate(HostCommand.RenderPaymentMethods(amount, renderOptions).toJs())
            },
        )
    }

    /** 첫 composable만 claim 성공, 이후 shim은 false. */
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
     * 선택된 method로 결제를 트리거하고 JS bridge가 resolve할 때까지 suspend한다.
     * [WidgetStatus.READY] 이전 호출은 throw하지 않고 [TossPaymentError.Configuration]으로 실패한다.
     *
     * NOTE: [PaymentResult.UnverifiedSuccess]는 확정 결제가 아니다 — 이행 전 server-side 확인 필수
     * (paymentKey + orderId + amount → /v1/payments/confirm).
     */
    suspend fun requestPayment(order: PaymentOrder): PaymentResult {
        // `pending` 접근을 모두 Main에 가둬 결과 전달(controller의 main scope)과 race하지 않게 한다.
        // await는 critical section 밖에서.
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
