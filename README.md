# toss-payments-kmp

토스페이먼츠 **v2 결제위젯**을 위한 Compose Multiplatform (Android + iOS) 래퍼 라이브러리.
v2 JS SDK를 플랫폼 WebView에 직접 띄워 하나의 Compose API로 노출한다.

## 사용법

```kotlin
val widget = rememberTossPaymentWidget(
    config = PaymentWidgetConfig(
        clientKey = ClientKey("test_gck_..."),
        customerKey = CustomerKey.ANONYMOUS,
        appScheme = "myapp",            // iOS 앱-투-앱 복귀 스킴 (아래 참고)
    ),
    amount = PaymentAmount(value = 50_000),   // 원(KRW)
)

val status by widget.status.collectAsState()
val agreed by widget.agreedRequiredTerms.collectAsState()

TossPaymentWidgetSurface(widget)

Button(enabled = status == WidgetStatus.READY && agreed, onClick = {
    scope.launch {
        when (val r = widget.requestPayment(PaymentOrder("order-1", "티셔츠"))) {
            is PaymentResult.UnverifiedSuccess -> // r.paymentKey + orderId + amount 를 가맹점 서버로 전송
            is PaymentResult.Failure -> // r.error 는 타입이 있는 TossPaymentError
        }
    }
}) { Text("결제하기") }
```

## 사용처 앱에 필요한 설정

아래 항목은 라이브러리가 대신 넣을 수 없다 — 앱 번들에 통합하는 쪽이 직접 추가해야 한다.
빠지면 **간편결제·은행 앱 결제가 테스트에서는 되지만 실제 앱에서는 깨진다.**

### iOS (`Info.plist`)
- `CFBundleURLTypes` → 외부 앱이 내 앱으로 복귀할 수 있도록 `appScheme` 등록.
- `LSApplicationQueriesSchemes` → 실행을 허용할 은행·간편결제 스킴 목록.
- 복귀 URL을 `onOpenURL` / `AppDelegate`로 받아 위젯으로 다시 라우팅.

### Android (`AndroidManifest.xml`)
- `<uses-permission android:name="android.permission.INTERNET" />`.

## 서버 측 승인 (필수)

`PaymentResult.UnverifiedSuccess`는 JS SDK가 `paymentKey`를 만들어냈다는 뜻일 뿐, **결제가 완료된 것이 아니다.**
가맹점 서버가 주문을 처리하기 전에 반드시 토스페이먼츠 `POST /v1/payments/confirm`을
`paymentKey` + `orderId` + `amount`로 호출해야 한다. 인-프로세스 결과는 절대 최종 권위가 아니다.

## 라이선스

[MIT](LICENSE) © HyunWoo Lee
