package dev.matchscope.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import dev.matchscope.app.data.MobileTransport
import dev.matchscope.app.data.MobileFailure
import dev.matchscope.app.ui.ScopeViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class WebsiteRecoveryTest {
    @Test fun aBlockedManualVisitPreservesTheArticleFailureAndDoesNotRetry() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[ScopeViewModel::class.java]
        }
        val url = "https://www.hltv.org/news/9876544/test-rejected"
        var retries = 0
        try {
            val oldMode = vm.state.value.mobileTransport
            withContext(Dispatchers.Main) { vm.loginAndRetry(url, mobileData = false) { retries++ } }
            val manual = withTimeout(5_000) { vm.browser.request.first { it != null }!! }
            withContext(Dispatchers.Main) { vm.browser.fail(manual.id, "HLTV 拒绝访问", 300_000, MobileFailure.BLOCKED) }
            withTimeout(5_000) { vm.state.first { it.articleFailures[url] != null } }
            assertEquals("网站仍拒绝访问，请稍后再试。", vm.state.value.articleFailures[url])
            assertEquals(0, retries)
            assertEquals(oldMode, vm.state.value.mobileTransport)
            assertNull(vm.browser.request.value)
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }

    @Test fun completingWebsiteRecoveryKeepsHttpsAndCancellingArticleReleasesItsLoadingState() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("matchscope", 0)
        val oldMode = prefs.getString("mobileTransport", null)
        val oldWebsite = if (prefs.contains("useWebsiteSession")) prefs.getBoolean("useWebsiteSession", false) else null
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[ScopeViewModel::class.java]
        }
        var retries = 0
        val url = "https://www.hltv.org/news/9876543/test-cancel"
        try {
            withContext(Dispatchers.Main) { vm.configureMobileMode(MobileTransport.HTTPS); vm.loginAndRetry(url, mobileData = false) { retries++ } }
            val manual = withTimeout(5_000) { vm.browser.request.first { it != null }!! }
            assertTrue(manual.interactive)
            withContext(Dispatchers.Main) { vm.browser.complete(manual.id, "done") }
            withTimeout(5_000) { while (withContext(Dispatchers.Main) { retries == 0 }) delay(10) }
            assertEquals(MobileTransport.HTTPS, vm.state.value.mobileTransport)
            assertTrue(prefs.getBoolean("useWebsiteSession", false))
            withContext(Dispatchers.Main) { vm.loadArticle(url, true) }
            val read = withTimeout(5_000) { vm.browser.request.first { it != null }!! }
            assertFalse(read.interactive)
            assertEquals(url, vm.state.value.loadingArticle)
            withContext(Dispatchers.Main) { vm.browser.cancel(read.id) }
            withTimeout(5_000) { vm.state.first { !it.busy } }
            assertNull(vm.state.value.loadingArticle)
            assertEquals("读取已取消", vm.state.value.articleFailures[url])
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            prefs.edit().apply {
                if (oldMode == null) remove("mobileTransport") else putString("mobileTransport", oldMode)
                if (oldWebsite == null) remove("useWebsiteSession") else putBoolean("useWebsiteSession", oldWebsite)
            }.commit()
        }
    }
}
