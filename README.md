# toss-payments-kmp

A Compose Multiplatform (Android + iOS) wrapper for the TossPayments **v2 payment widget**.

## Architecture

This library self-hosts the **TossPayments v2 JS SDK** inside a platform WebView
(`android.webkit.WebView` on Android, `WKWebView` on iOS) and exposes a single Compose API.
It depends on **neither** `payment-sdk-android` **nor** `payment-sdk-ios`.

**Why not wrap the native SDKs?** Both official SDKs are themselves thin WebView shells over
the same JS SDK — Android's `PaymentWebView extends android.webkit.WebView`, iOS's
`PaymentMethodWidget`/`AgreementWidget` are `WKWebView` subclasses. Crucially, the iOS SDK's
public API is **pure Swift with no `@objc`**, which Kotlin/Native cinterop cannot call — wrapping
it would force a hand-written `@objc` Swift shim compiled into every consumer app, permanently.
Self-hosting sidesteps that entirely: `WKWebView` and friends are part of WebKit, an
Objective-C framework Kotlin/Native speaks natively, so **iOS needs zero Swift**.

```
:tosspayments          published KMP library
  commonMain           ~80% of the code: public Composables, config, error model, JS bridge
  androidMain          WebView host (actual)
  iosMain              WKWebView host (actual) — pure Kotlin/Native, no Swift
:sample:shared         demo Compose UI depending on :tosspayments
:sample:androidApp     com.android.application demo
```

## Usage

```kotlin
val widget = rememberTossPaymentWidget(
    config = PaymentWidgetConfig(
        clientKey = ClientKey("test_gck_..."),
        customerKey = CustomerKey.ANONYMOUS,
        appScheme = "myapp",            // iOS app-to-app return scheme (see below)
    ),
    amount = PaymentAmount(value = 50_000),   // KRW
)

val status by widget.status.collectAsState()
val agreed by widget.agreedRequiredTerms.collectAsState()

TossPaymentMethods(widget)
TossPaymentAgreement(widget)

Button(enabled = status == WidgetStatus.READY && agreed, onClick = {
    scope.launch {
        when (val r = widget.requestPayment(PaymentOrder("order-1", "T-shirt"))) {
            is PaymentResult.Success -> /* send r.paymentKey + orderId + amount to YOUR server */
            is PaymentResult.Failure -> /* r.error is a typed TossPaymentError */
        }
    }
}) { Text("Pay") }
```

## Required host-app configuration

These cannot be set by the library — they are app-bundle entries the integrator must add.
Without them, **easy-pay / bank-app payments work in test but break with real apps**.

### iOS (`Info.plist`)
- `CFBundleURLTypes` → register your `appScheme` so external apps can return to yours.
- `LSApplicationQueriesSchemes` → list the bank/easy-pay schemes you allow launching.
- Route the inbound return URL back into the widget via `onOpenURL` / `AppDelegate`.

### Android (`AndroidManifest.xml`)
- `<uses-permission android:name="android.permission.INTERNET" />`.
- Intent redirect to bank apps (`intent://…`) is handled inside the WebView host; no extra
  manifest entry is needed for the return (the bank app re-foregrounds your Activity).

## Server-side confirmation (mandatory)

`PaymentResult.Success` only means the JS SDK produced a `paymentKey`. It is **not** a settled
payment. Your merchant server **must** call TossPayments `POST /v1/payments/confirm` with
`paymentKey` + `orderId` + `amount` before fulfilling the order. The in-process result is never
authoritative.

## Status

Design + scaffold. The WebView/JS wiring (host page ↔ JS SDK, redirect/app-scheme handling,
3DS popups, JS-driven height) is stubbed and lands in milestone 2.
