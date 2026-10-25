package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class MediaCandidatesTest {
    @Test fun fallbackPreservesSourceQueryAndRequiresSameAsset() {
        val original = "https://img-cdn.hltv.org/teamlogo/4eJSkDQINNM6Tbs4WvLzkN.png?ixlib=java-2.1.0&w=500&s=21613e9a4eb0302486984b3c3536202e"
        val variants = MediaUrls.candidates(original)
        assertEquals(original, variants.first())
        assertTrue(variants.size > 1)
        assertTrue(variants.all { URI(it).path == URI(original).path })
        assertEquals(variants.size, variants.distinct().size)
        assertTrue(MediaUrls.candidates("https://example.com/image.png").isEmpty())
    }
    @Test fun eventBannerIsAcceptedButAdvertisementsAreNot() {
        assertNotNull(MediaUrls.validated("https://img-cdn.hltv.org/eventbanner/known.png?ixlib=java-2.1.0&w=1276&s=example"))
        assertNull(MediaUrls.validated("https://img-cdn.hltv.org/ads/known.png"))
    }
}
