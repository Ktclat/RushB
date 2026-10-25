package dev.matchscope.app.data

enum class VetoAction { BAN, PICK, LEFT, UNKNOWN }
data class VetoStep(val order: Int, val map: String, val action: VetoAction, val team: Team?, val description: String)

fun Match.vetoSteps(): List<VetoStep> = veto.mapIndexed { index, raw ->
    val text = raw.trim().replace(Regex("^\\d+\\.\\s*"), "")
    val action = Regex("^(.*?) (removed|picked|移除|选择) (.+)$", RegexOption.IGNORE_CASE).matchEntire(text)
    val remaining = text.endsWith(" was left over", true) || text.startsWith("剩余地图 ")
    VetoStep(index + 1, action?.groupValues?.get(3) ?: text.replace(Regex(" was left over$", RegexOption.IGNORE_CASE), "").removePrefix("剩余地图 "),
        when (action?.groupValues?.get(2)?.lowercase()) { "removed", "移除" -> VetoAction.BAN; "picked", "选择" -> VetoAction.PICK; else -> if (remaining) VetoAction.LEFT else VetoAction.UNKNOWN },
        action?.groupValues?.get(1)?.let { name -> listOf(left, right).firstOrNull { it.name.equals(name, true) } }, text)
}
