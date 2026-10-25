package dev.matchscope.app.data

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import coil3.svg.SvgDecoder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody

object MediaLoader {
    private val revision = MutableStateFlow(0L)
    val retries = revision.asStateFlow()
    private var lastRetry = 0L
    @Synchronized fun retryFailed() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRetry < 2_000) return
        lastRetry = now
        revision.value++
    }
    fun cacheBytes(context: android.content.Context): Long = SingletonImageLoader.get(context).diskCache?.size ?: 0L

    fun clearImageCache(context: android.content.Context) {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    private var installed = false
    @Synchronized fun install() {
        if (installed) return
        SingletonImageLoader.setSafe { app ->
            MediaAssets.open(java.io.File(app.filesDir, "artwork-variants.properties"))
            val denied = ArtworkCooldown()
            val browser = BrowserImageTransport(app)
            val preferences = app.getSharedPreferences("matchscope", 0)
            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 3 })
                .addInterceptor { chain ->
                    if (MediaUrls.validated(chain.request().url.toString()) == null) throw IOException("Unsupported image source")
                    val url = chain.request().url.toString()
                    val useBrowser = preferences.getBoolean("useWebsiteSession", false) || preferences.getString("mobileTransport", "HTTPS") == "BROWSER"
                    val access = if (useBrowser) "BROWSER" else "HTTPS"
                    if (denied.blocked(url, access)) throw IOException("Image temporarily unavailable")
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "RushB/${dev.matchscope.app.BuildConfig.VERSION_NAME} (independent personal open-source client)")
                        .header("Accept", "image/webp,image/svg+xml,image/png,image/jpeg;q=0.9,*/*;q=0.5").build()
                    val response = if (useBrowser) browser.get(request) { chain.call().isCanceled() } else chain.proceed(request)
                    denied.record(url, access, response.code, response.header("Retry-After"))
                    if (useBrowser) response else response.use {
                        val type = it.header("Content-Type")
                        val bytes = ImageResponseBody.read(it.code, type, it.header("Content-Length")?.toLongOrNull(), it.body?.byteStream()) {
                            if (chain.call().isCanceled()) throw IOException("Image request cancelled")
                        }
                        it.newBuilder().body(bytes.toResponseBody(type?.toMediaTypeOrNull())).build()
                    }
                }.build()
            ImageLoader.Builder(app)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })); add(SvgDecoder.Factory()) }
                .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.12).build() }
                .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("identity-images").toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build() }
                .build()
        }
        installed = true
    }
}
