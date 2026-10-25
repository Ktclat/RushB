package dev.matchscope.app.data

import java.net.URI

/** Website slugs can be canonicalized, but verification/login/unrelated pages must
 * never be substituted for the requested document. Mobile JSON remains exact-URL. */
internal suspend fun readPublicDocument(url: String, fetch: suspend (String) -> MobileResponse): String {
    require(HltvAccess.allowed(url))
    var current = url
    repeat(4) {
        val response = fetch(current)
        if (!HltvAccess.sameResource(url, response.url)) throw SourceUnavailable("返回页面不匹配", 60_000)
        if (response.status in setOf(301, 302, 303, 307, 308)) {
            val location = response.headers["location"] ?: throw SourceUnavailable("网页跳转地址缺失", 30_000)
            val next = runCatching { URI(current).resolve(location).toString() }.getOrNull()
            if (next == null || !HltvAccess.sameResource(url, next)) throw SourceUnavailable("网页跳转不匹配", 60_000)
            current = next
            return@repeat
        }
        if (response.status == 403 || response.headers["cf-mitigated"] == "challenge") throw SourceUnavailable("网站暂时限制访问", 300_000)
        if (response.status == 429) throw SourceUnavailable("请求过于频繁", MobileResponsePolicy.retryDelay(response.headers["retry-after"]), true)
        if (response.status !in 200..299 || !response.headers["content-type"].orEmpty().startsWith("text/html", true)) throw SourceUnavailable("网页暂不可用", 30_000)
        return response.body
    }
    throw SourceUnavailable("网页跳转次数过多", 30_000)
}
