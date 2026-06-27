package io.github.l2hyunwoo.tosspayments

import kotlin.jvm.JvmInline

/**
 * 가맹점 client key (test_ck_… / live_ck_…). 설계상 공개되며 client에 그대로 박아도 안전하다.
 * 권한의 근거가 되는 secret key는 절대 가맹점 서버 밖으로 나가지 않는다.
 */
@JvmInline
value class ClientKey(val value: String)

/**
 * payment-widget / billing flow에서 구매자를 식별한다. 구매자 신원을 추적하지 않는
 * 일회성 게스트 결제에는 [ANONYMOUS]를 쓴다.
 */
@JvmInline
value class CustomerKey(val value: String) {
    companion object {
        val ANONYMOUS: CustomerKey = CustomerKey("ANONYMOUS")
    }
}

/** TossPayments JS SDK가 받는 통화. KRW 금액은 원 단위 정수다. */
enum class Currency { KRW, AUD, EUR, GBP, HKD, JPY, SGD, USD }

/**
 * 두 공식 SDK(그리고 JS SDK)에서 금액은 RENDER 시점에 바인딩되고, 이후
 * [TossPaymentWidget.updateAmount]로 변경된다. orderId/orderName은 requestPayment
 * 시점에만 들어온다 — [PaymentOrder] 참고. 두 단계를 분리한 것은 SDK 계약을 그대로 반영하며,
 * 어느 플랫폼에도 맞지 않는 API를 피하기 위함이다.
 */
data class PaymentAmount(
    val value: Long,
    val currency: Currency = Currency.KRW,
    /** ISO-3166-1 alpha-2; 기본값은 KR. */
    val country: String = "KR",
)

/**
 * requestPayment 시점에 넘기는 주문 정보.
 *
 * @param orderId 가맹점 내 고유값, [a-zA-Z0-9-_=] 문자로 6–64자.
 * @param orderName 구매자에게 노출되며, 100자 이하.
 */
data class PaymentOrder(
    val orderId: String,
    val orderName: String,
    val customerEmail: String? = null,
    val customerName: String? = null,
    val customerMobilePhone: String? = null,
)

/** payment-method widget을 렌더링할 때 적용되는 옵션. */
data class RenderOptions(
    /** 가맹점 콘솔에서 설정한 A/B-test 또는 UI variant. */
    val variantKey: String? = null,
)

/**
 * widget을 구성하는 데 필요한 모든 것과, 통합하는 쪽이 충족해야 하는 플랫폼별 host 요건.
 * 이 라이브러리는 앱을 대신해 URL scheme을 등록할 수 없다 — 필요한 Info.plist /
 * AndroidManifest 항목은 README 참고.
 */
data class PaymentWidgetConfig(
    val clientKey: ClientKey,
    val customerKey: CustomerKey,
    /**
     * host 앱의 CFBundleURLTypes에 선언한 iOS app-to-app return scheme
     * (예: "myapp"). iOS에서 easy-pay / bank-app 왕복에 필요하다. Android의
     * intent return은 자동이라 여기에 값이 필요 없다.
     */
    val appScheme: String? = null,
    /** BrandPay 전용. */
    val brandPayRedirectUrl: String? = null,
    /** 고정된 JS SDK endpoint. 기본값 = 공식 v2 standard. */
    val jsSdkUrl: String = DEFAULT_JS_SDK_URL,
) {
    companion object {
        const val DEFAULT_JS_SDK_URL: String = "https://js.tosspayments.com/v2/standard"
    }
}

/** widget에서 현재 선택된 결제 수단. */
data class SelectedPaymentMethod(
    val type: String,
    val method: String,
    val easyPay: String? = null,
)
