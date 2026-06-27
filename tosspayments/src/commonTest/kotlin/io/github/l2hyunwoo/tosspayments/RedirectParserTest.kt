package io.github.l2hyunwoo.tosspayments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedirectParserTest {

    @Test
    fun successUrl_parsesPaymentKeyOrderIdAmount() {
        val url = "${Bridge.SUCCESS_URL}?paymentKey=pk_abc&orderId=order-1&amount=50000"
        val msg = RedirectParser.parse(url)!!
        assertEquals(GuestEvent.PAYMENT_SUCCESS, msg.event)
        assertEquals("pk_abc", msg.payload?.paymentKey)
        assertEquals("order-1", msg.payload?.orderId)
        assertEquals(50000L, msg.payload?.amount)
    }

    @Test
    fun failUrl_parsesCodeMessageOrderId() {
        val url = "${Bridge.FAIL_URL}?code=PAY_PROCESS_CANCELED&message=cancelled&orderId=order-9"
        val msg = RedirectParser.parse(url)!!
        assertEquals(GuestEvent.PAYMENT_FAIL, msg.event)
        assertEquals("PAY_PROCESS_CANCELED", msg.payload?.code)
        assertEquals("order-9", msg.payload?.orderId)
    }

    @Test
    fun failUrl_withoutCode_defaultsToUnknown() {
        val msg = RedirectParser.parse("${Bridge.FAIL_URL}?orderId=o1")!!
        assertEquals("UNKNOWN", msg.payload?.code)
    }

    @Test
    fun percentEncodedMessage_isDecoded() {
        // Korean message arrives percent-encoded; must round-trip to the original text.
        val url = "${Bridge.FAIL_URL}?code=PAY_PROCESS_CANCELED&message=%EC%B7%A8%EC%86%8C"
        val msg = RedirectParser.parse(url)!!
        assertEquals("취소", msg.payload?.message)
    }

    @Test
    fun plusIsDecodedToSpace() {
        val url = "${Bridge.FAIL_URL}?code=X&message=card+rejected"
        assertEquals("card rejected", RedirectParser.parse(url)?.payload?.message)
    }

    @Test
    fun nonSentinelUrl_returnsNull() {
        assertNull(RedirectParser.parse("https://js.tosspayments.com/v2/standard"))
        assertNull(RedirectParser.parse("intent://pay#Intent;scheme=foo;end"))
        assertNull(RedirectParser.parse("https://tosspayments.local/widget/other?x=1"))
    }

    @Test
    fun samePrefixPath_isNotMistakenForSentinel() {
        // A path that merely starts with the sentinel string must NOT match.
        assertNull(RedirectParser.parse("${Bridge.SUCCESS_URL}-summary?paymentKey=pk"))
        assertNull(RedirectParser.parse("${Bridge.FAIL_URL}ure?code=X"))
    }

    @Test
    fun bareSentinel_withoutQuery_matches() {
        val msg = RedirectParser.parse(Bridge.SUCCESS_URL)!!
        assertEquals(GuestEvent.PAYMENT_SUCCESS, msg.event)
    }

    @Test
    fun malformedAmount_becomesNull_notCrash() {
        val msg = RedirectParser.parse("${Bridge.SUCCESS_URL}?paymentKey=pk&orderId=o&amount=NaN")!!
        assertNull(msg.payload?.amount)
    }
}
