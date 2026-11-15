package app.roger.dash

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max

/**
 * RogerDash's look: lots of space, big light numbers, small uppercase labels, hairline dividers,
 * one accent colour. Light and dark follow the system.
 */
class Ui(private val a: Activity) {
    val dark = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val accent = Color.parseColor("#E8590C")
    val bg = if (dark) Color.parseColor("#0E0F11") else Color.WHITE
    val ink = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#111214")
    val soft = if (dark) Color.parseColor("#8A8F98") else Color.parseColor("#80858D")
    val line = if (dark) Color.parseColor("#24262A") else Color.parseColor("#ECEDEF")
    val panel = if (dark) Color.parseColor("#17181B") else Color.parseColor("#F6F7F8")
    val ok = Color.parseColor("#2F9E44")
    val warn = Color.parseColor("#F29900")
    val bad = Color.parseColor("#D93025")

    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * a.resources.displayMetrics.density

    fun text(s: CharSequence, size: Float = 15f, color: Int = ink, weight: Typeface? = null) = TextView(a).apply {
        text = s; textSize = size; setTextColor(color)
        weight?.let { typeface = it }
    }

    /** Small uppercase section label. Starts a card when the page is [cardify]-ed. */
    fun label(s: String) = text(s.uppercase(), 11.5f, soft, medium).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(28), 0, dp(10))
        tag = SECTION
    }

    /** Card surface: a step lighter than the page. */
    val card = if (dark) Color.parseColor("#17191D") else Color.parseColor("#F4F5F7")

    /**
     * Groups a page into soft rounded cards: each section (from a [label], or a view tagged [SECTION])
     * and everything after it, up to the next section, goes in one card. Views before the first section
     * (page title, status line) stay as they are.
     */
    fun cardify(box: LinearLayout) {
        val kids = (0 until box.childCount).map { box.getChildAt(it) }
        box.removeAllViews()
        var card: LinearLayout? = null
        for (v in kids) {
            if (v.tag == END) card = null // e.g. bottom spacing: back on the page, not in the last card
            if (v.tag == SECTION) {
                card = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { setColor(this@Ui.card); cornerRadius = dpf(22f) }
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
                }
                box.addView(card)
                if (v is TextView && v.letterSpacing > 0f) v.setPadding(0, 0, 0, dp(8)) // label: no big top gap inside a card
            }
            (card ?: box).addView(v)
        }
    }

    /** The given views in one card (when a page isn't built from [label] sections). */
    fun cardOf(vararg v: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(this@Ui.card); cornerRadius = dpf(22f) }
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        v.forEach { x ->
            if (x is TextView && x.tag == SECTION && x.letterSpacing > 0f) x.setPadding(0, 0, 0, dp(8))
            addView(x)
        }
    }

    companion object {
        const val SECTION = "section"
        const val END = "end"
    }

    /** Big light number with a small unit: "12.4 km". */
    fun big(value: String, unit: String = "", size: Float = 52f) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(text(value, size, ink, light).apply { includeFontPadding = false })
        if (unit.isNotEmpty()) addView(text(unit, size * 0.3f, soft).apply { setPadding(dp(6), 0, 0, dp(6)) })
    }

    /** Value over a small label, for stat rows. */
    fun stat(value: String, labelText: String) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(value, 20f, ink, light))
        addView(text(labelText, 12f, soft).apply { setPadding(0, dp(2), 0, 0) })
    }

    fun statRow(vararg s: Pair<String, String>) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(14), 0, 0)
        s.forEach { (v, l) -> addView(stat(v, l), LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    fun divider() = View(a).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(2) }
    }

    fun space(h: Int) = View(a).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    /** Filled accent pill: the one primary action on a screen. */
    fun primary(s: String, onClick: () -> Unit) = pill(s, accent, Color.WHITE, null, onClick)

    /** Outlined pill: secondary actions. */
    fun secondary(s: String, onClick: () -> Unit) = pill(s, Color.TRANSPARENT, ink, line, onClick)

    private fun pill(s: String, fill: Int, fg: Int, stroke: Int?, onClick: () -> Unit) = Button(a).apply {
        text = s; isAllCaps = false; textSize = 15f; setTextColor(fg); typeface = medium
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat(); setColor(fill)
            stroke?.let { setStroke(dp(1), it) }
        }
        stateListAnimator = null
        minHeight = dp(52); minimumHeight = dp(52)
        setOnClickListener { onClick() }
    }

    /** Plain accent text action: "Map ›". */
    fun link(s: String, onClick: () -> Unit) = text(s, 14.5f, accent, medium).apply {
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    fun row(vararg v: View, gap: Int = 10) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(gap) }) }
    }

    fun column(vararg v: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        v.forEach { addView(it) }
    }

    /** Segmented control: a soft track with the selected option as a raised pill. */
    fun tabs(names: List<String>, selected: Int, onPick: (Int) -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply { setColor(line); cornerRadius = dpf(20f) }
        names.forEachIndexed { i, n ->
            val on = i == selected
            addView(text(n, 14f, if (on) ink else soft, medium).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                if (on) background = GradientDrawable().apply { setColor(bg); cornerRadius = dpf(16f) }
                setOnClickListener { onPick(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** Status chip: coloured dot + text in a soft pill ("● Connected · SBM01…"). */
    fun status(textS: String, dotColor: Int) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dpf(16f) }
        addView(dot(dotColor))
        addView(text(textS, 13f, soft))
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }

    fun dot(color: Int) = View(a).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
    }

    fun tint(c: Int) = ColorStateList.valueOf(c)

    /** Setting row: title, explanation, switch on the right. */
    @Suppress("UseSwitchCompatOrMaterialCode")
    fun toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = android.widget.Switch(a).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, soft))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.argb(110, 232, 89, 12), line))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(6))
            addView(column(text(title, 15.5f), text(sub, 12.5f, soft)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
    }

    fun field(value: String, hint: String) = android.widget.EditText(a).apply {
        setText(value); this.hint = hint
        setTextColor(ink); setHintTextColor(soft)
        setSingleLine()
        setPadding(0, paddingTop, 0, paddingBottom)
        backgroundTintList = tint(line)
    }
}

/** Line icons for the bottom bar, drawn on a 24-unit grid (no image assets). */
class TabIcon(a: Activity, private val ui: Ui, private val kind: Int) : View(a) {
    var color: Int = ui.soft
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(ui.dp(24), ui.dp(24))

    override fun onDraw(c: Canvas) {
        val u = width / 24f
        c.save(); c.scale(u, u)
        p.color = color; p.strokeWidth = 2f; p.style = Paint.Style.STROKE
        when (kind) {
            0 -> { // Ride: speedometer
                c.drawArc(3f, 4f, 21f, 22f, 150f, 240f, false, p)
                c.drawLine(12f, 13f, 16.5f, 8.5f, p)
                p.style = Paint.Style.FILL; c.drawCircle(12f, 13f, 1.8f, p)
            }
            1 -> { // Navigate: arrow
                c.drawPath(Path().apply { moveTo(12f, 2.5f); lineTo(20f, 21f); lineTo(12f, 16.5f); lineTo(4f, 21f); close() }, p)
            }
            2 -> { // Trips: route between two points
                c.drawCircle(6f, 18f, 2.5f, p); c.drawCircle(18f, 6f, 2.5f, p)
                c.drawPath(Path().apply { moveTo(8.5f, 18f); cubicTo(16f, 18f, 8f, 6f, 15.5f, 6f) }, p)
            }
            3 -> { // Fuel: pump with hose
                c.drawRoundRect(4f, 3.5f, 14f, 21f, 1.5f, 1.5f, p)
                c.drawLine(4f, 10f, 14f, 10f, p)
                c.drawPath(Path().apply { moveTo(14f, 8f); lineTo(16.5f, 8f); quadTo(18f, 8f, 18f, 9.5f); lineTo(18f, 16f)
                    quadTo(18f, 18f, 19.5f, 18f); quadTo(21f, 18f, 21f, 16f); lineTo(21f, 8.5f); lineTo(18.5f, 6f) }, p)
            }
            else -> { // More: three dots
                p.style = Paint.Style.FILL
                c.drawCircle(5f, 12f, 2f, p); c.drawCircle(12f, 12f, 2f, p); c.drawCircle(19f, 12f, 2f, p)
            }
        }
        c.restore()
    }
}

/** Six-segment fuel gauge, like the dash. */
class FuelBars(a: Activity, private val ui: Ui) : View(a) {
    var bars: Int? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(ui.dp(120), ui.dp(18))

    override fun onDraw(c: Canvas) {
        val n = 6
        val gap = ui.dpf(4f)
        val bw = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val on = (bars ?: -1) > i
            p.color = if (on) (if ((bars ?: 0) <= 1) ui.accent else ui.ink) else ui.line
            val x = i * (bw + gap)
            c.drawRoundRect(x, height * 0.15f, x + bw, height * 0.85f, ui.dpf(2f), ui.dpf(2f), p)
        }
    }
}

/**
 * Single-series bar chart: bars with rounded tops on a baseline, a label under each, the value over the
 * latest bar and over the tapped one. Bars with no value (null) leave a gap.
 */
class BarChart(
    a: Activity, private val ui: Ui,
    private val values: List<Double?>, private val labels: List<String>, private val fmt: (Double) -> String,
) : View(a) {
    private var picked = values.indexOfLast { it != null }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dpf(11f); textAlign = Paint.Align.CENTER }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), ui.dp(150))

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action == android.view.MotionEvent.ACTION_UP && values.isNotEmpty()) {
            picked = (e.x / (width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
            invalidate(); performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val top = (values.filterNotNull().maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
        val labelH = ui.dpf(18f); val valueH = ui.dpf(18f)
        val base = height - labelH
        val slot = width.toFloat() / values.size
        val bw = (slot * 0.55f).coerceAtMost(ui.dpf(28f))
        val r = ui.dpf(4f)
        bar.color = ui.line
        c.drawRect(0f, base, width.toFloat(), base + ui.dpf(1f), bar)
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            txt.color = ui.soft
            c.drawText(labels.getOrElse(i) { "" }, cx, height - ui.dpf(4f), txt)
            if (v == null) return@forEachIndexed
            val h = ((base - valueH) * (v / top)).toFloat().coerceAtLeast(if (v > 0) ui.dpf(2f) else 0f)
            bar.color = if (i == picked) ui.accent else android.graphics.Color.argb(120, 232, 89, 12)
            // Rounded top, square bottom anchored on the baseline.
            c.drawRoundRect(cx - bw / 2, base - h, cx + bw / 2, base, r, r, bar)
            if (h > r) c.drawRect(cx - bw / 2, base - r, cx + bw / 2, base, bar)
            if (i == picked) { txt.color = ui.ink; c.drawText(fmt(v), cx, base - h - ui.dpf(5f), txt) }
        }
    }
}

/** The ride's route drawn as a line (no map tiles: fast, offline, private). */
class RouteView(a: Activity, private val ui: Ui, private val pts: List<Pt>) : View(a) {
    private val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ui.dpf(3.5f); color = ui.accent
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * 0.62f).toInt())
    }

    override fun onDraw(c: Canvas) {
        fill.color = ui.panel
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), ui.dpf(18f), ui.dpf(18f), fill)
        if (pts.size < 2) return
        val k = cos(Math.toRadians(pts.map { it.lat }.average()))
        val xs = pts.map { it.lon * k }
        val ys = pts.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = ui.dpf(24f)
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val scale = minOf((width - 2 * pad) / (maxX - minX).coerceAtLeast(1e-9), (height - 2 * pad) / (maxY - minY).coerceAtLeast(1e-9))
            .coerceAtMost((minOf(width, height) - 2 * pad) / span)
        val ox = (width - (maxX - minX) * scale) / 2
        val oy = (height - (maxY - minY) * scale) / 2
        fun x(i: Int) = (ox + (xs[i] - minX) * scale).toFloat()
        fun y(i: Int) = (height - oy - (ys[i] - minY) * scale).toFloat()
        val p = Path().apply { moveTo(x(0), y(0)); for (i in 1 until pts.size) lineTo(x(i), y(i)) }
        c.drawPath(p, path)
        fill.color = ui.ink
        c.drawCircle(x(0), y(0), ui.dpf(5f), fill)
        fill.color = ui.accent
        c.drawCircle(x(pts.lastIndex), y(pts.lastIndex), ui.dpf(6f), fill)
    }
}

/** Speed over the ride (max per 10 s), as a soft area chart. */
class SpeedView(a: Activity, private val ui: Ui, private val speeds: List<Int>) : View(a) {
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ui.dpf(2f); color = ui.accent; strokeJoin = Paint.Join.ROUND
    }
    private val areaP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 232, 89, 12) }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.soft; textSize = ui.dpf(11f) }

    private val markP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.ink; strokeWidth = ui.dpf(1.5f) }
    private val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.ink; textSize = ui.dpf(13f); typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** Text for the chosen sample (e.g. "10:42 · 54 km/h"). */
    var labelFor: (Int) -> String = { "${speeds[it]} km/h" }
    /** Told which sample the finger is on (null = let go of nothing chosen). */
    var onScrub: ((Int?) -> Unit)? = null
    private var chosen: Int? = null

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), ui.dp(150))

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (speeds.size < 2) return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true) // don't scroll the page while sliding
                val i = Math.round(e.x / (width.toFloat() / (speeds.size - 1))).coerceIn(0, speeds.size - 1)
                if (i != chosen) { chosen = i; onScrub?.invoke(i); invalidate() }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (speeds.size < 2) return
        val top = (speeds.max().coerceAtLeast(20))
        val head = ui.dpf(26f) // room for the chosen value above the chart
        val h = height - ui.dpf(16f)
        val step = width.toFloat() / (speeds.size - 1)
        c.save(); c.translate(0f, head); c.scale(1f, (h - head) / h)
        val line = Path()
        val area = Path().apply { moveTo(0f, h) }
        speeds.forEachIndexed { i, s ->
            val x = i * step
            val y = h - h * s / top
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(width.toFloat(), h); area.close()
        c.drawPath(area, areaP)
        c.drawPath(line, lineP)
        c.restore()
        val yOf = { s: Int -> head + (h - head) * (1 - s.toFloat() / top) }
        c.drawText("$top km/h", 0f, head - ui.dpf(4f), textP)
        c.drawText("0", 0f, height.toFloat() - ui.dpf(2f), textP)
        chosen?.let { i ->
            val x = i * step
            c.drawLine(x, head, x, h, markP)
            c.drawCircle(x, yOf(speeds[i]), ui.dpf(5f), markP)
            val label = labelFor(i)
            val w = valueP.measureText(label)
            c.drawText(label, (x - w / 2).coerceIn(0f, width - w), ui.dpf(13f), valueP)
        }
    }
}
