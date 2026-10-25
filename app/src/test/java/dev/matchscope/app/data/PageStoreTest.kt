package dev.matchscope.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `captured page can be parsed during cooldown without issuing or enabling another request`() = runBlocking {
        var recovered: PageBody? = null
        var calls = 0
        val store = PageStore(directory.newFolder(), { 100_000 }, {
            calls++; throw SourceUnavailable("HTTP 429", 300_000, sharedCooldown = true)
        }, 0, recoveredPage = { recovered.also { recovered = null } })
        val url = "https://www.hltv.org/news/123/article"
        try { store.read(url, 0) { it }; fail() } catch (_: SourceUnavailable) { }
        recovered = PageBody("captured article")
        val current = store.read(url, 0, forceRefresh = true) { it }
        assertEquals("captured article", current.value)
        assertFalse(current.cached)
        assertEquals(1, calls)
        val next = store.read(url, 0, forceRefresh = true) { it }
        assertTrue(next.cached)
        assertNotNull(next.warning)
        assertEquals(1, calls)
    }

    @Test fun `invalid captured page cannot overwrite good data or its timestamp`() = runBlocking {
        var time = 100_000L
        var recovered: PageBody? = null
        var blocked = false
        var calls = 0
        val store = PageStore(directory.newFolder(), { time }, {
            calls++
            if (blocked) throw SourceUnavailable("HTTP 403", 300_000)
            PageBody("valid article")
        }, 0, recoveredPage = { recovered.also { recovered = null } })
        val parse: (String) -> String = { if (it != "valid article") throw SourceUnavailable("invalid article") else it }
        val url = "https://www.hltv.org/news/123/article"
        val first = store.read(url, 0, parse = parse)
        time += 5_000; blocked = true
        store.read(url, 0, forceRefresh = true, parse = parse)
        recovered = PageBody("challenge page")
        val result = store.read(url, 0, forceRefresh = true, parse = parse)
        assertEquals(first.value, result.value)
        assertEquals(first.at, result.at)
        assertTrue(result.cached)
        assertNotNull(result.warning)
        assertEquals(2, calls)
    }

    @Test fun `rate limit cooldown remains shared when switching transport`() = runBlocking {
        var scope = "http"
        var calls = 0
        val store = PageStore(directory.newFolder(), { 100_000 }, {
            calls++; throw SourceUnavailable("HTTP 429", 300_000, sharedCooldown = true)
        }, 0, { scope })
        repeat(2) {
            try { store.read("https://www.hltv.org/rss/news", 0) { it }; fail() } catch (_: SourceUnavailable) { }
            scope = "browser"
        }
        assertEquals(1, calls)
    }

    @Test fun `HTTP rejection does not block user browser session and each transport retains cooldown`() = runBlocking {
        var scope = "http"
        var calls = 0
        val store = PageStore(directory.newFolder(), { 100_000 }, {
            calls++
            if (scope == "http") throw SourceUnavailable("HTTP 403", 300_000)
            PageBody("browser page")
        }, 0, { scope })
        val url = "https://www.hltv.org/news/123/example"
        try { store.read(url, 0) { it }; fail() } catch (_: SourceUnavailable) { }
        scope = "browser"
        assertEquals("browser page", store.read(url, 0) { it }.value)
        scope = "http"
        val cached = store.read(url, 0, forceRefresh = true) { it }
        assertTrue(cached.cached); assertNotNull(cached.warning)
        assertEquals(2, calls)
    }

    @Test fun `schema upgrade reparses cached page without network or changing timestamp`() = runBlocking {
        var time = 100_000L
        var calls = 0
        val store = PageStore(directory.newFolder(), { time }, { calls++; PageBody("source body") }, 0)
        val url = "https://www.hltv.org/events/8932/thunderpick-world-championship-2026"
        val original = store.read(url, 1_000) { it }
        time += 30_000
        val restored = store.cached(url) { it.uppercase() }!!
        assertEquals("SOURCE BODY", restored.value)
        assertEquals(original.at, restored.at)
        assertTrue(restored.cached)
        assertEquals(1, calls)
        assertNull(store.cached(url) { throw SourceUnavailable("changed structure") })
    }

    @Test fun `manual refresh bypasses fresh cache but cannot bypass verification cooldown`() = runBlocking {
        var time = 100_000L
        var calls = 0
        var blocked = false
        val store = PageStore(directory.newFolder(), { time }, {
            calls++
            if(blocked) throw SourceUnavailable("verification", 300_000)
            PageBody("version-$calls")
        }, 0)
        val url = "https://www.hltv.org/player/19230/m0nesy"
        store.read(url, 3_600_000) { it }
        time += 1_000
        assertEquals("version-1", store.read(url, 3_600_000) { it }.value)
        val fresh = store.read(url, 3_600_000, forceRefresh = true) { it }
        assertEquals("version-2", fresh.value)
        assertFalse(fresh.cached)
        blocked = true; time += 1_000
        val fallback = store.read(url, 3_600_000, forceRefresh = true) { it }
        assertTrue(fallback.cached)
        assertEquals(fresh.at, fallback.at)
        assertNotNull(fallback.warning)
        store.read(url, 3_600_000, forceRefresh = true) { it }
        assertEquals(3, calls)
    }

    @Test fun `challenge discovered after download keeps old cache and applies host cooldown`() = runBlocking {
        var time = 100_000L
        var calls = 0
        var blocked = false
        val store = PageStore(directory.newFolder(), { time }, { calls++; PageBody(if(blocked) "challenge" else "verified") }, 0)
        fun parse(raw: String): String {
            if(raw == "challenge") throw SourceUnavailable("verification", 300_000)
            return raw
        }
        store.read("https://www.hltv.org/matches", 30_000, parse = ::parse)
        blocked = true; time += 60_000
        val old = store.read("https://www.hltv.org/matches", 30_000, parse = ::parse)
        assertEquals(100_000L, old.at.toEpochMilli())
        assertTrue(old.cached)
        assertNotNull(old.warning)
        time += 1_000
        store.read("https://www.hltv.org/matches", 30_000, parse = ::parse)
        assertEquals(2, calls)
        blocked = false; time += 300_000
        val fresh = store.read("https://www.hltv.org/matches", 30_000, parse = ::parse)
        assertFalse(fresh.cached)
        assertEquals(time, fresh.at.toEpochMilli())
        assertEquals(3, calls)
    }

    @Test fun `cache survives new store instance without another request`() = runBlocking {
        var calls = 0
        val folder = directory.newFolder()
        val first = PageStore(folder, { 100_000 }, { calls++; PageBody("verified") }, 0)
        assertFalse(first.read("https://www.hltv.org/matches", 60_000) { it }.cached)
        val second = PageStore(folder, { 120_000 }, { calls++; error("should not fetch") }, 0)
        val page = second.read("https://www.hltv.org/matches", 60_000) { it }
        assertEquals("verified", page.value)
        assertTrue(page.cached)
        assertEquals(1, calls)
    }

    @Test fun `parse failure cannot overwrite an expired successful cache`() = runBlocking {
        var time = 100_000L
        var body = "verified"
        val folder = directory.newFolder()
        val store = PageStore(folder, { time }, { PageBody(body) }, 0)
        store.read("https://www.hltv.org/matches", 30_000) { it }
        time += 60_000; body = "challenge"
        val fallback = store.read("https://www.hltv.org/matches", 30_000) { if(it == "challenge") throw SourceUnavailable("blocked") else it }
        assertEquals("verified", fallback.value)
        assertEquals(100_000L, fallback.at.toEpochMilli())
        assertTrue(fallback.cached)
        assertTrue(fallback.warning!!.contains("blocked"))
        assertEquals("verified", folder.listFiles()!!.single { it.extension == "txt" }.readText())
    }

    @Test fun `403 cooldown suppresses rapid repeated requests`() = runBlocking {
        var calls = 0
        val store = PageStore(directory.newFolder(), { 100_000 }, { calls++; throw SourceUnavailable("HTTP 403", 300_000) }, 0)
        repeat(2) {
            try { store.read("https://www.hltv.org/matches", 0) { it }; fail("Expected failure") }
            catch (_: SourceUnavailable) { }
        }
        assertEquals(1, calls)
    }

    @Test fun `stale data past its retention window cannot be returned as fresh`() = runBlocking {
        var time = 100_000L
        var fail = false
        val store = PageStore(directory.newFolder(), { time }, { if(fail) throw SourceUnavailable("offline") else PageBody("verified") }, 0)
        store.read("https://www.hltv.org/matches", 1_000) { it }
        time += 61_000; fail = true
        try { store.read("https://www.hltv.org/matches", 1_000, 60_000) { it }; fail("Must reject old data") }
        catch(e: SourceUnavailable) { assertEquals("offline", e.message) }
    }

    @Test fun `relay capture time is retained across process restarts`() = runBlocking {
        val folder = directory.newFolder()
        val store = PageStore(folder, { 120_000 }, { PageBody("verified", java.time.Instant.ofEpochMilli(100_000), true) }, 0)
        val first = store.read("https://www.hltv.org/matches", 60_000) { it }
        assertTrue(first.cached)
        assertEquals(100_000L, first.at.toEpochMilli())
        val second = PageStore(folder, { 130_000 }, { error("fresh cache should avoid network") }, 0)
        assertEquals(first.at, second.read("https://www.hltv.org/matches", 60_000) { it }.at)
    }

    @Test fun `cancellation does not fall back or poison cached data`() = runBlocking {
        val folder = directory.newFolder()
        PageStore(folder, { 100_000 }, { PageBody("verified") }, 0).read("https://www.hltv.org/matches", 0) { it }
        val cancelled = PageStore(folder, { 120_000 }, { throw CancellationException("cancel") }, 0)
        try { cancelled.read("https://www.hltv.org/matches", 0) { it }; fail("Must cancel") }
        catch (_: CancellationException) { }
        assertEquals("verified", folder.listFiles()!!.single { it.extension == "txt" }.readText())
    }
}
