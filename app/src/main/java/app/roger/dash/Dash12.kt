package app.roger.dash

/**
 * The V-Strom 250 SX dash's message popup shows 12 upper-case characters (checked on the bike, 2026-11-14).
 * Other models aren't known, so they get the same limit ([DashModel.textMax]).
 * Street names are shortened to fit: "Mahatma Gandhi Road" → "M GANDHI RD".
 */
object Dash12 {
    const val MAX = 12

    private val SHORT = listOf(
        "ROAD" to "RD", "STREET" to "ST", "MARG" to "MG", "HIGHWAY" to "HWY", "NATIONAL HIGHWAY" to "NH",
        "STATE HIGHWAY" to "SH", "EXPRESSWAY" to "EXPWY", "FLYOVER" to "FLYOVR", "BRIDGE" to "BR",
        "NAGAR" to "NGR", "SECTOR" to "SEC", "CROSS" to "X", "MAIN" to "MN", "LANE" to "LN",
        "AVENUE" to "AVE", "CIRCLE" to "CIR", "JUNCTION" to "JN", "SERVICE" to "SVC", "SOUTH" to "S",
        "NORTH" to "N", "EAST" to "E", "WEST" to "W", "OLD" to "OLD", "NEW" to "NEW",
    ).sortedByDescending { it.first.length }

    fun street(name: String, max: Int = MAX): String {
        var s = name.uppercase().replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        for ((long, short) in SHORT) s = s.replace(Regex("\\b$long\\b"), short)
        if (s.length <= max) return s
        // Still too long: shorten the first words to initials ("MAHATMA GANDHI RD" → "M GANDHI RD").
        val words = s.split(' ').toMutableList()
        var i = 0
        while (words.joinToString(" ").length > max && i < words.size - 1) {
            if (words[i].length > 1) words[i] = words[i].take(1)
            i++
        }
        return words.joinToString(" ").take(max).trim()
    }
}
