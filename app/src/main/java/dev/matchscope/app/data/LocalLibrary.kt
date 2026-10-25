package dev.matchscope.app.data

/** Lightweight references to verified records already held on this device. */
data class LocalLibrary(
    val matches: List<Match> = emptyList(), val teams: List<Team> = emptyList(),
    val players: List<PlayerRef> = emptyList(), val events: List<Tournament> = emptyList(),
    val news: List<NewsItem> = emptyList(),
    val teamRanks: Map<String, Sourced<Int>> = emptyMap(),
)

fun Tournament.hasIdentity(): Boolean = id.toLongOrNull()?.let { it > 0 } == true && name.isNotBlank() && name != "—"

internal fun Match.searchSummary(): Match = copy(
    event = event.copy(content = EventContent()), detailed = false,
    stats = emptyList(), statGroups = emptyList(), players = emptyMap(),
    insights = MatchInsights(), duels = emptyMap(), veto = emptyList(),
)

/** Read one record at a time off the UI thread, tolerating individual corrupt/old files. */
internal fun readLocalLibrary(store: RecordStore): LocalLibrary {
    val matches = mutableListOf<Match>()
    val teams = mutableListOf<Team>()
    val players = mutableListOf<PlayerRef>()
    val events = mutableListOf<Tournament>()
    val news = mutableListOf<NewsItem>()
    val teamRanks = mutableMapOf<String, Sourced<Int>>()
    fun addMatch(match: Match) {
        teams += listOf(match.left, match.right)
        players += match.players.values.flatten() + match.stats.mapNotNull { it.player }
        events += match.event.copy(content = EventContent())
        matches += match.searchSummary()
    }
    fun profile(content: ProfileContent, stamp: SourceStamp) {
        news += content.news
        content.matches.mapNotNull { it.asMatch()?.copy(stamp = stamp) }.forEach(::addMatch)
        teams += content.career.map { it.team } + content.events.mapNotNull { it.team } + content.earnings.map { it.team }
        players += content.roster.map { it.player } + content.teammates.map { it.player } + content.similarPlayers.map { it.player }
        events += content.events.map { it.event.copy(content = EventContent()) }
    }
    for (name in store.names()) {
        if (!Regex("(?:match|team|player|event)-[0-9]+|match-feed|saved").matches(name)) continue
        val raw = store.read(name) ?: continue
        runCatching {
            when {
                name == "match-feed" -> Records.feed(raw).items.forEach(::addMatch)
                name == "saved" -> raw.optJSONArray("items")?.let { array ->
                    for (i in 0 until array.length()) runCatching { addMatch(Records.match(array.getJSONObject(i), true)) }
                }
                name.startsWith("match-") -> addMatch(Records.match(raw, true))
                name.startsWith("team-") -> Records.teamProfile(raw).let { value ->
                    teams += value.value.team; players += value.value.players; profile(value.value.content, value.stamp)
                    value.value.worldRanking?.takeIf { it > 0 }?.let { rank ->
                        teamRanks[value.value.team.id] = Sourced(rank, value.stamp)
                    }
                }
                name.startsWith("player-") -> Records.profile(raw).let { value ->
                    players += value.value.player; value.value.team?.let { teams += it }; profile(value.value.content, value.stamp)
                }
                name.startsWith("event-") -> Records.eventProfile(raw).let { value ->
                    events += value.value.copy(content = EventContent())
                    teams += value.value.content.teams
                    players += value.value.content.statistics.flatMap { it.players.map { row -> row.player } }
                    value.value.content.matches.forEach { addMatch(it.copy(stamp = it.stamp ?: value.stamp)) }
                }
            }
        }
    }
    return LocalLibrary(preferredMatchRecords(matches), teams.distinctBy { it.id },
        players.distinctBy { it.id }, mergedEvents(events), news.filter { NewsParser.allowed(it.url) }
            .distinctBy { java.net.URI(it.url).path.split('/')[2] }.sortedByDescending { NewsParser.instant(it.publishedAt) }, teamRanks)
}
