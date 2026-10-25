package dev.matchscope.app.data

import java.time.LocalDate

data class ProfileHonor(val category: String, val title: String, val event: Tournament? = null, val year: Int? = null,
    val placement: Int? = null, val imageUrl: String? = null, val newsUrl: String? = null) {
    val key: String get() = "$category:${event?.id}:$year:$placement:$title"
}
data class ProfilePeer(val player: PlayerRef, val teamName: String? = null, val rating: String? = null, val ratingVersion: String? = null, val roles: List<String> = emptyList())
data class TeamEarning(val team: Team, val prize: String, val lastEvent: LocalDate? = null)
data class EventPlayerStats(val player: PlayerRef, val teamName: String?, val teamLogoUrl: String?, val rating: String?, val ratingVersion: String?, val maps: Int?)
data class EventStatistics(val key: String, val label: String, val players: List<EventPlayerStats>)
