package io.github.l2hyunwoo.tosspayments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeContractTest {

    @Test
    fun guestMessage_paymentSuccess_decodes() {
        val raw = """
            {"event":"paymentSuccess","payload":{"paymentKey":"pk_1","orderId":"order-1","amount":50000,
            "additionalParameters":{"paymentType":"BRANDPAY"}}}
        """.trimIndent()
        val msg = Bridge.json.decodeFromString(GuestMessage.serializer(), raw)
        assertEquals(GuestEvent.PAYMENT_SUCCESS, msg.event)
        assertEquals("pk_1", msg.payload?.paymentKey)
        assertEquals(50000L, msg.payload?.amount)
        assertEquals("BRANDPAY", msg.payload?.additionalParameters?.get("paymentType"))
    }

    @Test
    fun guestMessage_paymentFail_decodes() {
        val raw = """{"event":"paymentFail","payload":{"code":"PAY_PROCESS_CANCELED","message":"취소","orderId":"o9"}}"""
        val msg = Bridge.json.decodeFromString(GuestMessage.serializer(), raw)
        assertEquals(GuestEvent.PAYMENT_FAIL, msg.event)
        assertEquals("PAY_PROCESS_CANCELED", msg.payload?.code)
    }

    @Test
    fun guestMessage_ignoresUnknownKeys() {
        // 페이지가 모델에 없는 필드를 추가할 수 있다. 디코딩이 예외를 던지면 안 된다.
        val raw = """{"event":"ready","payload":{"height":120.0,"futureField":"x"},"meta":123}"""
        val msg = Bridge.json.decodeFromString(GuestMessage.serializer(), raw)
        assertEquals(GuestEvent.READY, msg.event)
        assertEquals(120.0, msg.payload?.height)
    }

    @Test
    fun hostCommand_requestPayment_buildsEscapedJsCall() {
        val js = HostCommand.RequestPayment(
            PaymentOrder(orderId = "order-1", orderName = "Tee \"L\""),
        ).toJs()
        assertTrue(js.startsWith("window.${Bridge.JS_NAMESPACE}.requestPayment("))
        // orderName 안의 따옴표는 JS 호출을 깨지 않도록 JSON-escape 되어야 한다.
        assertTrue(js.contains("""Tee \"L\""""), "orderName quote must be escaped: $js")
    }

    @Test
    fun hostCommand_requestPayment_injectsSentinelUrls() {
        // requestPayment는 native host가 가로채는 sentinel successUrl/failUrl을 담아야 한다.
        val js = HostCommand.RequestPayment(PaymentOrder("order-1", "Tee")).toJs()
        assertTrue(js.contains("\"successUrl\":\"${Bridge.SUCCESS_URL}\""), js)
        assertTrue(js.contains("\"failUrl\":\"${Bridge.FAIL_URL}\""), js)
    }

    @Test
    fun hostCommand_init_buildsInitCall() {
        val js = HostCommand.Init(
            PaymentWidgetConfig(ClientKey("test_ck_x"), CustomerKey.ANONYMOUS),
        ).toJs()
        assertTrue(js.startsWith("window.${Bridge.JS_NAMESPACE}.init("), js)
        assertTrue(js.contains("\"clientKey\":\"test_ck_x\""), js)
        assertTrue(js.contains("\"customerKey\":\"ANONYMOUS\""), js)
    }

    @Test
    fun hostCommand_updateAmount_carriesCurrency() {
        // setAmount는 {currency, value}를 요구한다 — value만이 아니라 currency도 직렬화되어야 한다.
        val js = HostCommand.UpdateAmount(
            PaymentAmount(value = 2000, currency = Currency.JPY),
            description = "coupon",
        ).toJs()
        assertTrue(js.contains("\"value\":2000"), js)
        assertTrue(js.contains("\"currency\":\"JPY\""), js)
    }

    @Test
    fun hostCommand_render_encodesAmountAndCurrency() {
        val js = HostCommand.RenderPaymentMethods(
            PaymentAmount(value = 1500, currency = Currency.USD, country = "US"),
            RenderOptions(variantKey = "DEFAULT"),
        ).toJs()
        assertTrue(js.contains("\"value\":1500"))
        assertTrue(js.contains("\"currency\":\"USD\""))
        assertTrue(js.contains("\"variantKey\":\"DEFAULT\""))
    }

    @Test
    fun hostCommand_render_omitsNullVariantKey() {
        // explicitNulls=false → 없는 optional 필드는 null로 직렬화되지 않고 제거된다.
        val js = HostCommand.RenderPaymentMethods(PaymentAmount(1000), RenderOptions()).toJs()
        assertTrue(!js.contains("variantKey"), "null variantKey should be omitted: $js")
    }

    @Test
    fun emptyPayload_fieldsAreNull() {
        val msg = Bridge.json.decodeFromString(GuestMessage.serializer(), """{"event":"ready"}""")
        assertNull(msg.payload)
    }
}
