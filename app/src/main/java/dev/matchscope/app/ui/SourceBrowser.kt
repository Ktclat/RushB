package dev.matchscope.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.matchscope.app.data.BrowserSession
import dev.matchscope.app.data.PhoneBrowser
import dev.matchscope.app.data.BrowserPageState
import dev.matchscope.app.data.MobileFailure
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoView

@Composable
fun SourceBrowser(session: BrowserSession) {
    val request by session.request.collectAsState()
    val backgroundColor = Background.toArgb()
    request?.let { pending -> key(pending.id) {
        var status by remember { mutableStateOf("正在连接…") }
        var loading by remember { mutableStateOf(true) }
        var pageState by remember { mutableStateOf(BrowserPageState.LOADING) }
        val context = LocalContext.current
        val browser = remember(pending.id) {
            PhoneBrowser(context, pending,
                onStatus = { text, busy -> status = text; loading = busy },
                onDocument = { session.complete(pending.id, it) },
                onFailure = { text, cooldown, reason -> session.fail(pending.id, text, cooldown, reason) },
                onPageState = { pageState = it })
        }
        DisposableEffect(browser) { onDispose { browser.close() } }
        LaunchedEffect(browser) {
            if (pending.interactive) {
                // Inspect the already open DOM without completing or reloading the page.
                while (true) { delay(1_500); browser.capture() }
            }
            repeat(60) { delay(1_500); browser.capture() }
            loading = false
            status = "等待超时，请完成验证后重试。"
        }
        val close = {
            if (pageState == BrowserPageState.BLOCKED) session.fail(pending.id, "网站仍拒绝访问，请稍后再试。", 300_000, MobileFailure.BLOCKED)
            else session.cancel(pending.id)
        }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize().systemBarsPadding(), color = Background) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("HLTV", fontSize = 16.sp)
                            Text(java.net.URI(pending.url).host.orEmpty(), fontSize = 11.sp, color = Quiet, maxLines = 1)
                        }
                        TextButton(onClick = close) { Text("关闭") }
                    }
                    if (pageState != BrowserPageState.READY) Text(status, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { GeckoView(it).apply { browser.attach(this) } },
                            update = { it.setBackgroundColor(backgroundColor) })
                        if (loading) Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                        }
                    }
                    SourceBrowserActions(pageState, loading, pending.interactive,
                        reload = { browser.returnToTarget() }, finish = { browser.capture(finishInteractive = pending.interactive) }, close = close)
                }
            }
        }
    } }
}

@Composable
internal fun SourceBrowserActions(state: BrowserPageState, loading: Boolean, interactive: Boolean, reload: () -> Unit, finish: () -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        if (state == BrowserPageState.BLOCKED) FilledTonalButton(close) { Text("返回") }
        else {
            TextButton(reload) { Text("重新加载") }
            if (!loading && state == BrowserPageState.READY) FilledTonalButton(finish) { Text(if (interactive) "完成并重试" else "完成验证") }
        }
    }
}
