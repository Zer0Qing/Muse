package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsrfGuardTest {

    @Test
    fun blocksLoopbackPrivateAndLinkLocalAddresses() {
        assertTrue(SsrfGuard.isBlocked("http://127.0.0.1/"))
        assertTrue(SsrfGuard.isBlocked("http://10.0.0.1/"))
        assertTrue(SsrfGuard.isBlocked("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun rejectsNonHttpUrlsAndMalformedUrls() {
        assertTrue(SsrfGuard.isBlocked("about:blank"))
        assertTrue(SsrfGuard.isBlocked("file:///android_asset/test.html"))
        assertTrue(SsrfGuard.isBlocked("not a URL"))
    }

    @Test
    fun allowsKnownPublicLiteralAddress() {
        assertFalse(SsrfGuard.isBlocked("http://8.8.8.8/"))
    }
}
