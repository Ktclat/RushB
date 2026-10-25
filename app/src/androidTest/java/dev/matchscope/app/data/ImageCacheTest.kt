package dev.matchscope.app.data

import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -Pandroid.testInstrumentationRunnerArguments.onlineMedia=true. */
class ImageCacheTest {
    @Test fun realPngSvgAndPortraitDecodeAndReloadFromDiskWithoutNetwork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("onlineMedia") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MediaLoader.install()
        val loader = SingletonImageLoader.get(context)
        assertNotNull("Initial Falcons logo", SeedSnapshot.falcons.logoUrl)
        assertNotNull("Initial FURIA SVG logo", SeedSnapshot.furia.logoUrl)
        val urls = listOf(SeedSnapshot.falcons.logoUrl!!, SeedSnapshot.furia.logoUrl!!,
            SeedSnapshot.matchItems.flatMap { it.players.values.flatten() }.first { it.id == "3741" }.photoUrl!!)
        for(url in urls) {
            val first = loader.execute(ImageRequest.Builder(context).data(url).size(160).memoryCachePolicy(CachePolicy.DISABLED).build())
            assertTrue("Real CDN image must decode: $first", first is SuccessResult)
            val cached = loader.execute(ImageRequest.Builder(context).data(url).size(160).memoryCachePolicy(CachePolicy.DISABLED)
                .networkCachePolicy(CachePolicy.DISABLED).build())
            assertTrue("Image must decode with network disabled: $cached", cached is SuccessResult)
            assertEquals(DataSource.DISK, (cached as SuccessResult).dataSource)
        }
    }
}
