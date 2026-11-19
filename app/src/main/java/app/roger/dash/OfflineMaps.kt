package app.roger.dash

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Saved map areas for rides without signal (the OpenStreetMap map).
 *
 * The app downloads the tiles itself into an MBTiles file (SQLite), plus the fonts and icons, and when there's no
 * internet the map runs on a copy of the style that reads only those files. Not MapLibre's own offline regions:
 * OpenFreeMap's tile index (".../planet") points at a dated tile set that changes every week, and MapLibre reuses
 * whatever index it has cached, so its saved tiles and any copy of the style could belong to different weeks
 * (bike test 2026-11-18: a blank map offline). Here, each week's tiles go in their own file with the index they came
 * from. Areas saved in an older week are downloaded again with the newest one after the next save.
 *
 * OpenFreeMap allows offline downloads of at most 3,000 tiles or 50 MB each, 4 requests at a time, with a User-Agent
 * naming the app (openfreemap.org): [MAX_TILES], [MAX_BYTES], [THREADS], [Http.UA].
 */
object OfflineMaps {
    const val MAX_TILES = 3000
    const val MAX_BYTES = 50L * 1024 * 1024
    private const val THREADS = 4
    const val MAX_ZOOM = 14 // OpenFreeMap's tiles stop at 14; the map zooms further on those
    private const val BASE = "https://tiles.openfreemap.org"
    val STYLES = listOf("liberty", "fiord") // light and dark: the same tiles, fonts and icons
    private val FONTS = listOf("Noto Sans Regular", "Noto Sans Bold", "Noto Sans Italic")
    /** Glyph ranges kept: Latin to Malayalam and Thai (0–4095, all Indian scripts), and punctuation (8192–8447). */
    private val GLYPHS = (0 until 16).map { it * 256 } + 8192

    fun styleUrl(name: String) = "$BASE/styles/$name"

    // ───────────── tiles (web-mercator x/y at a zoom) ─────────────

    data class Tile(val x: Int, val y: Int) // zoom 14

    fun tileX(lon: Double, z: Int = MAX_ZOOM) = floor((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    fun tileY(lat: Double, z: Int = MAX_ZOOM): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }
    private fun lonOf(x: Int, z: Int = MAX_ZOOM) = x.toDouble() / (1 shl z) * 360 - 180
    private fun latOf(y: Int, z: Int = MAX_ZOOM) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

    /** South, west, north, east of a zoom-14 tile. */
    fun bounds(t: Tile) = doubleArrayOf(latOf(t.y + 1), lonOf(t.x), latOf(t.y), lonOf(t.x + 1))

    /** Zoom-14 tiles that come within [km] of [p]. */
    private fun near(p: LatLon, km: Double, into: MutableSet<Tile>) {
        val dLat = km / 111.0
        val dLon = km / (111.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.1))
        for (x in tileX(p.lon - dLon)..tileX(p.lon + dLon)) for (y in tileY(p.lat + dLat)..tileY(p.lat - dLat)) {
            val t = Tile(x, y)
            val b = bounds(t)
            val closest = LatLon(p.lat.coerceIn(b[0], b[2]), p.lon.coerceIn(b[1], b[3]))
            if (Geo.dist(p, closest) <= km * 1000) into += t
        }
    }

    fun around(center: LatLon, km: Double): Set<Tile> = LinkedHashSet<Tile>().also { near(center, km, it) }

    /** Zoom-14 tiles within [km] either side of a route, in route order. */
    fun along(shape: List<LatLon>, km: Double): Set<Tile> {
        val out = LinkedHashSet<Tile>()
        var last: LatLon? = null
        for (p in shape) {
            // Every ~200 m along the route (and every bend point).
            val l = last
            if (l != null) {
                val d = Geo.dist(l, p)
                val steps = (d / 200).toInt()
                for (i in 1..steps) near(LatLon(l.lat + (p.lat - l.lat) * i / (steps + 1), l.lon + (p.lon - l.lon) * i / (steps + 1)), km, out)
            }
            near(p, km, out)
            last = p
        }
        return out
    }

    /** z/x/y of every tile to download for [z14]: those, plus the ones above them at every zoom out to the whole world. */
    fun pyramid(z14: Collection<Tile>): Set<Triple<Int, Int, Int>> {
        val out = LinkedHashSet<Triple<Int, Int, Int>>()
        for (z in 0..MAX_ZOOM) z14.forEach { out += Triple(z, it.x shr (MAX_ZOOM - z), it.y shr (MAX_ZOOM - z)) }
        return out
    }

    fun count(z14: Collection<Tile>): Int = pyramid(z14).size

    // ───────────── saved areas (index file) ─────────────

    data class Area(val id: String, val name: String, val kind: String, val made: Long, val version: String, val tiles: List<Tile>) {
        fun json() = JSONObject().put("id", id).put("name", name).put("kind", kind).put("made", made).put("version", version)
            .put("tiles", JSONArray(tiles.map { "${it.x},${it.y}" }))
        companion object {
            fun of(o: JSONObject) = Area(o.getString("id"), o.getString("name"), o.optString("kind", "area"), o.optLong("made"),
                o.optString("version"), o.optJSONArray("tiles")?.let { a -> (0 until a.length()).mapNotNull { i ->
                    a.getString(i).split(',').takeIf { it.size == 2 }?.let { Tile(it[0].toInt(), it[1].toInt()) } } } ?: emptyList())
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "offline").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")
    private fun mbtiles(ctx: Context, version: String) = File(dir(ctx), "$version.mbtiles")

    /** The week of tiles the offline map uses, e.g. "20261115_080022_pt". */
    fun version(ctx: Context): String? = runCatching { JSONObject(indexFile(ctx).readText()).optString("version").ifBlank { null } }.getOrNull()

    fun areas(ctx: Context): List<Area> = runCatching {
        val a = JSONObject(indexFile(ctx).readText()).getJSONArray("areas")
        (0 until a.length()).map { Area.of(a.getJSONObject(it)) }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    @Synchronized private fun writeIndex(ctx: Context, version: String?, areas: List<Area>) {
        indexFile(ctx).writeText(JSONObject().put("version", version ?: "").put("areas", JSONArray(areas.distinctBy { it.id }.map { it.json() })).toString())
    }

    /** Space used by saved maps on the phone. */
    fun bytesUsed(ctx: Context): Long = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(true)

    /** Saved areas exist and there's no internet: the map runs on the saved files. */
    fun useSaved(ctx: Context): Boolean = !online(ctx) && offlineStyle(ctx, "liberty") != null

    /**
     * The map style [name] reading only saved files: tiles from this week's MBTiles, fonts and icons from disk.
     * Null without saved areas.
     */
    fun offlineStyle(ctx: Context, name: String): String? {
        val v = version(ctx) ?: return null
        val tiles = mbtiles(ctx, v).takeIf { it.exists() } ?: return null
        val raw = File(dir(ctx), "style-$name.json").takeIf { it.exists() }?.readText() ?: return null
        return runCatching {
            val s = JSONObject(raw)
            val d = dir(ctx).absolutePath
            s.put("sources", JSONObject().put("openmaptiles", JSONObject().put("type", "vector").put("url", "mbtiles://${tiles.absolutePath}")))
            // Layers on other sources (the light style's world relief shading) need the internet: leave them out.
            val layers = s.getJSONArray("layers")
            s.put("layers", JSONArray((0 until layers.length()).map { layers.getJSONObject(it) }.filter { !it.has("source") || it.optString("source") == "openmaptiles" }))
            s.put("glyphs", "file://$d/fonts/{fontstack}/{range}.pbf")
            s.put("sprite", "file://$d/sprites/ofm")
            s.toString()
        }.getOrNull()
    }

    // ───────────── saving ─────────────

    private val main = Handler(Looper.getMainLooper())

    /** What's being saved now ("Saving Pune… 40%"), or null. */
    @Volatile var progress: String? = null; private set
    var onChange: (() -> Unit)? = null
    @Volatile private var cancelled = false

    private fun changed() = main.post { onChange?.invoke() }
    private fun say(p: String) { if (p != progress) { progress = p; changed() } }

    fun save(ctx: Context, name: String, kind: String, tiles: Collection<Tile>, done: (String?) -> Unit) = save(ctx, { name }, kind, tiles, done)

    /** The town at [p] from OpenStreetMap (Photon), for naming an area; null if unknown. Blocking. */
    fun townAt(p: LatLon): String? = runCatching {
        val f = JSONObject(Http.get("https://photon.komoot.io/reverse?lat=${p.lat}&lon=${p.lon}&lang=en", 10_000)).getJSONArray("features")
        val pr = f.getJSONObject(0).getJSONObject("properties")
        listOf("city", "town", "village", "district", "county", "name").map { pr.optString(it) }.firstOrNull { it.isNotBlank() }
    }.getOrNull()

    /**
     * Save [tiles] as an area ([kind]: "area" or "route"; [name] is worked out on the download thread, it may look up
     * a town). [done] on the main thread: null on success, or what went wrong. Areas saved in an older week are then
     * saved again with this week's tiles.
     */
    fun save(ctx: Context, name: () -> String, kind: String, tiles: Collection<Tile>, done: (String?) -> Unit) {
        if (progress != null) { done("Already saving a map"); return }
        if (count(tiles) > MAX_TILES) { done("Too big: make the area smaller"); return }
        val app = ctx.applicationContext
        cancelled = false
        say("Saving the map…")
        Thread {
            val err = runCatching {
                val n = name()
                // This week's tile set (the address has the week in it) and both styles.
                val tj = JSONObject(fetch("$BASE/planet").toString(Charsets.UTF_8))
                val template = tj.getJSONArray("tiles").getString(0)
                val version = Regex("/planet/([^/]+)/").find(template)?.groupValues?.get(1) ?: error("Unexpected tile address $template")
                val area = Area("a" + System.currentTimeMillis(), n, kind, System.currentTimeMillis(), version, tiles.toList())
                extras(app)
                download(app, area, template, tj)?.let { return@runCatching it }
                val older = areas(app).filter { it.version != version }
                writeIndex(app, version, areas(app) + area)
                DashHub.onLog("Offline map saved: $n, ${count(tiles)} tiles of $version")
                // Older weeks: the same areas again with this week's tiles, then their old file goes.
                for (o in older) {
                    if (cancelled) break
                    val fresh = o.copy(version = version, made = System.currentTimeMillis())
                    val e = download(app, fresh, template, tj)
                    if (e == null) writeIndex(app, version, areas(app).map { if (it.id == o.id) fresh else it })
                    else DashHub.onLog("Offline map update failed: ${o.name}: $e")
                }
                dir(app).listFiles()?.filter { it.name.endsWith(".mbtiles") && it.name != "$version.mbtiles" && areas(app).none { a -> "${a.version}.mbtiles" == it.name } }
                    ?.forEach { it.delete() }
                null
            }.getOrElse { "Couldn't reach OpenFreeMap (${it.message})" }
            main.post {
                if (!cancelled) { progress = null; changed(); done(err) }
            }
        }.start()
    }

    /** Stop saving. What was downloaded for an unfinished area is cleared out. */
    fun cancel(ctx: Context) {
        cancelled = true
        progress = null
        changed()
        val app = ctx.applicationContext
        Thread { runCatching { prune(app) } }.start()
    }

    /** Fonts, icons and both styles (once; small, the same every week). */
    private fun extras(ctx: Context) {
        val d = dir(ctx)
        for (s in STYLES) File(d, "style-$s.json").writeBytes(fetch(styleUrl(s)))
        val sprites = File(d, "sprites").apply { mkdirs() }
        val spriteBase = JSONObject(File(d, "style-liberty.json").readText()).getString("sprite")
        for (f in listOf("ofm.json", "ofm.png", "ofm@2x.json", "ofm@2x.png")) {
            val out = File(sprites, f)
            if (!out.exists()) out.writeBytes(fetch(spriteBase + f.removePrefix("ofm")))
        }
        for (font in FONTS) for (r in GLYPHS) {
            val name = "$r-${r + 255}.pbf"
            // Written under the plain and the %20 folder name: MapLibre may or may not decode the space.
            val outs = listOf(font, font.replace(" ", "%20")).map { File(File(d, "fonts/$it").apply { mkdirs() }, name) }
            if (outs.all { it.exists() }) continue
            val bytes = runCatching { fetch("$BASE/fonts/${URLEncoder.encode(font, "UTF-8").replace("+", "%20")}/$name") }.getOrNull() ?: continue
            outs.forEach { it.writeBytes(bytes) }
        }
    }

    /** Tiles of [area] into its week's MBTiles: [THREADS] at a time, skipping those already there. Null when done. */
    private fun download(ctx: Context, area: Area, template: String, tj: JSONObject): String? {
        val db = open(ctx, area.version, tj)
        try {
            val want = pyramid(area.tiles).filter { (z, x, y) -> !has(db, z, x, y) }
            val total = want.size
            val doneN = AtomicInteger(); val bytes = AtomicLong()
            val failed = AtomicInteger()
            val pool = Executors.newFixedThreadPool(THREADS)
            for ((z, x, y) in want) pool.execute {
                if (cancelled || bytes.get() > MAX_BYTES) return@execute
                val data = runCatching { fetch(template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")) }.getOrNull()
                if (data == null) failed.incrementAndGet() else {
                    bytes.addAndGet(data.size.toLong())
                    synchronized(db) {
                        db.execSQL("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)",
                            arrayOf(z, x, (1 shl z) - 1 - y, data))
                    }
                }
                val n = doneN.incrementAndGet()
                if (n % 10 == 0 || n == total) say("Saving ${area.name}… ${n * 100 / total.coerceAtLeast(1)}% (${Fmt.mb(bytes.get())})")
            }
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.HOURS)
            return when {
                cancelled -> "Stopped"
                bytes.get() > MAX_BYTES -> { prune(ctx); "Too much map data there (over 50 MB): choose a smaller area" }
                failed.get() > total / 20 -> "${failed.get()} of $total map tiles didn't download: try again with a better signal"
                else -> null
            }
        } finally { synchronized(db) { db.close() } }
    }

    private fun open(ctx: Context, version: String, tj: JSONObject): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(mbtiles(ctx, version), null)
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
        db.delete("metadata", null, null)
        listOf("name" to "OpenFreeMap $version", "format" to "pbf", "type" to "baselayer", "minzoom" to "0", "maxzoom" to "$MAX_ZOOM",
            "bounds" to "-180,-85.0511,180,85.0511", "attribution" to tj.optString("attribution"),
            "json" to JSONObject().put("vector_layers", tj.optJSONArray("vector_layers") ?: JSONArray()).toString(),
        ).forEach { (k, v) -> db.execSQL("INSERT INTO metadata (name, value) VALUES (?,?)", arrayOf(k, v)) }
        return db
    }

    private fun has(db: SQLiteDatabase, z: Int, x: Int, y: Int): Boolean =
        db.rawQuery("SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf("$z", "$x", "${(1 shl z) - 1 - y}")).use { it.moveToFirst() }

    /** Removes tiles no saved area needs (after a delete or a stopped save). */
    private fun prune(ctx: Context) {
        val all = areas(ctx)
        dir(ctx).listFiles()?.filter { it.name.endsWith(".mbtiles") }?.forEach { f ->
            val v = f.name.removeSuffix(".mbtiles")
            val keep = pyramid(all.filter { it.version == v }.flatMap { it.tiles })
            if (keep.isEmpty()) { f.delete(); File(f.path + "-journal").delete(); return@forEach }
            val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val gone = ArrayList<Array<Any>>()
                db.rawQuery("SELECT zoom_level, tile_column, tile_row FROM tiles", null).use { c ->
                    while (c.moveToNext()) {
                        val z = c.getInt(0); val x = c.getInt(1); val y = (1 shl z) - 1 - c.getInt(2)
                        if (Triple(z, x, y) !in keep) gone += arrayOf(c.getInt(0), c.getInt(1), c.getInt(2))
                    }
                }
                gone.forEach { db.execSQL("DELETE FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", it) }
                if (gone.isNotEmpty()) db.execSQL("VACUUM")
            } finally { db.close() }
        }
    }

    /** Delete [area]: its entry, and the tiles only it used. With none left, everything goes. [done] on the main thread. */
    fun delete(ctx: Context, area: Area, done: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val left = areas(app).filter { it.id != area.id }
            writeIndex(app, if (left.isEmpty()) null else version(app), left)
            runCatching { if (left.isEmpty()) dir(app).deleteRecursively() else prune(app) }
            main.post(done)
        }.start()
    }

    /** One file, with OpenFreeMap's rules: the app named in the User-Agent; 3 tries. Blocking. */
    private fun fetch(url: String): ByteArray {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", Http.UA)
                try {
                    if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                    return c.inputStream.use { it.readBytes() }
                } finally { c.disconnect() }
            } catch (e: Exception) { last = e; if (attempt < 2) Thread.sleep(1000L * (attempt + 1)) }
        }
        throw last ?: IllegalStateException("download failed")
    }

    // ───────────── MapLibre ─────────────

    @Volatile private var ready = false

    /** MapLibre, naming the app to OpenFreeMap like the downloads do. The client is set after MapLibre starts (before, the app didn't open). */
    fun setup(ctx: Context) {
        org.maplibre.android.MapLibre.getInstance(ctx.applicationContext)
        if (ready) return
        ready = true
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", Http.UA).build()) }
            .build()
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client)
        dropRegions(ctx)
    }

    /** MapLibre offline regions from the first try of this feature (test phone only): no longer used, so freed. */
    private fun dropRegions(ctx: Context) = runCatching {
        org.maplibre.android.offline.OfflineManager.getInstance(ctx).listOfflineRegions(object : org.maplibre.android.offline.OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<org.maplibre.android.offline.OfflineRegion>?) {
                offlineRegions.orEmpty().forEach { r ->
                    r.delete(object : org.maplibre.android.offline.OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {}
                        override fun onError(error: String) {}
                    })
                }
            }
            override fun onError(error: String) {}
        })
    }
}
