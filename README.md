# toss-payments-kmp

토스페이먼츠 **v2 결제위젯**을 위한 Compose Multiplatform (Android + iOS) 래퍼 라이브러리.

## 아키텍처

이 라이브러리는 **토스페이먼츠 v2 JS SDK**를 플랫폼 WebView
(Android는 `android.webkit.WebView`, iOS는 `WKWebView`) 안에 직접 띄우고(self-host),
하나의 Compose API로 노출한다. `payment-sdk-android`·`payment-sdk-ios` **어느 쪽에도 의존하지 않는다.**

**네이티브 SDK를 래핑하지 않는 이유.** 공식 SDK 두 개 모두 같은 JS SDK 위에 얹힌 얇은 WebView 껍데기다 —
Android는 `PaymentWebView extends android.webkit.WebView`, iOS는 `PaymentMethodWidget`·`AgreementWidget`이
`WKWebView` 서브클래스. 결정적으로 iOS SDK의 공개 API는 **`@objc`가 전혀 없는 순수 Swift**라
Kotlin/Native cinterop가 직접 호출할 수 없다 — 래핑하려면 손으로 작성한 `@objc` Swift shim을
모든 사용처 앱에 영구히 컴파일해 넣어야 한다. self-host는 이 문제를 통째로 우회한다: `WKWebView`와 그 동료들은
Kotlin/Native가 그대로 호출하는 Objective-C 프레임워크인 WebKit의 일부라, **iOS에 Swift가 한 줄도 필요 없다.**

```
:tosspayments          배포되는 KMP 라이브러리
  commonMain           코드의 ~80%: 공개 Composable, config, 에러 모델, JS 브리지
  androidMain          WebView 호스트 (actual)
  iosMain              WKWebView 호스트 (actual) — 순수 Kotlin/Native, Swift 없음
:sample:shared         :tosspayments에 의존하는 데모 Compose UI
:sample:androidApp     com.android.application 데모
```

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
- 은행 앱으로의 인텐트 리다이렉트(`intent://…`)는 WebView 호스트 내부에서 처리한다. 복귀를 위한
  별도 매니페스트 항목은 필요 없다(은행 앱이 내 Activity를 다시 포그라운드로 올린다).

## 서버 측 승인 (필수)

`PaymentResult.UnverifiedSuccess`는 JS SDK가 `paymentKey`를 만들어냈다는 뜻일 뿐, **결제가 완료된 것이 아니다.**
(타입 이름이 `UnverifiedSuccess`인 이유 — 클라이언트가 받는 성공은 WebView 안에서 위조 가능하므로, 서버 confirm 전까지는 "미검증"이다.)
가맹점 서버가 주문을 처리하기 전에 반드시 토스페이먼츠 `POST /v1/payments/confirm`을
`paymentKey` + `orderId` + `amount`로 호출해야 한다. 인-프로세스 결과는 절대 최종 권위가 아니다.

## 라이선스

[MIT](LICENSE) © HyunWoo Lee
