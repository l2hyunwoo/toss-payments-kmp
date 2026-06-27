package io.github.l2hyunwoo.tosspayments

/**
 * JS / Android / iOS SDK 전반을 아우르는 통합 에러 모델.
 *
 * 검증된 설계 제약(docs.tosspayments.com/sdk/v2/error-codes + 양쪽 SDK 레포):
 *  - 에러 CODE 문자열은 서버에서 내려온 값을 그대로 전달하며, 모든 플랫폼에서 동일하다.
 *    message가 아니라 code로 분기한다.
 *  - 같은 code라도 MESSAGE는 플랫폼별로 다르므로(iOS는 web과 다른 취소 메시지를 쓴다)
 *    참고용일 뿐이다.
 *  - 집합은 open이다: SDK는 임의/신규/플랫폼 내부 code를 그대로 흘려보낸다(예: iOS의
 *    내부 "102" WKWebView frame-load interruption). 매핑되지 않은 code는 exhaustive `when`을
 *    깨뜨리지 않고 [Unknown]으로 강등된다.
 *  - 두 취소 채널 — USER_CANCEL(promise)과 PAY_PROCESS_CANCELED(failUrl) — 은
 *    [UserCanceled]로 통합되어 호출자가 한 케이스만 검사하면 된다.
 *
 * [code]는 variant가 [Unknown]일 때도 진단을 위해 항상 원문 그대로 보존된다.
 */
sealed class TossPaymentError(
    open val code: String,
    open val message: String,
    open val orderId: String? = null,
) {
    /** USER_CANCEL | PAY_PROCESS_CANCELED — 구매자가 중단함. 실제 실패가 아니다. */
    data class UserCanceled(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** 개발자/설정 오류 — 코드에서 고쳐야 한다(잘못된 key, 잘못된 amount, 잘못된 URL…). */
    data class Configuration(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** 진행하려면 사용자 동작이 필요하다(약관 미동의, 결제수단 미선택, password…). */
    data class UserFlow(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** NETWORK_ERROR — 일시적이며 대개 재시도 가능하다. */
    data class Network(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** 결제대행사/카드사/프로세스 실패 — 실제 결제 실패다. */
    data class ProviderFailure(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** 카드 상태 문제 — 정지, 분실/도난, 또는 재등록 필요. */
    data class CardState(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    /** 문서화된 UNKNOWN, 매핑되지 않은 code, 또는 플랫폼 내부 code. */
    data class Unknown(
        override val code: String,
        override val message: String,
        override val orderId: String? = null,
    ) : TossPaymentError(code, message, orderId)

    companion object {
        /** 임의 플랫폼에서 온 raw (code, message) 쌍을 타입화된 에러로 정규화한다. */
        fun from(code: String, message: String, orderId: String? = null): TossPaymentError =
            when (code) {
                "USER_CANCEL", "PAY_PROCESS_CANCELED" -> UserCanceled(code, message, orderId)
                "NETWORK_ERROR" -> Network(code, message, orderId)
                in CONFIG_CODES -> Configuration(code, message, orderId)
                in USER_FLOW_CODES -> UserFlow(code, message, orderId)
                in PROVIDER_CODES -> ProviderFailure(code, message, orderId)
                in CARD_STATE_CODES -> CardState(code, message, orderId)
                else -> Unknown(code, message, orderId) // "UNKNOWN", iOS "102", 향후 code 포함
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
