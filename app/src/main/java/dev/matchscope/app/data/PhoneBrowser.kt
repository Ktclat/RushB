package dev.matchscope.app.data

import android.content.Context
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError

/** One private on-device browser profile. Its normal cookies never leave the engine. */
internal object PhoneBrowserRuntime {
    private var instance: GeckoRuntime? = null
    fun get(context: Context): GeckoRuntime = instance ?: GeckoRuntime.create(context.applicationContext).also { instance = it }
}

enum class BrowserPageState { LOADING, READY, CHALLENGE, BLOCKED }

/** All methods and delegates run on the UI thread, like GeckoView's session API. */
class PhoneBrowser(
    context: Context,
    private val request: BrowserRequest,
    private val onStatus: (String, Boolean) -> Unit,
    private val onDocument: (String) -> Unit,
    private val onFailure: (String, Long, MobileFailure?) -> Unit,
    private val onPageState: (BrowserPageState) -> Unit = {},
) {
    private val runtime = PhoneBrowserRuntime.get(context)
    private val page = GeckoSession()
    private var port: WebExtension.Port? = null
    private var closed = false
    private var capturing = false
    private var ready = false
    private var accessState = BrowserPageState.LOADING
    private fun access(state: BrowserPageState) { accessState = state; onPageState(state) }

    init {
        page.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) { if (!closed) onFailure("网页加载中断，请重试。", 30_000, MobileFailure.NETWORK) }
        }
        page.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, load: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                GeckoResult.fromValue(if (HltvAccess.allowed(load.uri)) AllowOrDeny.ALLOW else AllowOrDeny.DENY)

            // HTTPS subresources and the site's verification iframe use normal engine defaults.
            // No new windows, external intents, permissions or certificate exceptions are granted.
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (!closed) onFailure("连接失败，请检查网络后重试。", 30_000, MobileFailure.NETWORK)
                return null
            }
        }
        page.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                capturing = false
                if (!closed) { access(BrowserPageState.LOADING); onStatus("正在连接…", true) }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) { if (!closed) capture() }
        }
        page.open(runtime)
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/hltv-reader/", "reader@rushb.local").accept({ extension ->
            if (closed || extension == null) return@accept
            page.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
                override fun onConnect(connection: WebExtension.Port) {
                    val sender = connection.sender
                    if (closed || sender.session !== page || !sender.isTopLevel ||
                        sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                        !HltvAccess.sameResource(request.url, sender.url)) {
                        connection.disconnect()
                        return
                    }
                    port?.disconnect()
                    port = connection
                    capturing = false
                    connection.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, source: WebExtension.Port) {
                            if (closed || source !== port || message !is JSONObject || message.optLong("requestId") != request.id) return
                            capturing = false
                            if (!HltvAccess.sameResource(request.url, message.optString("url"))) {
                                onStatus("页面已跳转，请重新加载。", false)
                                return
                            }
                            when (message.optString("state")) {
                                "blocked" -> {
                                    access(BrowserPageState.BLOCKED)
                                    if (request.interactive) onStatus("网站仍拒绝访问，请稍后再试。", false)
                                    else onFailure("HLTV 暂时拒绝访问，请稍后重试。", 300_000, MobileFailure.BLOCKED)
                                }
                                "challenge" -> { access(BrowserPageState.CHALLENGE); onStatus("请完成网页验证", false) }
                                "ready" -> { access(BrowserPageState.READY); onStatus("网页已打开", false) }
                                "loading" -> onStatus("加载中…", true)
                                "oversize" -> onFailure("来源页面超过大小限制。", 60_000, MobileFailure.OVERSIZE)
                                "json-response" -> if (request.jsonUrl != null && message.optString("jsonUrl") == request.jsonUrl) onDocument(message.toString())
                                "json-network-error" -> onFailure("连接失败，请稍后重试。", 30_000, MobileFailure.NETWORK)
                                "document" -> {
                                    val html = message.optString("html")
                                    val validRss = request.url == "https://www.hltv.org/rss/news" &&
                                        runCatching { NewsParser.rss(html).isNotEmpty() }.getOrDefault(false)
                                    if (request.jsonUrl == null && (html.length in 500..8 * 1024 * 1024 || validRss)) onDocument(html)
                                    else onStatus("页面未就绪，请稍后重试。", false)
                                }
                            }
                        }
                        override fun onDisconnect(source: WebExtension.Port) { if (port === source) { port = null; capturing = false } }
                    })
                    capture()
                }
            }, "rushb_reader")
            ready = true
            page.load(GeckoSession.Loader().uri(request.url).flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE))
        }, { _ -> if (!closed) onFailure("浏览器启动失败，请重启应用。", 30_000, MobileFailure.NETWORK) })
    }

    fun attach(view: GeckoView) { view.setSession(page) }
    fun capture(finishInteractive: Boolean = false) {
        if (closed || capturing || accessState == BrowserPageState.BLOCKED) return
        if (finishInteractive && port == null) {
            onStatus("页面尚未就绪，请稍后重试。", false)
            return
        }
        port?.let { capturing = true; it.postMessage(JSONObject().put("type", "capture").put("requestId", request.id).put("jsonUrl", request.jsonUrl)
            .put("inspectOnly", request.interactive && !finishInteractive)
            .put("rssUrl", request.url.takeIf { it == "https://www.hltv.org/rss/news" })) }
    }
    fun returnToTarget() { if (ready && !closed) page.load(GeckoSession.Loader().uri(request.url).flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)) }
    fun close() { closed = true; port?.disconnect(); port = null; page.stop(); page.close() }
}
