package io.zer0.muse.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * R-TEST-12: WebServer 登录限流与 JWT 签发/校验纯逻辑。
 */
class WebServerAuthPolicyTest {

    @Test
    fun `rate limit kicks in after five failures within window`() {
        val start = 1_000L
        val window = 30_000L
        assertFalse(WebServerAuthPolicy.isRateLimited(start, start, 4, window, 5))
        assertTrue(WebServerAuthPolicy.isRateLimited(start, start, 5, window, 5))
        assertTrue(WebServerAuthPolicy.isRateLimited(start + 29_000, start, 5, window, 5))
        assertFalse(WebServerAuthPolicy.isRateLimited(start + 30_001, start, 5, window, 5))
    }

    @Test
    fun `remaining seconds counts down and resets after window`() {
        val start = 1_000L
        val window = 30_000L
        assertEquals(30L, WebServerAuthPolicy.remainingSeconds(start, start, window))
        assertEquals(1L, WebServerAuthPolicy.remainingSeconds(start + 29_000, start, window))
        assertEquals(0L, WebServerAuthPolicy.remainingSeconds(start + 30_001, start, window))
    }

    @Test
    fun `jwt signs and verifies with hmac secret`() {
        val algorithm = Algorithm.HMAC256("muse-test-secret")
        val token = JWT.create()
            .withSubject("muse-web")
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000L))
            .sign(algorithm)
        val decoded = JWT.require(algorithm).build().verify(token)
        assertEquals("muse-web", decoded.subject)
    }

    @Test
    fun `generated jwt secret is random and long enough`() {
        val secret = WebServerConfig.generateRandomJwtSecret()
        assertTrue(secret.length >= 43)
        assertFalse(secret == WebServerConfig.generateRandomJwtSecret())
    }

    @Test
    fun `bind host defaults to localhost and only binds all interfaces when lan enabled`() {
        assertEquals("127.0.0.1", WebServer.bindHost(false))
        assertEquals("0.0.0.0", WebServer.bindHost(true))
    }

    @Test
    fun `new config defaults to localhost only`() {
        assertFalse(WebServerConfig().allowLan)
    }
}
