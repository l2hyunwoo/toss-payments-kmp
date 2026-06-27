package io.github.l2hyunwoo.tosspayments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 브리지 origin 제한(Android WebMessageListener allowlist / iOS frameInfo 검증)의 정확성은
 * [Bridge.ORIGIN] / [Bridge.ORIGIN_SCHEME] / [Bridge.ORIGIN_HOST] 가 [Bridge.BASE_URL] 및
 * sentinel URL들과 정확히 일치하는 데 달려 있다. 어긋나면 정당한 host 페이지 메시지가 거부되거나
 * (기능 깨짐) 잘못된 origin이 허용된다(보안 구멍). 이 불변식을 고정한다.
 */
class BridgeOriginTest {

    @Test
    fun origin_isBaseUrlWithoutTrailingSlash() {
        assertEquals(Bridge.BASE_URL.trimEnd('/'), Bridge.ORIGIN)
    }

    @Test
    fun origin_decomposesIntoSchemeAndHost() {
        assertEquals("${Bridge.ORIGIN_SCHEME}://${Bridge.ORIGIN_HOST}", Bridge.ORIGIN)
    }

    @Test
    fun sentinelUrls_shareTheOrigin() {
        // success/fail redirect가 같은 origin이어야 same-origin navigation으로 interceptor에 도달한다.
        assertTrue(Bridge.SUCCESS_URL.startsWith(Bridge.ORIGIN), Bridge.SUCCESS_URL)
        assertTrue(Bridge.FAIL_URL.startsWith(Bridge.ORIGIN), Bridge.FAIL_URL)
    }

    @Test
    fun origin_isHttps() {
        // origin 제한이 의미를 가지려면 https여야 한다(평문 origin은 위조·MITM에 취약).
        assertEquals("https", Bridge.ORIGIN_SCHEME)
    }
}
