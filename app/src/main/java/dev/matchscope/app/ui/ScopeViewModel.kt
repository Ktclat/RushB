package dev.matchscope.app.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.matchscope.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class ScopeState(
    val matches: Feed<Match> = SeedSnapshot.matches,
    val rankings: Feed<RankingEntry> = SeedSnapshot.rankings.copy(items = emptyList(), warning = null),
    val vrs: Map<VrsRegion, Feed<RankingEntry>> = emptyMap(),
    val news: Feed<NewsItem>? = null,
    val newsMonth: YearMonth = YearMonth.now(),
    val newsFailure: String? = null,
    val articleFailures: Map<String, String> = emptyMap(),
    val loadingArticle: String? = null,
    val articles: Map<String, Sourced<NewsArticle>> = emptyMap(),
    val eventIndex: Feed<Tournament>? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val requestFailed: Boolean = false,
    val followedTeams: Set<String> = emptySet(),
    val savedMatches: Set<String> = emptySet(),
    val timezone: String = ZoneId.systemDefault().id,
    val relayEnabled: Boolean = false,
    val mobileConnection: String? = null,
    val mobileTransport: MobileTransport = MobileTransport.HTTPS,
    val mobileIssue: MobileAccessIssue? = null,
    val savedRecords: List<Match> = emptyList(),
    val matchDetails: Map<String, Match> = emptyMap(),
    val matchStatistics: Map<String, Match> = emptyMap(),
    val statisticsLoading: Set<String> = emptySet(),
    val statisticsFailures: Map<String, String> = emptyMap(),
    val players: Map<String, Sourced<PlayerProfile>> = emptyMap(),
    val teamProfiles: Map<String, Sourced<TeamProfile>> = emptyMap(),
    val eventProfiles: Map<String, Sourced<Tournament>> = emptyMap(),
    val followedPlayers: List<PlayerRef> = emptyList(),
    val matchDuels: Map<String, Sourced<MatchDuels>> = emptyMap(),
    val matchHistories: Map<String, Sourced<MatchHistoryPage>> = emptyMap(),
    val historyFailures: Map<String, String> = emptyMap(),
    val liveScoreFailures: Map<String, String> = emptyMap(),
    val uiPreferences: UiPreferences = UiPreferences(),
    val imageCacheBytes: Long? = null,
    val cleaningImages: Boolean = false,
    val imageCacheMessage: String? = null,
    val rankingFailure: String? = null,
    val vrsFailure: String? = null,
    val library: LocalLibrary = LocalLibrary(),
    val libraryReady: Boolean = false,
    val networkAvailable: Boolean? = null,
) {
    val zone: ZoneId get() = ZoneId.of(timezone)
    val zoneLabel: String get() = when(timezone) { "Asia/Hong_Kong" -> "UTC+8"; "UTC" -> "UTC"; else -> "设备时区" }
    private val profileContent: List<ProfileContent> by lazy { players.values.map { it.value.content } + teamProfiles.values.map { it.value.content } }
    private val profileMatchRecords: List<Match> by lazy {
        (players.values.map { it.value.content to it.stamp } + teamProfiles.values.map { it.value.content to it.stamp })
            .flatMap { (content, stamp) -> content.matches.mapNotNull { it.asMatch()?.copy(stamp = stamp) } }
    }
    val teams: List<Team> by lazy {
        val known = (teamProfiles.values.map { it.value.team } + eventProfiles.values.flatMap { it.value.content.teams } + rankings.items.map { it.team } + players.values.mapNotNull { it.value.team } + allMatches.flatMap { listOf(it.left, it.right) } + profileContent.flatMap { c -> c.matches.flatMap { listOf(it.left, it.right) } + c.career.map { it.team } + c.events.mapNotNull { it.team } } + library.teams + SeedSnapshot.teams)
            .filterNot { it.id.startsWith("pending:") }.groupBy { it.id }.values.map { versions ->
                versions.first().copy(region = versions.firstOrNull { it.region != "未分类" }?.region ?: "未分类",
                    roster = versions.firstOrNull { it.roster.isNotEmpty() }?.roster.orEmpty(), logoUrl = versions.firstNotNullOfOrNull { it.logoUrl })
            }
        val additional = vrs[VrsRegion.GLOBAL]?.items.orEmpty().map { it.team }.filter { candidate -> known.none { it.name.equals(candidate.name, true) } }
        known + additional
    }
    val allMatches: List<Match> by lazy { preferredMatchRecords(matchDetails.values + matches.items + savedRecords + library.matches + profileMatchRecords + eventProfiles.values.flatMap { it.value.content.matches } + eventProfiles.values.flatMap { profile -> profile.value.content.brackets.flatMap { it.rounds }.flatMap { it.slots }.mapNotNull { it.asMatch(profile.value)?.copy(stamp = profile.stamp) } }) }
    val playerRefs: List<PlayerRef> by lazy { (players.values.map { it.value.player } + teamProfiles.values.flatMap { it.value.players + it.value.content.roster.map { row -> row.player } } +
        players.values.flatMap { it.value.content.teammates.map { row -> row.player } + it.value.content.similarPlayers.map { row -> row.player } } +
        eventProfiles.values.flatMap { it.value.content.statistics.flatMap { group -> group.players.map { row -> row.player } } } + followedPlayers +
        allMatches.flatMap { it.players.values.flatten() + it.stats.mapNotNull { s -> s.player } } + library.players).distinctBy { it.id } }
    val events: List<Tournament> by lazy { mergedEvents(eventProfiles.values.map { it.value } + eventIndex?.items.orEmpty() + SeedSnapshot.events.items + allMatches.map { it.event } + library.events + profileContent.flatMap { c -> c.events.map { it.event } + c.matches.map { it.event } + c.career.flatMap { it.trophies } }) }
    fun hltvRank(teamId: String): Int? {
        // Keep VRS separate. Prefer the latest verified HLTV record, and the ranking feed on equal timestamps.
        val candidates = listOfNotNull(
            rankings.items.firstOrNull { it.team.id == teamId }?.let { Sourced(it.rank, rankings.stamp) },
            teamProfiles[teamId]?.let { profile -> profile.value.worldRanking?.let { Sourced(it, profile.stamp) } },
            library.teamRanks[teamId],
        ).filter { it.value > 0 }
        return candidates.maxWithOrNull(compareBy<Sourced<Int>>(
            { if (it.stamp.origin == DataOrigin.SNAPSHOT) 0 else 1 }, { it.stamp.capturedAt }
        ))?.value
    }
    fun displayTeam(team: Team): Team = team.copy(logoUrl = team.logoUrl
        ?: rankings.items.firstOrNull { it.team.logoUrl != null && (it.team.id == team.id || it.team.name.equals(team.name, true)) }?.team?.logoUrl
        ?: teams.firstOrNull { it.logoUrl != null && (it.id == team.id || it.name.equals(team.name, true)) }?.logoUrl)
    val relatedNews: List<NewsItem> by lazy { (profileContent.flatMap { it.news } + library.news)
        .filter { NewsParser.allowed(it.url) }.distinctBy { java.net.URI(it.url).path.split('/')[2] }
        .sortedByDescending { NewsParser.instant(it.publishedAt) } }
    fun articlePreview(url: String): NewsItem? = (news?.items.orEmpty() + relatedNews)
        .firstOrNull { NewsParser.allowed(it.url) && HltvAccess.sameResource(url, it.url) }
    internal fun toggleSavedMatch(id: String): ScopeState {
        val next = if (id in savedMatches) savedMatches - id else savedMatches + id
        val record = allMatches.find { it.id == id }
        return copy(savedMatches = next,
            // Restored bookmarks can be the only in-memory record of a future match.
            // Removing the bookmark must not remove that match from the current search.
            matchDetails = if (record == null) matchDetails else matchDetails + (id to record),
            savedRecords = (savedRecords.filter { it.id != id } + listOfNotNull(record)).filter { it.id in next })
    }
    fun resolveTeam(id: String): Team? {
        val team = teams.find { it.id == id } ?: return null
        return if(team.id.toLongOrNull() != null || team.id.startsWith("vrs:")) team
        else teams.filter { it.id.toLongOrNull() != null && it.name.equals(team.name, true) }.singleOrNull() ?: team
    }
    fun resolveEvent(id: String): Tournament? {
        val event = events.find { it.id == id } ?: return null
        return if(event.id.toLongOrNull() != null) event
        else events.filter { it.id.toLongOrNull() != null && it.name == event.name }.singleOrNull() ?: event
    }

}

class ScopeViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("matchscope", 0)
    private val uiPreferencesStore = UiPreferencesStore(preferences)
    val browser = BrowserSession()
    private val repository = MatchRepository(application, browser, mobileMode = { savedMobileMode() },
        pageMode = { if (preferences.getBoolean("useWebsiteSession", false)) MobileTransport.BROWSER else savedMobileMode() }) {
        RelayConfig(preferences.getBoolean("relay", false), preferences.getString("relayToken", "").orEmpty())
    }
    val mobileJson get() = repository.mobile
    private val mutable = MutableStateFlow(ScopeState(
        followedPlayers = runCatching {
            val array = org.json.JSONArray(preferences.getString("followedPlayers", "[]"))
            (0 until array.length()).map { Records.player(array.getJSONObject(it)) }
        }.getOrDefault(emptyList()),
        followedTeams = preferences.getStringSet("teams", emptySet()).orEmpty().toSet(),
        savedMatches = preferences.getStringSet("matches", emptySet()).orEmpty().toSet(),
        timezone = ZoneId.systemDefault().id,
        vrs = VrsRegion.entries.associateWith(repository::vrsSnapshot),
        relayEnabled = preferences.getBoolean("relay", false),
        mobileTransport = savedMobileMode(),
        uiPreferences = uiPreferencesStore.read(),
    ))
    val state = mutable.asStateFlow()
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private fun updateNetworkAvailability() {
        val available = connectivity?.let { manager ->
            manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        mutable.update { it.copy(networkAvailable = available) }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetworkAvailability()
        override fun onLost(network: Network) = updateNetworkAvailability()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateNetworkAvailability()
    }
    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }
    fun configureUi(value: UiPreferences) {
        uiPreferencesStore.write(value)
        mutable.update { it.copy(uiPreferences = value) }
    }
    fun measureImageCache() = viewModelScope.launch {
        val bytes = withContext(Dispatchers.IO) { MediaLoader.cacheBytes(getApplication()) }
        mutable.update { it.copy(imageCacheBytes = bytes) }
    }
    fun clearImageCache() {
        if (mutable.value.cleaningImages) return
        mutable.update { it.copy(cleaningImages = true, imageCacheMessage = null) }
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    MediaLoader.clearImageCache(getApplication())
                    MediaLoader.cacheBytes(getApplication())
                }
                mutable.update { it.copy(imageCacheBytes = bytes, imageCacheMessage = "已清理") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(imageCacheMessage = "清理失败，请重试") } }
            finally { mutable.update { it.copy(cleaningImages = false) } }
        }
    }
    private fun savedMobileMode(): MobileTransport = runCatching { MobileTransport.valueOf(preferences.getString("mobileTransport", "HTTPS")!!) }.getOrDefault(MobileTransport.HTTPS)
    init {
        updateNetworkAvailability()
        connectivity?.registerDefaultNetworkCallback(networkCallback)
        viewModelScope.launch {
            try {
                val library = withContext(Dispatchers.IO) { repository.restoredLibrary() }
                mutable.update { it.copy(library = library) }
            } finally { mutable.update { it.copy(libraryReady = true) } }
        }
        viewModelScope.launch { mobileJson.issue.collect { issue -> mutable.update { it.copy(mobileIssue = issue) } } }
        viewModelScope.launch {
            val ranking = withContext(Dispatchers.IO) { repository.restoredRankings() }
            val news = withContext(Dispatchers.IO) { repository.restoredNews() }
            mutable.update { it.copy(rankings = ranking ?: it.rankings, news = news) }
            val vrs = withContext(Dispatchers.IO) { repository.restoredVrs() }
            if (vrs != null) mutable.update { it.copy(vrs = if (it.vrs.values.all { feed -> feed.stamp.origin == DataOrigin.SNAPSHOT }) vrs else it.vrs) }
        }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { Triple(repository.restoredMatches(), repository.restoredSaved(), repository.restoredEvents()) }
            mutable.update { it.copy(matches = if(it.matches.stamp.origin == DataOrigin.SNAPSHOT) restored.first ?: it.matches else it.matches, savedRecords = restored.second, eventIndex = restored.third) }
        }
    }
    fun relayToken(): String = preferences.getString("relayToken", "").orEmpty()
    fun configureRelay(enabled: Boolean, token: String) {
        if(enabled && !Regex("[a-fA-F0-9]{64}").matches(token.trim())) {
            mutable.update { it.copy(message = "请输入电脑中继生成的 64 位令牌。") }; return
        }
        preferences.edit().putBoolean("relay", enabled).putString("relayToken", token.trim()).apply()
        mutable.update { it.copy(relayEnabled = enabled, message = "已保存") }
    }
    fun configureMobileMode(mode: MobileTransport) {
        if (state.value.busy) return
        preferences.edit().putString("mobileTransport", mode.name).apply()
        mutable.update { it.copy(mobileTransport = mode, mobileConnection = null) }
    }
    fun checkMobileConnection(mode: MobileTransport) = request {
        val id = mutable.value.allMatches.firstOrNull { it.id.matches(Regex("[1-9][0-9]{0,9}")) }?.id
            ?: throw SourceUnavailable("暂无可检测的比赛")
        mutable.update { it.copy(mobileConnection = "正在检测…") }
        try {
            val result = mobileJson.read(MobileRequest.create(MobileRoute.MATCH, id), mode, refresh = true) { mobileMatchEnvelope(it, id) }
            mutable.update { it.copy(mobileConnection = if (result.stamp.origin == DataOrigin.NETWORK) "连接正常" else "连接失败，使用缓存") }
        } catch (e: CancellationException) {
            mutable.update { it.copy(mobileConnection = null) }
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(mobileConnection = if (e is SourceUnavailable) e.message else "连接失败") }
            throw e
        }
    }
    fun followTeam(id: String) {
        val next = mutable.value.followedTeams.toggle(id)
        preferences.edit().putStringSet("teams", next).apply()
        mutable.update { it.copy(followedTeams = next) }
    }
    fun followPlayer(player: PlayerRef) {
        val next = if(mutable.value.followedPlayers.any { it.id == player.id }) mutable.value.followedPlayers.filter { it.id != player.id }
            else mutable.value.followedPlayers + player
        preferences.edit().putString("followedPlayers", org.json.JSONArray(next.map(Records::player)).toString()).apply()
        mutable.update { it.copy(followedPlayers = next) }
    }
    private fun cacheRecent(stamp: SourceStamp): Boolean = java.time.Duration.between(stamp.capturedAt, java.time.Instant.now()).seconds in 0..600
    fun loadTeam(id: String, refresh: Boolean = false) = request {
        if(id.toLongOrNull() == null) return@request
        val cached = withContext(Dispatchers.IO) { repository.restoredTeam(id) }
        if(cached != null && id !in mutable.value.teamProfiles) mutable.update { it.copy(teamProfiles = it.teamProfiles + (id to cached)) }
        if(cached?.value?.content?.schemaVersion == 2 && cached.warning == null && cached.stamp.url.contains("/mobile/") && cacheRecent(cached.stamp) && !refresh) return@request
        val url = mutable.value.teams.find { it.id == id }?.url ?: "https://www.hltv.org/team/$id/-"
        val result = repository.team(id, url, forceRefresh = refresh)
        mutable.update { it.copy(teamProfiles = it.teamProfiles + (id to result)) }
    }
    fun loadEvent(id: String, refresh: Boolean = false) = request {
        if(id.toLongOrNull() == null) return@request
        val cached = withContext(Dispatchers.IO) { repository.restoredEvent(id) }
        if(cached != null && id !in mutable.value.eventProfiles) mutable.update { it.copy(eventProfiles = it.eventProfiles + (id to cached)) }
        if(cached?.value?.content?.schemaVersion == 2 && cacheRecent(cached.stamp) && cached.stamp.url.contains("/mobile/") && cached.warning == null &&
            cached.value.content.brackets.size == cached.value.content.bracketLinks.size && !refresh) return@request
        val result = repository.event(id, mutable.value.events.find { it.id == id }?.url ?: "https://www.hltv.org/events/$id/-", forceRefresh = refresh)
        mutable.update { it.copy(eventProfiles = it.eventProfiles + (id to result)) }
    }
    fun saveMatch(id: String) {
        mutable.value.allMatches.find { it.id == id }?.let(::rememberForSearch)
        mutable.update { it.toggleSavedMatch(id) }
        preferences.edit().putStringSet("matches", mutable.value.savedMatches).apply()
        persistBookmarks()
    }

    fun syncDeviceTimezone() { mutable.update { it.copy(timezone = ZoneId.systemDefault().id) } }

    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    private var calendarActive = false
    fun calendarVisible(visible: Boolean) {
        calendarActive = visible
        if (!visible) while (true) { val id = calendarQueue.tryReceive().getOrNull() ?: break; calendarQueued.remove(id) }
    }
    private val calendarQueue = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val calendarQueued = mutableSetOf<String>()
    private val calendarWorker = viewModelScope.launch {
        for (id in calendarQueue) {
            state.first { !it.busy }
            if (!calendarActive || mutable.value.mobileTransport != MobileTransport.HTTPS) { calendarQueued.remove(id); continue }
            if (mutable.value.eventProfiles[id]?.value?.content?.complete == true) continue
            mutable.update { it.copy(busy = true) }
            try {
                val result = repository.calendarEvent(id)
                mutable.update { it.copy(eventProfiles = it.eventProfiles + (id to result)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // A missing attendee list must not hide the event or repeatedly hit a blocked source.
                while (calendarQueue.tryReceive().isSuccess) { }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun queueEventParticipants(id: String) {
        if (!calendarActive || id.toLongOrNull() == null || mutable.value.mobileTransport != MobileTransport.HTTPS || !calendarQueued.add(id)) return
        calendarQueue.trySend(id)
    }
    private var rankingsAttempted = false
    private var vrsAttempted = false
    fun ensureVrs() = viewModelScope.launch {
        state.first { !it.busy }
        if (vrsAttempted) return@launch
        vrsAttempted = true
        val feeds = mutable.value.vrs.values
        if (feeds.isEmpty() || feeds.any { it.stamp.origin == DataOrigin.SNAPSHOT ||
                java.time.Duration.between(it.stamp.capturedAt, java.time.Instant.now()).toHours() >= 6 })
            refreshVrs(VrsRegion.GLOBAL)
    }
    fun ensureRankings() {
        if (rankingsAttempted || mutable.value.busy) return
        rankingsAttempted = true
        if (mutable.value.rankings.items.isEmpty() || java.time.Duration.between(mutable.value.rankings.stamp.capturedAt, java.time.Instant.now()).toHours() >= 6)
            rankingRequest(false, false)
    }
    fun restoreCalendarDetails() = viewModelScope.launch {
        val cached = withContext(Dispatchers.IO) { mutable.value.events.mapNotNull { event -> repository.restoredEvent(event.id)?.let { event.id to it } }.toMap() }
        mutable.update { it.copy(eventProfiles = cached + it.eventProfiles) }
    }
    fun loadNews() {
        if (mutable.value.newsMonth != YearMonth.now()) loadNewsMonth(mutable.value.newsMonth)
        else newsRequest(false)
    }
    fun loadNewsMonth(month: YearMonth, refresh: Boolean = false) = request {
        require(month.year >= 2002 && month <= YearMonth.now())
        val cached = withContext(Dispatchers.IO) { repository.restoredNewsMonth(month) }
        mutable.update { it.copy(newsMonth = month, news = cached, newsFailure = null) }
        try {
            if (mutable.value.networkAvailable == false) {
                if (cached == null) throw SourceUnavailable("当前离线，该月新闻未缓存")
                return@request
            }
            val result = repository.newsMonth(month, refresh)
            mutable.update { it.copy(news = result, newsFailure = result.warning) }
            if (result.warning == null) preferences.edit().putBoolean("useWebsiteSession", true).apply()
        } catch (e: CancellationException) {
            mutable.update { it.copy(newsFailure = "读取已取消") }
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(newsFailure = e.message ?: "连接失败") }
            throw e
        }
    }
    private fun newsRequest(refresh: Boolean) = request {
        try {
            if (mutable.value.networkAvailable == false) {
                val cached = withContext(Dispatchers.IO) { repository.restoredNews() }
                mutable.update { it.copy(news = cached, newsFailure = if (cached == null) "当前离线，新闻未缓存" else null) }
                return@request
            }
            val result = repository.news(refresh)
            mutable.update { it.copy(news = result, newsFailure = result.warning) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(newsFailure = e.message ?: "连接失败") }; throw e }
    }
    fun loadArticle(url: String, refresh: Boolean = false) = request {
        require(NewsParser.allowed(url))
        mutable.update { it.copy(loadingArticle = url, articleFailures = it.articleFailures - url) }
        try {
            val cached = withContext(Dispatchers.IO) { repository.restoredArticle(url) }
            if (cached != null && url !in mutable.value.articles) mutable.update { it.copy(articles = it.articles + (url to cached)) }
            if (mutable.value.networkAvailable == false) {
                if (cached == null && url !in mutable.value.articles) throw SourceUnavailable("当前离线，正文未缓存")
                return@request
            }
            val result = repository.article(url, refresh)
            mutable.update { it.copy(articles = it.articles + (url to result), articleFailures = it.articleFailures - url) }
        } catch (e: CancellationException) {
            mutable.update { it.copy(articleFailures = it.articleFailures + (url to "读取已取消")) }
            throw e
        }
        catch (e: Exception) { mutable.update { it.copy(articleFailures = it.articleFailures + (url to (e.message ?: "连接失败"))) }; throw e }
        finally { mutable.update { it.copy(loadingArticle = null) } }
    }
    fun loginAndRetry(url: String, mobileData: Boolean = true, retry: () -> Unit) {
        if (mutable.value.busy || browser.request.value != null) return
        viewModelScope.launch {
            try {
                browser.browse(url.takeIf(HltvAccess::allowed) ?: "https://www.hltv.org/")
                // Retry through the browser that owns the user's session. Cookies never leave Gecko.
                if (mobileData) configureMobileMode(MobileTransport.BROWSER)
                else preferences.edit().putBoolean("useWebsiteSession", true).apply()
                retry()
            } catch (_: CancellationException) { /* Closing the website is not a fetch failure. */ }
            catch (e: Exception) {
                val message = if (e is MobileAccessException && e.reason == MobileFailure.BLOCKED) "网站仍拒绝访问，请稍后再试。" else e.message ?: "网页打开失败"
                mutable.update { it.copy(message = message,
                    newsFailure = if (!mobileData && NewsParser.archiveMonth(url) != null) message else it.newsFailure,
                    articleFailures = if (!mobileData && NewsParser.allowed(url)) it.articleFailures + (url to message) else it.articleFailures) }
            }
        }
    }
    fun refreshMatches(date: LocalDate) = request { val result = repository.matches(date, mutable.value.matches, forceRefresh = true); mutable.update { it.copy(matches = result) } }
    fun refreshMatch(id: String) = loadMatch(id, true)
    private val nextStatisticsAttempt = mutableMapOf<String, Long>()
    fun retryMatchStatistics(id: String) { viewModelScope.launch { loadMatchStatistics(id, true) } }
    suspend fun loadMatchStatistics(id: String, refresh: Boolean = false) {
        if (id in mutable.value.statisticsLoading) return
        val match = mutable.value.allMatches.find { it.id == id } ?: return
        if (match.status == MatchStatus.UPCOMING || match.stats.isEmpty() && match.statGroups.isEmpty() || match.hasCompleteSideStatistics()) return
        mutable.update { it.copy(statisticsLoading = it.statisticsLoading + id) }
        try {
            val cached = mutable.value.matchStatistics[id] ?: withContext(Dispatchers.IO) { repository.restoredMatchStatistics(id) }
            if (cached != null) mutable.update { it.copy(matchStatistics = it.matchStatistics + (id to cached),
                statisticsFailures = cached.note?.let { note -> it.statisticsFailures + (id to note) } ?: it.statisticsFailures) }
            val now = System.currentTimeMillis()
            val age = cached?.stamp?.capturedAt?.toEpochMilli()?.let { now - it }
            val ttl = if (match.status == MatchStatus.FINISHED) 86_400_000L else 60_000L
            if (!refresh && age != null && age in 0 until ttl && match.withSideStatistics(cached).hasCompleteSideStatistics()) return
            // Restore local sides immediately even while an unrelated feed is refreshing.
            // Only a new website request waits for the foreground request to finish.
            mutable.first { !it.busy }
            if (!refresh && now < (nextStatisticsAttempt[id] ?: 0)) return
            nextStatisticsAttempt[id] = now + 60_000L
            val result = repository.matchStatistics(match, refresh || cached != null && !match.withSideStatistics(cached).hasCompleteSideStatistics())
            val failure = result.note ?: "阵营统计待更新".takeUnless { match.withSideStatistics(result).hasCompleteSideStatistics() }
            mutable.update { it.copy(matchStatistics = it.matchStatistics + (id to result),
                statisticsFailures = if (failure == null) it.statisticsFailures - id else it.statisticsFailures + (id to failure)) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            mutable.update { it.copy(statisticsFailures = it.statisticsFailures + (id to (e.message ?: "阵营统计加载失败"))) }
        } finally { mutable.update { it.copy(statisticsLoading = it.statisticsLoading - id) } }
    }
    private val nextLiveDetails = mutableMapOf<String, Long>()
    private val liveUpdateGate = kotlinx.coroutines.sync.Mutex()
    fun retryLiveScore(id: String) { viewModelScope.launch { updateVisibleMatch(id) } }
    suspend fun updateVisibleMatch(id: String) {
        if (mutable.value.busy || mutable.value.networkAvailable == false || browser.request.value != null) return
        if (!liveUpdateGate.tryLock()) return
        try {
        var current = mutable.value.allMatches.find { it.id == id } ?: return
        val now = java.time.Instant.now()
        if (current.status != MatchStatus.LIVE && !(current.status == MatchStatus.UPCOMING &&
            current.startsAt?.let { java.time.Duration.between(now, it).seconds in -86_400..120 } == true)) return
        if (current.status == MatchStatus.LIVE) try {
            repository.liveScore(current)?.let { score ->
                mutable.update { state ->
                    val latest = state.allMatches.find { it.id == id } ?: current
                    if (latest.status != MatchStatus.LIVE) state else state.copy(matchDetails = state.matchDetails + (id to latest.copy(liveScore = latest.compatibleLiveScore(score))),
                        liveScoreFailures = state.liveScoreFailures - id)
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // Retain the last successful score and its own capture time.
            mutable.update { it.copy(liveScoreFailures = it.liveScoreFailures + (id to (e.message ?: "连接失败"))) }
        }
        if (System.currentTimeMillis() < (nextLiveDetails[id] ?: 0)) return
        nextLiveDetails[id] = System.currentTimeMillis() + 60_000
        current = mutable.value.allMatches.find { it.id == id } ?: return
        try {
            val result = repository.match(id, current.url, true, background = true)
            mutable.update { state ->
                val latest = state.matchDetails[id]?.liveScore
                state.copy(matchDetails = state.matchDetails + (id to result.copy(liveScore = result.compatibleLiveScore(latest))),
                    liveScoreFailures = if (result.status == MatchStatus.FINISHED) state.liveScoreFailures - id else state.liveScoreFailures)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Access cooldowns remain enforced by the source. */ }
        mutable.value.matchDetails[id]?.let { repository.rememberMatch(it) }
        } finally { liveUpdateGate.unlock() }
    }
    private suspend fun restoreMatchTeams(match: Match) {
        val cached = withContext(Dispatchers.IO) { listOf(match.left, match.right).mapNotNull { team -> repository.restoredTeam(team.id)?.let { team.id to it } }.toMap() }
        mutable.update { it.copy(teamProfiles = cached + it.teamProfiles) }
    }
    fun loadMatch(id: String, refresh: Boolean = false) = request {
        val cached = withContext(Dispatchers.IO) { repository.restoredMatch(id) }
        if (cached != null) mutable.update { state -> if (state.matchDetails[id]?.detailed == true) state else state.copy(matchDetails = state.matchDetails + (id to cached)) }
        val cachedStatistics = withContext(Dispatchers.IO) { repository.restoredMatchStatistics(id) }
        if (cachedStatistics != null) mutable.update { state -> state.copy(matchStatistics = if (id in state.matchStatistics) state.matchStatistics else state.matchStatistics + (id to cachedStatistics)) }
        val current = mutable.value.allMatches.find { it.id == id }
        if (current != null) restoreMatchTeams(current)
        if (!refresh && current?.canReuseDetails(java.time.Instant.now()) == true) return@request
        val result = repository.match(id, current?.url ?: "https://www.hltv.org/matches/$id/-", forceRefresh = refresh)
        mutable.update { state -> state.copy(matchDetails = state.matchDetails + (id to result),
            matches = state.matches.copy(items = state.matches.items.map { if(it.id == id) result else it }),
            savedRecords = state.savedRecords.map { if(it.id == id) result else it }) }
        persistBookmarks()
    }

    fun loadMatchTeams(id: String) = request {
        val match = mutable.value.allMatches.find { it.id == id } ?: return@request
        for (team in listOf(match.left, match.right)) {
            if (mutable.value.teamProfiles[team.id]?.value?.content?.complete == true) continue
            val result = repository.team(team.id, team.url)
            mutable.update { it.copy(teamProfiles = it.teamProfiles + (team.id to result)) }
        }
    }

    fun loadDuels(statsUrl: String, refresh: Boolean = false) = request {
        val url = DuelParser.performanceUrl(statsUrl) ?: return@request
        val cached = withContext(Dispatchers.IO) { repository.restoredDuels(url) }
        if (cached != null) mutable.update { it.copy(matchDuels = it.matchDuels + (url to cached)) }
        if (cached != null && !refresh) return@request
        val result = repository.duels(statsUrl, refresh)
        mutable.update { it.copy(matchDuels = it.matchDuels + (url to result)) }
    }
    fun restoreDuels(statsUrl: String) { viewModelScope.launch {
        val url = DuelParser.performanceUrl(statsUrl) ?: return@launch
        if (url in mutable.value.matchDuels) return@launch
        val cached = withContext(Dispatchers.IO) { repository.restoredDuels(url) } ?: return@launch
        mutable.update { it.copy(matchDuels = it.matchDuels + (url to cached)) }
    } }

    fun openHistoryMatch(row: MatchHistoryResult, open: (String) -> Unit) {
        val match = MatchAnalysisCalculations.historyMatch(row)
        rememberForSearch(match)
        mutable.update { state -> if (state.allMatches.any { it.id == match.id }) state else state.copy(matchDetails = state.matchDetails + (match.id to match)) }
        open("match|${match.id}")
    }

    fun openMatch(match: Match, open: (String) -> Unit) {
        rememberForSearch(match)
        mutable.update { state -> if (state.allMatches.any { it.id == match.id }) state else state.copy(matchDetails = state.matchDetails + (match.id to match)) }
        open("match|${match.id}")
    }

    fun retryMatchHistory(id: String, teamId: String?) {
        val key = "$id|${teamId ?: "h2h"}"
        loadMatchHistory(id, teamId, more = key in mutable.value.historyFailures && key in mutable.value.matchHistories, refresh = true)
    }
    fun loadMatchHistory(id: String, teamId: String?, more: Boolean = false, refresh: Boolean = false) = request {
        val match = mutable.value.allMatches.find { it.id == id } ?: return@request
        val key = "$id|${teamId ?: "h2h"}"
        val previous = mutable.value.matchHistories[key]
        if (previous != null && !more && !refresh) return@request
        val teams = listOf(match.left, match.right).filter { teamId == null || it.id == teamId }
        if (teams.isEmpty()) return@request
        val firstUrl = if (teamId == null) match.insights.headToHead?.moreUrl else
            match.insights.recentForm.firstOrNull { it.team.id == teamId && it.scope == MatchHistoryScope.TEAM }?.moreUrl
        val url = if (more) previous?.value?.nextUrl else firstUrl
        if (url == null) return@request
        val result = try { repository.history(url, teams, teamId == null, refresh) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(historyFailures = it.historyFailures + (key to (e.message ?: "连接失败"))) }; throw e }
        val combined = if (more && previous != null) result.copy(value = result.value.copy(items = (previous.value.items + result.value.items).distinctBy { it.id })) else result
        mutable.update { it.copy(matchHistories = it.matchHistories + (key to combined), historyFailures = it.historyFailures - key) }
    }
    fun restoreMatch(id: String) { viewModelScope.launch {
        val cached = withContext(Dispatchers.IO) { repository.restoredMatch(id) }
        if(cached != null) mutable.update { if(id in it.matchDetails) it else it.copy(matchDetails = it.matchDetails + (id to cached)) }
    } }
    fun loadPlayer(id: String, refresh: Boolean = false) = request {
        val cached = withContext(Dispatchers.IO) { repository.restoredPlayer(id) }
        if(cached != null && id !in mutable.value.players) mutable.update { it.copy(players = it.players + (id to cached)) }
        if(cached?.value?.content?.schemaVersion == 2 && cached.warning == null && cached.stamp.url.contains("/mobile/") && cacheRecent(cached.stamp) && !refresh) return@request
        val url = mutable.value.playerRefs.find { it.id == id }?.url ?: "https://www.hltv.org/player/$id/-"
        val result = repository.player(id, url, forceRefresh = refresh)
        mutable.update { it.copy(players = it.players + (id to result)) }
    }
    fun openProfileMatch(item: ProfileMatch, open: (String) -> Unit) {
        fun show(match: Match) {
            rememberForSearch(match)
            mutable.update { current -> if (current.allMatches.any { it.id == match.id }) current else current.copy(matchDetails = current.matchDetails + (match.id to match)) }
            open("match|${match.id}")
        }
        val direct = resolveProfileMatch(item, mutable.value.allMatches).asMatch()
        if (direct != null) show(direct) else request { show(repository.resolveMatch(item)) }
    }
    private fun rememberForSearch(match: Match) {
        val known = mutable.value.allMatches.firstOrNull { it.id == match.id &&
            setOf(it.left.id, it.right.id) == setOf(match.left.id, match.right.id) }
        val stamped = if (match.stamp == null) known ?: match else match
        viewModelScope.launch {
            try { repository.rememberMatch(stamped) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "比赛记录保存失败，请检查存储空间") } }
        }
    }
    /** Called by the visible profile; leaving the tab cancels further enrichment. */
    suspend fun completeProfileSchedule(rows: List<ProfileMatch>) {
        val missing = rows.filter { it.status != MatchStatus.FINISHED &&
            !resolveProfileMatch(it, mutable.value.allMatches).event.hasIdentity() }
            .distinctBy { it.key }.take(8)
        for (row in missing) {
            try {
                val match = repository.scheduleMatch(row) ?: continue
                mutable.update { it.copy(matchDetails = it.matchDetails + (match.id to match)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the known schedule; the normal refresh/verification remains available. */ }
        }
    }
    fun moreProfileMatches(kind: String, id: String) = request {
        if (kind == "player") {
            val current = mutable.value.players[id] ?: return@request
            val content = repository.moreProfileMatches(kind, id, current.value.content)
            val updated = current.copy(value = current.value.copy(content = content))
            repository.persistPlayer(updated)
            mutable.update { it.copy(players = it.players + (id to updated)) }
        } else {
            val current = mutable.value.teamProfiles[id] ?: return@request
            val content = repository.moreProfileMatches(kind, id, current.value.content)
            val updated = current.copy(value = current.value.copy(content = content))
            repository.persistTeam(updated)
            mutable.update { it.copy(teamProfiles = it.teamProfiles + (id to updated)) }
        }
    }
    private fun persistBookmarks() {
        val saved = mutable.value.savedRecords
        viewModelScope.launch { try { repository.saveBookmarks(saved) } catch(e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(message = "收藏记录保存失败，请检查设备存储空间。") } } }
    }
    fun refreshEvents() = request { val result = repository.events(true); mutable.update { it.copy(eventIndex = result) } }
    fun refreshRankings() = rankingRequest(true, true)
    private fun rankingRequest(refresh: Boolean, browserAllowed: Boolean) = request {
        mutable.update { it.copy(rankingFailure = null) }
        try {
            val result = repository.hltvRankings(forceRefresh = refresh, allowBrowser = browserAllowed)
            mutable.update { it.copy(rankings = result, rankingFailure = result.warning) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(rankingFailure = e.message ?: "排名更新失败") } }
    }
    fun refreshVrs(region: VrsRegion) = request {
        mutable.update { it.copy(vrsFailure = null) }
        try {
            val result = repository.vrsBundle(forceRefresh = true)
            mutable.update { it.copy(vrs = result, vrsFailure = result.values.firstNotNullOfOrNull { feed -> feed.warning }) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(vrsFailure = e.message ?: "排名更新失败") } }
    }
    fun refreshNews() = loadNewsMonth(mutable.value.newsMonth, true)
    private var previewRetryAfter = 0L
    /** Called from the visible list's lifecycle. Scrolling away cancels unfinished reads. */
    suspend fun prepareNewsPreviews(urls: List<String>) {
        if (mutable.value.networkAvailable == false || mutable.value.busy || System.currentTimeMillis() < previewRetryAfter) return
        for (url in urls.distinct().take(6)) {
            if (mutable.value.networkAvailable == false || mutable.value.busy) return
            if (url in mutable.value.articles || !NewsParser.allowed(url)) continue
            try {
                val result = repository.articlePreview(url)
                mutable.update { it.copy(articles = it.articles + (url to result)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                previewRetryAfter = System.currentTimeMillis() + ((e as? SourceUnavailable)?.cooldownMs ?: 30_000).coerceAtLeast(30_000)
                return
            }
        }
    }
    private var lastRequest: (suspend () -> Unit)? = null
    fun retryLastRequest() { lastRequest?.let(::request) }
    private fun request(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        lastRequest = block
        mutable.update { it.copy(busy = true, message = null, requestFailed = false) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (dev.matchscope.app.BuildConfig.DEBUG) android.util.Log.w("RushB", "Source refresh failed", e)
                mutable.update { it.copy(requestFailed = true, message = if (e is SourceUnavailable) e.message else "连接失败，请检查网络后重试。") }
            }
            finally { mutable.update { it.copy(busy = false) }; MediaLoader.retryFailed() }
        }
    }
    private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id
}
