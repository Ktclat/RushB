package dev.matchscope.app.data

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Only source-observed variants of the same host/path; signed queries remain untouched. */
object MediaAssets {
    private val assets = linkedMapOf<String, List<String>>()
    private var file: File? = null
    private var dirty = false
    private fun key(url: String): String = URI(url).let { "${it.host}${it.path}" }
    @Synchronized fun open(location: File) {
        if (file != null) return
        file = location
        runCatching {
            val saved = Properties().apply { if (location.isFile) location.inputStream().use(::load) }
            saved.stringPropertyNames().take(512).forEach { k ->
                val variants = saved.getProperty(k).split('\n').mapNotNull(MediaUrls::validated).filter { key(it) == k }
                assets[k] = (assets[k].orEmpty() + variants).distinct().take(4)
            }
        }
    }
    @Synchronized fun remember(urls: List<String>) {
        urls.mapNotNull(MediaUrls::validated).groupBy(::key).forEach { (key, values) ->
            val variants = (values + assets[key].orEmpty()).distinct().take(4)
            if (assets[key] != variants) { assets.remove(key); assets[key] = variants; dirty = true }
        }
        while (assets.size > 512) assets.remove(assets.keys.first())
    }
    @Synchronized fun variants(url: String): List<String> = assets[key(url)].orEmpty()
    @Synchronized fun flush() {
        val target = file ?: return
        if (!dirty) return
        runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            val saved = Properties().apply { assets.forEach { (key,value) -> setProperty(key,value.joinToString("\n")) } }
            temporary.outputStream().use { saved.store(it,"Source image variants") }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
            dirty = false
        }
    }
}
