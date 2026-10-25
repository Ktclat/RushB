package dev.matchscope.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

class MobileExtrasCaptureTest {
    @Test fun captureNewsAndBracketDocuments() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mobileExtras") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val urls = linkedMapOf("team-news" to "https://www.hltv.org/mobile/TeamNews?teamId=11283", "player-news" to "https://www.hltv.org/mobile/PlayerNews?playerId=19230")
        for (id in listOf(5359, 5360, 5361, 5362)) urls["bracket-$id"] = "https://www.hltv.org/mobile/embeds/Brackets?eventId=8932&bracketId=$id"
        val receipts = JSONArray()
        var errors = 0
        for ((name, url) in urls) {
            val receipt = JSONObject().put("url", url).put("at", Instant.now().toString())
            try {
                val body = withContext(Dispatchers.IO) { fetchHttps(url) }
                File(context.filesDir, "mobile-extra-$name.txt").writeText(body)
                receipt.put("outcome", "READ").put("bytes", body.toByteArray().size)
            } catch (e: Exception) { errors++; receipt.put("outcome", "FAILED").put("error", e.message) }
            receipts.put(receipt); delay(2_000)
        }
        File(context.filesDir, "mobile-extras-proof.json").writeText(receipts.toString(2))
        assertEquals(0, errors)
    }
}
