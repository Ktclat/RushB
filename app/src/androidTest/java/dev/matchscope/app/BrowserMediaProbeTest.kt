package dev.matchscope.app

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/** Anonymous browser-engine image comparison, using the last verified app records. */
class BrowserMediaProbeTest {
    @Test fun sourceArtworkThroughAnonymousPhoneBrowserEngine() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("browserMedia")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=MatchRepository(context,BrowserSession()) { RelayConfig(false,"") }
        val player=repository.restoredPlayer("19230")!!
        val event=repository.restoredEvent("8932")!!
        val executor=withContext(Dispatchers.Main) { GeckoWebExecutor(PhoneBrowserRuntime.get(context)) }
        val proof=JSONObject().put("startedAt",Instant.now().toString()).put("version",BuildConfig.VERSION_NAME)
            .put("transport","GECKO_ANONYMOUS").put("relayEnabled",false).put("metadataOrigin","CACHE")
            .put("playerMetadataAt",player.stamp.capturedAt.toString()).put("eventMetadataAt",event.stamp.capturedAt.toString())
        val rows=JSONArray();proof.put("images",rows)
        val p=player.value
        val sources=listOf("team" to p.team?.logoUrl,"portrait" to p.player.photoUrl,"event-banner" to event.value.bannerUrl,
            "trophy" to p.content.honors.first { it.category=="冠军" }.imageUrl,
            "mvp-medal" to p.content.honors.first { it.category=="MVP" }.imageUrl,
            "top20" to p.content.honors.first { it.category=="Top 20" }.imageUrl,"news" to p.content.news.first().imageUrl)
        try {
            withContext(Dispatchers.IO) {
                for ((name,source) in sources) {
                    val url=MediaUrls.validated(source)!!
                    val row=JSONObject().put("name",name).put("url",url);rows.put(row)
                    try {
                        val pending=executor.fetch(WebRequest.Builder(url).header("Accept","image/webp,image/png,image/jpeg,image/svg+xml;q=0.9,*/*;q=0.5")
                            .header("Cache-Control","no-cache").build(),GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS or GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS)
                        val response=try { pending.poll(25_000)!! } catch (e:Exception) { pending.cancel(); throw e }
                        response.setReadTimeoutMillis(15_000)
                        row.put("status",response.statusCode).put("responseUrl",response.uri).put("secure",response.isSecure)
                        for ((key,value) in response.headers) if (key.lowercase() in setOf("content-type","cf-mitigated","server")) row.put(key.lowercase(),value)
                        val output=ByteArrayOutputStream()
                        response.body?.use { input ->
                            val buffer=ByteArray(8192)
                            while(true) { val n=input.read(buffer);if(n<0) break;check(output.size()+n<=8*1024*1024);output.write(buffer,0,n) }
                        }
                        val bytes=output.toByteArray()
                        val bitmap=if(response.statusCode==200 && response.uri==url) BitmapFactory.decodeByteArray(bytes,0,bytes.size) else null
                        row.put("bytes",bytes.size).put("decoded",bitmap!=null)
                        bitmap?.let { row.put("width",it.width).put("height",it.height);it.recycle() }
                        if(response.statusCode!=200) row.put("errorPreview",bytes.toString(Charsets.UTF_8).take(160))
                    } catch(e:Exception) { row.put("error",e.toString()) }
                }
            }
            val failed=(0 until rows.length()).count { !rows.getJSONObject(it).optBoolean("decoded") }
            proof.put("failed",failed).put("outcome",if(failed==0) "PASSED" else "PARTIAL")
            assertEquals("Inspect browser-media-proof.json",0,failed)
        } finally {
            proof.put("finishedAt",Instant.now().toString())
            File(context.filesDir,"browser-media-proof.json").writeText(proof.toString(2))
        }
    }
}
