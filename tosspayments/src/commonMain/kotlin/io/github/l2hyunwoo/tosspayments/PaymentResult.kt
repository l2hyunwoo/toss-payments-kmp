package io.github.l2hyunwoo.tosspayments

/**
 * [TossPaymentWidget.requestPayment]의 결과.
 *
 * 결과는 WebView가 sentinel URL로 redirect한 query에서 읽으므로 WebView 안 스크립트가 위조할 수
 * 있다. 진짜 권위는 가맹점 서버의 `/v1/payments/confirm`뿐 — 성공 케이스를 [UnverifiedSuccess]로
 * 명명해 "아직 검증 안 됨"을 타입에 실었다.
 */
sealed interface PaymentResult {
    /**
     * paymentKey가 생성됐을 뿐 결제 완료가 아니다. 가맹점 서버가 [paymentKey]+[orderId]+[amount]로
     * `/v1/payments/confirm`을 호출해야 한다. [amount]는 신뢰 불가한 redirect query 값이므로 서버가
     * 자신이 만든 주문 금액과 대조해야 한다(클라이언트 금액 위변조 방지).
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
 * 위젯 준비 상태. JS SDK는 비동기 렌더링이라 [READY] 이전 requestPayment는 거부된다 — 결제 버튼의
 * enabled 상태를 이 값으로 제어한다.
 */
enum class WidgetStatus { LOADING, READY, FAILED }
