package app.roger.dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/** A shareable picture of a ride: the route drawn as a line, name, date and the main numbers. */
object RideCard {
    private const val W = 1080
    private const val H = 1350
    private val BG = Color.parseColor("#0E0F11")
    private val INK = Color.parseColor("#F2F2F2")
    private val SOFT = Color.parseColor("#8A8F98")
    private val ACCENT = Color.parseColor("#E8590C")

    fun render(t: Trip): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(BG)
        val pad = 80f
        val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fun paint(color: Int, size: Float, tf: Typeface = Typeface.DEFAULT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = tf }

        // Title and date.
        c.drawText(t.name, pad, 150f, paint(INK, 62f, light))
        c.drawText("${Fmt.day(t.start)} · ${Fmt.time(t.start)} – ${Fmt.time(t.end)}", pad, 210f, paint(SOFT, 34f))

        // Route.
        val box = android.graphics.RectF(pad, 270f, W - pad, 930f)
        if (t.points.size >= 2) drawRoute(c, t, box) else
            c.drawText("No route recorded", box.centerX() - 170f, box.centerY(), paint(SOFT, 40f))

        // Numbers.
        c.drawText("%.1f".format(Locale.US, t.distanceKm), pad, 1080f, paint(INK, 130f, light))
        val kmX = pad + paint(INK, 130f, light).measureText("%.1f".format(Locale.US, t.distanceKm)) + 16f
        c.drawText("km", kmX, 1080f, paint(SOFT, 46f))
        val stats = listOf(Fmt.dur(t.movingSec) to "riding", "${t.avgKmh.toInt()}" to "avg km/h", "${t.topKmh}" to "top km/h")
        val colW = (W - 2 * pad) / 3
        stats.forEachIndexed { i, (v, l) ->
            val x = pad + i * colW
            c.drawText(v, x, 1180f, paint(INK, 50f, light))
            c.drawText(l, x, 1228f, paint(SOFT, 30f))
        }
        c.drawText("RogerDash", pad, H - 50f, paint(ACCENT, 30f, medium))
        return b
    }

    private fun drawRoute(c: Canvas, t: Trip, box: android.graphics.RectF) {
        val pts = t.points
        val k = cos(Math.toRadians(pts.map { it.lat }.average()))
        val xs = pts.map { it.lon * k }; val ys = pts.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val scale = minOf(box.width() / (maxX - minX).coerceAtLeast(span * 0.05), box.height() / (maxY - minY).coerceAtLeast(span * 0.05))
        val offX = box.left + (box.width() - (maxX - minX) * scale).toFloat() / 2
        val offY = box.top + (box.height() - (maxY - minY) * scale).toFloat() / 2
        fun x(i: Int) = (offX + (xs[i] - minX) * scale).toFloat()
        fun y(i: Int) = (offY + (maxY - ys[i]) * scale).toFloat() // north up

        val path = Path().apply { moveTo(x(0), y(0)); for (i in 1 until pts.size) lineTo(x(i), y(i)) }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 14f; color = ACCENT
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        fun mark(i: Int, fill: Int) {
            dot.color = BG; c.drawCircle(x(i), y(i), 20f, dot)
            dot.color = fill; c.drawCircle(x(i), y(i), 13f, dot)
        }
        mark(0, INK)
        mark(pts.lastIndex, ACCENT)
        Track.topPoint(t)?.let { top -> mark(pts.indexOf(top), Color.parseColor("#FAB005")) }
    }

    /** Render to a PNG in the share folder. */
    fun save(t: Trip, ctx: android.content.Context): File {
        val f = Share.newFile(ctx, "${Share.slug(t.name)}-${t.id}.png")
        f.outputStream().use { render(t).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}
