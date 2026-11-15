package app.roger.dash

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Everything the rider would lose by uninstalling, in one JSON file they save where they like:
 * rides, fill-ups, parked spots, vehicles and settings (including the Google key, if one was entered).
 * Used to move to a new phone, or from a test build to a release build (which can't be installed over it).
 *
 * Format: {"app":"RogerDash","format":1,"made":<ms>,"prefs":{key:{"t":type,"v":value}},"files":{path:text}}.
 * The log file and the demo data are left out.
 */
object Backup {
    private const val FORMAT = 1

    /** Kept as they are on this phone when a backup is restored. */
    private val KEEP_LOCAL = setOf("setupDone")

    private fun files(root: File): List<File> =
        (File(root, "trips").listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList() +
            root.listFiles { f -> f.isFile && (f.name == "fuel.json" || f.name.startsWith("parked") && f.name.endsWith(".json")) }.orEmpty())

    fun export(ctx: Context): String {
        val root = ctx.filesDir
        val prefs = JSONObject()
        ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val (t, value) = when (v) {
                is String -> "s" to v
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                else -> return@forEach // string sets aren't used
            }
            prefs.put(k, JSONObject().put("t", t).put("v", value))
        }
        val f = JSONObject()
        files(root).forEach { f.put(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
        return JSONObject().put("app", "RogerDash").put("format", FORMAT).put("made", System.currentTimeMillis())
            .put("prefs", prefs).put("files", f).toString()
    }

    /** What a backup holds, to show before restoring it. Throws if it isn't a RogerDash backup. */
    data class Summary(val made: Long, val rides: Int, val hasFuel: Boolean, val vehicles: Int)

    fun read(json: String): Pair<JSONObject, Summary> {
        val o = JSONObject(json)
        require(o.optString("app") == "RogerDash") { "This isn't a RogerDash backup." }
        require(o.optInt("format") in 1..FORMAT) { "This backup is from a newer RogerDash. Update the app first." }
        val files = o.getJSONObject("files")
        val rides = files.keys().asSequence().count { it.startsWith("trips/") }
        val vehicles = o.getJSONObject("prefs").optJSONObject("vehicles")?.optString("v")
            ?.let { runCatching { org.json.JSONArray(it).length() }.getOrNull() } ?: 1
        return o to Summary(o.optLong("made"), rides, files.has("fuel.json"), vehicles)
    }

    /** Replaces this phone's rides, fill-ups, parked spots and settings with the backup's. */
    fun restore(ctx: Context, o: JSONObject) {
        val root = ctx.filesDir
        val files = o.getJSONObject("files")
        // Check every path first: only plain files under the app's own folders.
        val paths = files.keys().asSequence().toList()
        paths.forEach { p ->
            require(!p.contains("..") && !p.startsWith("/") && (p.startsWith("trips/") || !p.contains('/'))) { "Unexpected file in the backup: $p" }
        }
        files(root).forEach { it.delete() }
        File(root, "trips").mkdirs()
        paths.forEach { p -> File(root, p).writeText(files.getString(p)) }

        val sp = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE)
        val keep = KEEP_LOCAL.associateWith { sp.all[it] }
        val e = sp.edit().clear()
        val prefs = o.getJSONObject("prefs")
        prefs.keys().forEach { k ->
            if (k in KEEP_LOCAL) return@forEach
            val x = prefs.getJSONObject(k)
            when (x.getString("t")) {
                "s" -> e.putString(k, x.getString("v"))
                "b" -> e.putBoolean(k, x.getBoolean("v"))
                "i" -> e.putInt(k, x.getInt("v"))
                "l" -> e.putLong(k, x.getLong("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat())
            }
        }
        keep.forEach { (k, v) -> if (v is Boolean) e.putBoolean(k, v) }
        e.commit()
    }
}
