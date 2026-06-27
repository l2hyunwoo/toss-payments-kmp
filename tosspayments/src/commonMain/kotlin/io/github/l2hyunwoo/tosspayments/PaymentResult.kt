package io.github.l2hyunwoo.tosspayments

/**
 * [TossPaymentWidget.requestPayment]의 결과.
 *
 * 중요: [Success]는 결제가 완료되었다는 증거가 아니다. JS SDK가 paymentKey를
 * 만들어냈다는 의미일 뿐이다. 가맹점 서버는 주문을 처리하기 전에 반드시
 * paymentKey + orderId + amount로 TossPayments `/v1/payments/confirm` API를
 * 호출해야 한다. in-process 결과는 어떤 연동 방식에서든 참고용일 뿐이다.
 */
sealed interface PaymentResult {
    data class Success(
        val paymentKey: String,
        val orderId: String,
        val amount: Long,
        /** JS SDK가 반환하는 추가 필드. 예: additionalParameters["paymentType"] = "BRANDPAY". */
        val additionalParameters: Map<String, String> = emptyMap(),
    ) : PaymentResult

    data class Failure(val error: TossPaymentError) : PaymentResult
}

/**
 * 위젯의 준비 상태. JS SDK는 비동기로 렌더링하므로, [READY] 이전에 requestPayment를
 * 호출하면 거부된다(네이티브 SDK는 throw하고 — 우리는 이를 타입이 있는 에러 /
 * 호출자가 관찰할 수 있는 게이트로 노출한다). 결제 버튼의 enabled 상태를 이 값으로 제어한다.
 */
enum class WidgetStatus { LOADING, READY, FAILED }
