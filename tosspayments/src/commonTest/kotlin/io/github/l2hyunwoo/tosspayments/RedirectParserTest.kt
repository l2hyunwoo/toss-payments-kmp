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
        // 한글 message는 percent-encoded로 도착하므로 원본 텍스트로 round-trip 되어야 한다.
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
        // sentinel 문자열로 시작하기만 하는 path는 매칭되면 안 된다.
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
