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
        // The page may add fields we don't model; decoding must not throw.
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
        // The quote inside orderName must be JSON-escaped, not break the JS call.
        assertTrue(js.contains("""Tee \"L\""""), "orderName quote must be escaped: $js")
    }

    @Test
    fun hostCommand_requestPayment_injectsSentinelUrls() {
        // requestPayment must carry the sentinel successUrl/failUrl the native host intercepts.
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
        // setAmount requires {currency, value} — currency must be serialized, not just value.
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
        // explicitNulls=false → absent optional fields are dropped, not serialized as null.
        val js = HostCommand.RenderPaymentMethods(PaymentAmount(1000), RenderOptions()).toJs()
        assertTrue(!js.contains("variantKey"), "null variantKey should be omitted: $js")
    }

    @Test
    fun emptyPayload_fieldsAreNull() {
        val msg = Bridge.json.decodeFromString(GuestMessage.serializer(), """{"event":"ready"}""")
        assertNull(msg.payload)
    }
}
