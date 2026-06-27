package io.github.l2hyunwoo.tosspayments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TossPaymentErrorTest {

    @Test
    fun bothCancelChannels_mapToUserCanceled() {
        assertIs<TossPaymentError.UserCanceled>(TossPaymentError.from("USER_CANCEL", "x"))
        assertIs<TossPaymentError.UserCanceled>(TossPaymentError.from("PAY_PROCESS_CANCELED", "x"))
    }

    @Test
    fun networkCode_mapsToNetwork() {
        assertIs<TossPaymentError.Network>(TossPaymentError.from("NETWORK_ERROR", "x"))
    }

    @Test
    fun categoryCodes_mapToTheirVariants() {
        assertIs<TossPaymentError.Configuration>(TossPaymentError.from("INVALID_CLIENT_KEY", "x"))
        assertIs<TossPaymentError.UserFlow>(TossPaymentError.from("NEED_AGREEMENT_WITH_REQUIRED_TERMS", "x"))
        assertIs<TossPaymentError.ProviderFailure>(TossPaymentError.from("PAY_PROCESS_ABORTED", "x"))
        assertIs<TossPaymentError.CardState>(TossPaymentError.from("INVALID_STOPPED_CARD", "x"))
    }

    @Test
    fun unknownAndPlatformInternalCodes_degradeToUnknown_retainingCode() {
        // iOS-internal "102", documented "UNKNOWN", and any future code must not crash.
        val internal = TossPaymentError.from("102", "frame load interrupted")
        assertIs<TossPaymentError.Unknown>(internal)
        assertEquals("102", internal.code)

        assertIs<TossPaymentError.Unknown>(TossPaymentError.from("UNKNOWN", "x"))
        assertIs<TossPaymentError.Unknown>(TossPaymentError.from("SOME_FUTURE_CODE", "x"))
    }

    @Test
    fun rawCodeAndOrderId_areAlwaysPreserved() {
        val e = TossPaymentError.from("REJECT_CARD_COMPANY", "rejected", orderId = "order-42")
        assertEquals("REJECT_CARD_COMPANY", e.code)
        assertEquals("rejected", e.message)
        assertEquals("order-42", e.orderId)
    }

    @Test
    fun messageIsNotUsedForClassification() {
        // Same code, different platform messages → same variant.
        val web = TossPaymentError.from("PAY_PROCESS_CANCELED", "사용자에 의해 결제가 취소되었습니다")
        val ios = TossPaymentError.from("PAY_PROCESS_CANCELED", "사용자가 결제를 취소하였습니다")
        assertTrue(web is TossPaymentError.UserCanceled && ios is TossPaymentError.UserCanceled)
    }
}
