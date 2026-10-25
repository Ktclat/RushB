package dev.matchscope.app.data

import java.time.Instant
import java.time.LocalDate

/** A deliberately small, manually checked research snapshot. Never relabel as live. */
object SeedSnapshot {
    val capturedAt: Instant = Instant.parse("2026-10-18T02:36:00Z")
    private val rankingUrl = "https://www.hltv.org/ranking/teams/2026/october/12"
    private fun team(id: String, name: String, region: String, players: String = "") =
        Team(id, name, region, players.split(',').filter(String::isNotBlank), logoUrl = SeedArtwork.teams[id])
    val spirit = team("7020", "Spirit", "欧洲", "sh1ro,magixx,tN1R,zont1x,donk")
    val vitality = team("9565", "Vitality", "欧洲", "apEX,ropz,ZywOo,flameZ,mezii")
    val mouz = team("4494", "MOUZ", "欧洲", "torzsi,Spinx,xertioN,PR,xelex")
    val furia = team("8297", "FURIA", "美洲", "FalleN,yuurih,YEKINDAR,KSCERATO,molodoy")
    val falcons = team("11283", "Falcons", "欧洲", "karrigan,NiKo,TeSeS,m0NESY,kyousuke")
    val aurora = team("11861", "Aurora", "欧洲", "XANTARES,woxic,Jimpphat,kyxsan,Wicadia")
    val betboom = team("12394", "BETBOOM", "欧洲")
    val teams = listOf(spirit, vitality, mouz, furia, falcons, aurora, betboom)
    val thunderpick = Tournament("8932", "Thunderpick World Championship 2026",
        LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-18"), "马耳他 · LAN", "$1,000,000", 8,
        "https://www.hltv.org/events/8932/thunderpick-world-championship-2026", logoUrl = SeedArtwork.eventLogo)
    val matchItems = listOf(
        Match("2399416", aurora, falcons, thunderpick, MatchStatus.UPCOMING,
            format = "BO3",
            startsAt = Instant.ofEpochMilli(1792323000000),
            url = "https://www.hltv.org/matches/2399416/aurora-vs-falcons-thunderpick-world-championship-2026"),
        Match("2399417", furia, betboom, thunderpick, MatchStatus.UPCOMING,
            format = "BO5",
            startsAt = Instant.ofEpochMilli(1792335600000),
            url = "https://www.hltv.org/matches/2399417/furia-vs-betboom-thunderpick-world-championship-2026"),
        Match("2399415", falcons, betboom, thunderpick, MatchStatus.FINISHED,
            format = "BO3",
            startsAt = Instant.parse("2026-10-17T17:00:00Z"), leftScore = 1, rightScore = 2,
            maps = listOf(MapScore("Anubis", 13, 5, "8:4 / 5:1"), MapScore("Dust2", 14, 16, "7:5 / 5:7 · 加时 2:4"), MapScore("Mirage", 7, 13, "6:6 / 1:7")),
            veto = listOf("BETBOOM 移除 Inferno", "Falcons 移除 Cache", "BETBOOM 选择 Anubis", "Falcons 选择 Dust2", "BETBOOM 移除 Ancient", "Falcons 移除 Nuke", "剩余地图 Mirage"),
            stats = listOf(
                PlayerStat("m0NESY", falcons.id, "55–47", "81.0", "67.6%", "1.14", "3.0"),
                PlayerStat("karrigan", falcons.id, "41–46", "57.1", "63.2%", "0.94", "3.0"),
                PlayerStat("NiKo", falcons.id, "44–53", "71.1", "69.1%", "0.93", "3.0"),
                PlayerStat("TeSeS", falcons.id, "37–46", "64.9", "61.8%", "0.92", "3.0")),
            url = "https://www.hltv.org/matches/2399415/falcons-vs-betboom-thunderpick-world-championship-2026")
    ).map { match -> match.copy(players = SeedArtwork.rosters[match.id].orEmpty(), stats = match.stats.map { stat ->
        stat.copy(player = SeedArtwork.rosters[match.id]?.get(stat.teamId)?.find { it.name == stat.name })
    }, stamp = SourceStamp(match.url, Instant.parse(when(match.id) {
        "2399416" -> "2026-10-18T04:20:30.494Z"
        "2399415" -> "2026-10-18T04:03:04.671Z"
        else -> "2026-10-18T04:00:59.704Z"
    }), DataOrigin.SNAPSHOT)) }
    fun stamp(url: String, published: String? = null) = SourceStamp(url, capturedAt, DataOrigin.SNAPSHOT, published)
    val matches = Feed(matchItems, SourceStamp("https://www.hltv.org/matches", matchItems.minOf { it.stamp!!.capturedAt }, DataOrigin.SNAPSHOT),
        "收录 UTC+8 的 10 月 18 日三场比赛，仅供离线浏览。开赛时间来自已核验的页面时间戳；状态以采集时为准。")
    val events = Feed(listOf(thunderpick), stamp(thunderpick.url))
    val rankings = Feed(listOf(972, 894, 692, 527, 488, 461).mapIndexed { i, points ->
        RankingEntry(i + 1, teams[i], points, if (i == 5) 3 else 0, teams[i].roster)
    }, stamp(rankingUrl, "2026-10-12"), "快照仅收录前 6 名；阵容以该期榜单为准。")
}
