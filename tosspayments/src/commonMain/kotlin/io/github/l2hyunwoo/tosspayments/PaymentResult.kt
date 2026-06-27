package io.github.l2hyunwoo.tosspayments

/**
 * [TossPaymentWidget.requestPayment]의 결과.
 *
 * 결제 라이브러리에서 가장 중요한 보안 불변식: 클라이언트가 받는 "성공"은 절대 최종이 아니다.
 * 결과는 WebView가 sentinel URL로 redirect한 query에서 읽으므로 WebView 안의 스크립트가 위조할 수
 * 있다. 진짜 권위는 가맹점 서버의 `/v1/payments/confirm` 호출뿐이다. 그래서 성공 케이스 이름을
 * [UnverifiedSuccess]로 두어 "아직 검증 안 됨"을 타입에 실었다 — 연동 개발자가 이걸 곧바로 "결제
 * 완료"로 처리하지 못하게 한다.
 */
sealed interface PaymentResult {
    /**
     * JS SDK가 paymentKey를 만들어냈다. **결제가 완료된 것이 아니다.**
     *
     * 반드시 가맹점 서버가 [paymentKey] + [orderId] + [amount]로 TossPayments
     * `/v1/payments/confirm` API를 호출해 승인을 확정한 뒤에야 주문을 처리해야 한다. amount는
     * 신뢰할 수 없는 redirect query에서 온 값이므로, 서버는 자신이 만든 주문 금액과 반드시 대조해야
     * 한다(클라이언트 금액 위변조 방지).
     */
    data class UnverifiedSuccess(
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
