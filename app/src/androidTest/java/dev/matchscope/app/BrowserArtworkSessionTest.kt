package dev.matchscope.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.mozilla.geckoview.*
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** A visible normal image navigation. Does not click challenges or export session state. */
class BrowserArtworkSessionTest {
    @get:Rule val app=createComposeRule()
    @Test fun verifiedImageNavigationCanBeReadInsideTheSamePhoneEngine() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("browserArtworkSession")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=MatchRepository(context,BrowserSession()) { RelayConfig(false,"") }
        val player=repository.restoredPlayer("19230")!!.value
        val url=MediaUrls.validated(player.team!!.logoUrl)!!
        val ready=AtomicBoolean(false)
        var title=""
        lateinit var page:GeckoSession
        lateinit var executor:GeckoWebExecutor
        val proof=JSONObject().put("startedAt",Instant.now().toString()).put("source",url).put("relayEnabled",false)
        app.setContent { AndroidView(modifier=Modifier.fillMaxSize(),factory={ ctx ->
            val runtime=PhoneBrowserRuntime.get(ctx)
            executor=GeckoWebExecutor(runtime)
            page=GeckoSession().apply {
                contentDelegate=object:GeckoSession.ContentDelegate {
                    override fun onTitleChange(session:GeckoSession,value:String?) {
                        title=value.orEmpty()
                        if (title.contains(".png",true) && !title.contains("moment",true)) ready.set(true)
                    }
                }
                navigationDelegate=object:GeckoSession.NavigationDelegate {
                    override fun onLoadRequest(session:GeckoSession,request:GeckoSession.NavigationDelegate.LoadRequest):GeckoResult<AllowOrDeny> =
                        GeckoResult.fromValue(if(MediaUrls.validated(request.uri)!=null) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
                open(runtime)
            }
            GeckoView(ctx).apply { setSession(page);page.load(GeckoSession.Loader().uri(url).flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)) }
        }) }
        try {
            val until=System.currentTimeMillis()+60_000
            while(!ready.get() && System.currentTimeMillis()<until) { app.mainClock.advanceTimeBy(50);delay(100) }
            proof.put("title",title).put("imageDocument",ready.get())
            app.saveEvidence("v062-browser-image-navigation")
            assertTrue("Image navigation did not reach an image document",ready.get())
            withContext(Dispatchers.IO) {
                val pending=executor.fetch(WebRequest.Builder(url).header("Accept","image/png").build(),GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS)
                val response=try { pending.poll(25_000)!! } catch(e:Exception) { pending.cancel();throw e }
                response.setReadTimeoutMillis(15_000)
                proof.put("status",response.statusCode)
                val bytes=response.body?.use { input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) { val n=input.read(buffer);if(n<0)break;check(output.size()+n<8*1024*1024);output.write(buffer,0,n) };output.toByteArray()
                } ?: ByteArray(0)
                val bitmap=if(response.statusCode==200) BitmapFactory.decodeByteArray(bytes,0,bytes.size) else null
                proof.put("bytes",bytes.size).put("decoded",bitmap!=null)
                bitmap?.let { proof.put("width",it.width).put("height",it.height);it.recycle() }
                assertNotNull("Same-engine fetch did not decode",bitmap)
            }
            proof.put("outcome","PASSED")
        } catch(e:Throwable) { proof.put("outcome","FAILED").put("error",e.toString());throw e }
        finally {
            proof.put("finishedAt",Instant.now().toString())
            File(context.filesDir,"browser-artwork-session-proof.json").writeText(proof.toString(2))
            app.runOnUiThread { page.close() }
        }
    }
}
