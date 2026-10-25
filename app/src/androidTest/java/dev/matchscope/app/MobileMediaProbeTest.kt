package dev.matchscope.app

import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import dev.matchscope.app.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Fresh mobile responses and cold image reads; no computer relay or imported cookies. */
class MobileMediaProbeTest {
    @Test fun decodeCurrentSourceImagesAndReuseDiskCacheOffline() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mobileMedia")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val portraitId=InstrumentationRegistry.getArguments().getString("portraitId")
        require(portraitId==null || portraitId.matches(Regex("[1-9][0-9]*")))
        MediaLoader.install()
        val loader=SingletonImageLoader.get(context)
        val source=MobileJsonSource(File(context.cacheDir,"mobile-media-${System.nanoTime()}"),BrowserSession())
        val proof=JSONObject().put("startedAt",Instant.now().toString()).put("version",BuildConfig.VERSION_NAME).put("relayEnabled",false)
        val cases=JSONArray(); proof.put("images",cases)
        try {
            withTimeout(180_000) {
                val playerId=portraitId ?: "19230"
                val player=source.read(MobileRequest.create(MobileRoute.PLAYER,playerId),refresh=true) { MobileProfileParser.player(it,playerId) }.value
                val urls=if(portraitId!=null) listOf("portrait-$portraitId" to player.player.photoUrl) else {
                val event=source.read(MobileRequest.create(MobileRoute.EVENT,"8932"),refresh=true) { MobileProfileParser.event(it,"8932") }.value
                val news=source.read(MobileRequest.create(MobileRoute.PLAYER_NEWS,"19230"),refresh=true,decode=MobileProfileParser::news).value
                listOf("team" to player.team?.logoUrl,"portrait" to player.player.photoUrl,"event-banner" to event.bannerUrl,
                    "trophy" to player.content.honors.first { it.category=="冠军" }.imageUrl,
                    "mvp-medal" to player.content.honors.first { it.category=="MVP" }.imageUrl,
                    "top20" to player.content.honors.first { it.category=="Top 20" }.imageUrl,"news" to news.first().imageUrl)
                }
                for ((name,url) in urls) {
                    val row=JSONObject().put("name",name).put("source",url);cases.put(row)
                    val attempts=JSONArray();row.put("attempts",attempts)
                    var successful:String?=null
                    for (candidate in MediaUrls.candidates(url).take(3)) {
                        val result=loader.execute(ImageRequest.Builder(context).data(candidate).size(320)
                            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.WRITE_ONLY).build())
                        val attempt=JSONObject().put("url",candidate);attempts.put(attempt)
                        if (result is SuccessResult) {
                            assertEquals(DataSource.NETWORK,result.dataSource)
                            attempt.put("origin",result.dataSource.name).put("width",result.image.width).put("height",result.image.height)
                            successful=candidate;break
                        } else attempt.put("error",(result as? ErrorResult)?.throwable.toString().take(300))
                    }
                    if (successful!=null) {
                        val cached=loader.execute(ImageRequest.Builder(context).data(successful).size(320).memoryCachePolicy(CachePolicy.DISABLED).networkCachePolicy(CachePolicy.DISABLED).build())
                        assertTrue(cached is SuccessResult)
                        assertEquals(DataSource.DISK,(cached as SuccessResult).dataSource)
                        row.put("outcome","PASSED").put("offlineOrigin",cached.dataSource.name)
                    } else row.put("outcome","FAILED")
                }
                val failed=(0 until cases.length()).count { cases.getJSONObject(it).getString("outcome")!="PASSED" }
                proof.put("failed",failed).put("outcome",if(failed==0) "PASSED" else "PARTIAL")
                assertEquals("Some current image sources failed; inspect mobile-media-proof.json",0,failed)
            }
        } finally {
            proof.put("finishedAt",Instant.now().toString())
            File(context.filesDir,if(portraitId==null) "mobile-media-proof.json" else "portrait-$portraitId-proof.json").writeText(proof.toString(2))
        }
    }
}
