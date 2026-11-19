package app.roger.dash

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** Search a place, see the route, start; then the next turn (on the dash) and a map that follows you. */
class NavTab(
    private val a: Activity,
    private val ui: Ui,
    private val requireLocation: (then: () -> Unit) -> Unit,
) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var key = ""

    /** Search / preview pages. */
    private val scroll = ScrollView(a).apply {
        isFillViewport = true
        addView(box.apply { setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(24)) })
    }

    /** Full-screen guidance: map, turn banner on top, arrival bar at the bottom. */
    private val navLayer = android.widget.FrameLayout(a).apply { visibility = View.GONE }

    /** Home: full-screen map with search, quick categories and a sheet, like Google Maps. */
    private val homeLayer = android.widget.FrameLayout(a).apply { visibility = View.GONE }

    val view: View = android.widget.FrameLayout(a).apply {
        addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(homeLayer, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(navLayer, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    fun refresh() { key = ""; navigating = false; homeMap = null; update() }

    fun update() {
        val n = DashHub.nav
        if (n != null) { homeLayer.visibility = View.GONE; renderNavigating(n); return }
        if (navigating) {
            navigating = false; navMap = null; key = ""
            navLayer.removeAllViews(); navLayer.visibility = View.GONE; scroll.visibility = View.VISIBLE
        }
        // Planning / route preview: the page; everything else: the map home.
        val page = DashHub.planning != null || (DashHub.preview != null && pickMode == null)
        if (!page) { scroll.visibility = View.GONE; renderHome(); return }
        homeLayer.visibility = View.GONE; scroll.visibility = View.VISIBLE
        val k = listOf(DashHub.planning, DashHub.preview?.first, DashHub.preview?.second?.lengthM, DashHub.navError,
            DashHub.planStops.size, DashHub.routeOptions).joinToString()
        if (k == key) return
        key = k
        box.removeAllViews()
        DashHub.planning?.let { planning(it); return }
        DashHub.preview?.let { (p, r) -> preview(p, r) }
    }

    // ───────────── home: map, search, categories, sheet ─────────────

    private var homeMap: RouteMap? = null
    private val sheet = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val sheetScroll = ScrollView(a)
    private val sheetCard = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val panel = ScrollView(a).apply { visibility = View.GONE } // text-search results, over the map
    private var homeKey = ""
    private var category: Category? = null
    private var catResults: List<Place>? = null
    private var catLoading = false
    private var selected: Place? = null

    private fun card(radius: Float = 18f) = android.graphics.drawable.GradientDrawable().apply {
        setColor(if (ui.dark) android.graphics.Color.parseColor("#1B1D21") else android.graphics.Color.WHITE)
        cornerRadius = ui.dpf(radius)
    }

    private fun renderHome() {
        homeLayer.visibility = View.VISIBLE
        if (homeMap == null) buildHome()
        val m = homeMap!!
        // Map: me, plus the category results (or the picked place) as pins.
        val shown = catResults.orEmpty().ifEmpty { listOfNotNull(selected) }
        m.showPlaces(shown.mapNotNull { it.pos }, shown.indexOfFirst { it == selected })
        m.showPosition(DashHub.lastFix, followRider = following && shown.isEmpty())
        val k = listOf(pickMode, DashHub.navError, DashHub.recentPlaces, DashHub.savedPlace("home"), DashHub.savedPlace("work"),
            category, catResults, catLoading, selected, Places.usingGoogle).joinToString()
        if (k == homeKey) return
        homeKey = k
        renderSheet()
    }

    /** The map follows you until you pan it or look at search results. */
    private var following = true

    private fun buildHome() {
        homeLayer.removeAllViews()
        homeKey = ""
        following = true
        val m = RouteMap(a, ui, fill = true)
        homeMap = m
        m.onFollowChanged = { f -> following = f }
        m.onPlaceTap = { i -> (catResults.orEmpty().ifEmpty { listOfNotNull(selected) }).getOrNull(i)?.let { selectPlace(it) } }
        homeLayer.addView(m, android.widget.FrameLayout.LayoutParams(-1, -1))

        // Top: search bar, then the category chips.
        val pad = ui.dp(12)
        (where.parent as? ViewGroup)?.removeView(where)
        val searchBar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card(28f)
            elevation = ui.dpf(6f)
            setPadding(ui.dp(18), 0, ui.dp(8), 0)
            addView(ui.text("⌕", 22f, ui.soft).apply { setPadding(0, 0, ui.dp(10), 0) })
            addView(where, LinearLayout.LayoutParams(0, ui.dp(54), 1f))
            addView(ui.text("✕", 18f, ui.soft).apply {
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                setOnClickListener { where.setText(""); hideKeyboard(); where.clearFocus() }
            })
        }
        val chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(10), 0, ui.dp(4)) }
        // Home and Work first, as icons: tap = go (or set it), long-press = change / remove.
        chips.addView(savedChip("home", "🏠"))
        chips.addView(savedChip("work", "💼"))
        chips.addView(mapChip("🏍 Weekend loop") { pickLoop() })
        Category.values().forEach { c -> chips.addView(mapChip(c.label) { openCategory(c) }) }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(searchBar)
            addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
            (panel.parent as? ViewGroup)?.removeView(panel)
            (results.parent as? ViewGroup)?.removeView(results)
            panel.removeAllViews()
            panel.addView(results.apply { setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(12)) })
            panel.background = card()
            panel.elevation = ui.dpf(6f)
            addView(panel, LinearLayout.LayoutParams(-1, -1).apply { topMargin = ui.dp(6); bottomMargin = pad })
        }
        homeLayer.addView(top, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.TOP))

        // Bottom sheet.
        (sheet.parent as? ViewGroup)?.removeView(sheet)
        (sheetScroll.parent as? ViewGroup)?.removeView(sheetScroll)
        sheetScroll.removeAllViews()
        sheetScroll.addView(sheet.apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(14)) })
        sheetCard.removeAllViews()
        sheetCard.background = card(22f)
        sheetCard.elevation = ui.dpf(8f)
        sheetCard.addView(View(a).apply { background = card(3f).apply { setColor(ui.line) } },
            LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(10) })
        sheetCard.addView(sheetScroll)
        // "My location" button (bottom right, always above the sheet): back to you, and follow again.
        val locate = android.widget.ImageView(a).apply {
            setImageBitmap(locateIcon())
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = card(28f)
            elevation = ui.dpf(6f)
            setOnClickListener {
                val me = DashHub.lastFix
                if (me == null) { requireLocation { DashHub.warmPosition() }; android.widget.Toast.makeText(a, "Finding your position…", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                following = true
                m.camAt = null
                m.fitTo(listOf(me))
                m.showPosition(me, followRider = catResults.isNullOrEmpty() && selected == null)
            }
        }
        (sheetCard.parent as? ViewGroup)?.removeView(sheetCard)
        val bottom = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(locate, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply { gravity = Gravity.END; setMargins(0, 0, pad + ui.dp(4), pad) })
            addView(sheetCard, LinearLayout.LayoutParams(-1, -2))
        }
        homeLayer.addView(bottom, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // Keep the camera centred in the visible gap between search bar and sheet.
        homeLayer.post { m.setMapPadding(ui.dp(130), (sheetCard.height).coerceAtLeast(ui.dp(160))) }
        sheetCard.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ -> m.setMapPadding(ui.dp(130), (b - t).coerceAtLeast(ui.dp(120))) }
        renderResults()
    }

    /** "My location" target: blue ring, centre dot and four ticks. */
    private fun locateIcon(): android.graphics.Bitmap {
        val s = ui.dp(26)
        val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(RouteMap.BLUE); strokeWidth = ui.dpf(2.2f)
        }
        val r = s / 2f
        p.style = android.graphics.Paint.Style.STROKE
        c.drawCircle(r, r, s * 0.30f, p)
        listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
            c.drawLine(r + dx * s * 0.30f, r + dy * s * 0.30f, r + dx * s * 0.48f, r + dy * s * 0.48f, p)
        }
        p.style = android.graphics.Paint.Style.FILL
        c.drawCircle(r, r, s * 0.13f, p)
        return b
    }

    /** Quick-search button floating on the map: solid card, like Google Maps' chips. */
    private fun mapChip(label: String, onClick: () -> Unit) = ui.text(label, 14f, ui.ink).apply {
        background = card(18f)
        elevation = ui.dpf(3f)
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8); bottomMargin = ui.dp(4) }
        setOnClickListener { onClick() }
    }

    /** Round icon button for Home / Work. */
    private fun savedChip(which: String, icon: String) = ui.text(icon, 14f, ui.ink).apply {
        gravity = Gravity.CENTER
        background = card(18f)
        elevation = ui.dpf(3f)
        setPadding(ui.dp(13), ui.dp(8), ui.dp(13), ui.dp(8)) // same height as the other chips
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8); bottomMargin = ui.dp(4) }
        setOnClickListener {
            val p = DashHub.savedPlace(which)
            if (p != null) choose(p) else {
                android.widget.Toast.makeText(a, "Search for your ${if (which == "home") "home" else "work"} address to save it", android.widget.Toast.LENGTH_SHORT).show()
                pickMode = which; refreshHome(); where.requestFocus(); showKeyboard()
            }
        }
        setOnLongClickListener {
            val name = if (which == "home") "Home" else "Work"
            android.app.AlertDialog.Builder(a).setTitle(DashHub.savedPlace(which)?.let { "$name: ${it.name}" } ?: name)
                .setItems(arrayOf("Change", "Remove")) { _, i ->
                    if (i == 0) { pickMode = which; refreshHome(); where.requestFocus(); showKeyboard() } else DashHub.setSavedPlace(which, null)
                }.show()
            true
        }
    }

    private fun showKeyboard() = where.post { a.getSystemService(InputMethodManager::class.java)?.showSoftInput(where, 0) }

    /** Back button: close the search list, a picked place, results or a pick, before leaving the tab. */
    fun onBack(): Boolean {
        if (DashHub.nav != null || DashHub.planning != null || (DashHub.preview != null && pickMode == null)) return false
        when {
            where.hasFocus() || found != null -> { where.setText(""); found = null; searching = false; hideKeyboard(); where.clearFocus(); renderResults() }
            selected != null -> { selected = null; refreshHome() }
            category != null -> { closeCategory(); refreshHome() }
            pickMode != null -> { pickMode = null; refreshHome() }
            else -> return false
        }
        return true
    }

    /** Sheet height: at most ~45% of the screen so the map stays visible. */
    private fun limitSheet() {
        val max = (a.resources.displayMetrics.heightPixels * 0.42).toInt()
        sheetScroll.layoutParams = LinearLayout.LayoutParams(-1, -2)
        sheetScroll.measure(View.MeasureSpec.makeMeasureSpec(a.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        if (sheetScroll.measuredHeight > max) sheetScroll.layoutParams = LinearLayout.LayoutParams(-1, max)
    }

    private fun renderSheet() {
        sheet.removeAllViews()
        // Picking a stop / Home / Work.
        pickMode?.let { mode ->
            sheet.addView(ui.row(
                ui.text(when (mode) { "stop" -> "Add a stop"; "home" -> "Set Home"; else -> "Set Work" }, 18f),
                ui.link("Cancel") { pickMode = null; selected = null; closeCategory(); refreshHome() }.apply { gravity = Gravity.END },
            ))
            sheet.addView(ui.text("Search above, use a category, or tap a pin.", 13f, ui.soft))
        }
        DashHub.navError?.let { err ->
            sheet.addView(ui.text(err, 14f, ui.accent).apply { setPadding(0, ui.dp(6), 0, 0) })
            when {
                err == DashHub.NO_LOCATION_ACCESS -> sheet.addView(ui.link("Open settings ›") {
                    a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
                })
                err.startsWith("Turn on Location") -> sheet.addView(ui.link("Location settings ›") {
                    a.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                })
            }
        }
        val sel = selected
        val c = category
        when {
            sel != null -> placeCard(sel)
            c != null -> {
                sheet.addView(ui.row(ui.text(c.title, 18f), ui.link("✕") { closeCategory(); refreshHome() }.apply { gravity = Gravity.END })
                    .apply { setPadding(0, ui.dp(6), 0, ui.dp(4)) })
                val list = catResults
                when {
                    catLoading -> sheet.addView(ui.text("Searching…", 13.5f, ui.soft))
                    list == null -> sheet.addView(ui.text("Couldn't search right now. Check the internet connection.", 13.5f, ui.soft))
                    list.isEmpty() -> sheet.addView(ui.text("Nothing found nearby.", 13.5f, ui.soft))
                    else -> list.forEachIndexed { i, p -> if (i > 0) sheet.addView(ui.divider()); sheet.addView(placeRow(p) { selectPlace(p) }) }
                }
            }
            else -> {} // nothing picked: no sheet, just the map (Home, Work and recents are in the search list)
        }
        limitSheet()
        sheetVisibility()
    }

    /** The sheet shows only when it has something, and never together with the search list. */
    private fun sheetVisibility() {
        val show = sheet.childCount > 0 && panel.visibility != View.VISIBLE
        sheetCard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) homeMap?.setMapPadding(ui.dp(130), ui.dp(24))
    }

    /** One result: name, then distance · rating · open/closed · address. */
    private fun placeRow(p: Place, onClick: () -> Unit) = ui.column(
        ui.text(p.name, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
        ui.text(placeFacts(p), 12.5f, ui.soft).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
    ).apply { setPadding(0, ui.dp(9), 0, ui.dp(9)); setOnClickListener { onClick() } }

    private fun placeFacts(p: Place) = listOfNotNull(
        (p.distM ?: DashHub.lastFix?.let { me -> p.pos?.let { Geo.dist(me, it) } })?.let { Fmt.km(it / 1000) },
        p.rating?.let { "★ %.1f".format(java.util.Locale.US, it) },
        p.openNow?.let { if (it) "Open now" else "Closed" },
        p.detail.ifBlank { null },
    ).joinToString(" · ")

    /** The picked place, with what you can do with it. */
    private fun placeCard(p: Place) {
        sheet.addView(ui.row(ui.text(p.name, 20f).apply { maxLines = 2 },
            ui.link("✕") { selected = null; refreshHome() }.apply { gravity = Gravity.END }).apply { setPadding(0, ui.dp(6), 0, 0) })
        sheet.addView(ui.text(placeFacts(p), 13f, ui.soft).apply { maxLines = 2; setPadding(0, ui.dp(2), 0, ui.dp(12)) })
        val action = when (pickMode) { "stop" -> "Add stop"; "home" -> "Set as Home"; "work" -> "Set as Work"; else -> "Directions" }
        sheet.addView(ui.primary(action) { val x = p; selected = null; closeCategory(); choose(x) })
    }

    private fun selectPlace(p: Place) {
        selected = p
        p.pos?.let { homeMap?.fitTo(listOfNotNull(it, DashHub.lastFix)) }
        refreshHome()
    }

    private fun openCategory(c: Category) {
        hideKeyboard(); where.clearFocus()
        val me = DashHub.lastFix
        if (me == null) { requireLocation { DashHub.warmPosition() }; android.widget.Toast.makeText(a, "Finding your position…", android.widget.Toast.LENGTH_SHORT).show(); return }
        category = c; catResults = emptyList(); catLoading = true; selected = null
        refreshHome()
        Places.nearby(c, me) { list ->
            h.post {
                if (category != c) return@post
                catLoading = false; catResults = list
                refreshHome()
                list?.mapNotNull { it.pos }?.takeIf { it.isNotEmpty() }?.let { homeMap?.fitTo(it.take(10) + me) }
            }
        }
    }

    private fun closeCategory() { category = null; catResults = null; catLoading = false; following = true }

    private fun refreshHome() { homeKey = ""; renderHome() }

    private fun hideKeyboard() = a.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(where.windowToken, 0)

    /** Search is picking something other than a destination: a stop to add, or Home/Work to save. */
    private var pickMode: String? = null // null, "stop", "home", "work"

    // ───────────── search ─────────────

    private val results = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val h = Handler(Looper.getMainLooper())
    private var found: List<Place>? = null
    private var searching = false

    private val where: EditText by lazy {
        EditText(a).apply {
            hint = "Search here"
            setTextColor(ui.ink); setHintTextColor(ui.soft)
            textSize = 17f
            setSingleLine()
            setPadding(0, 0, 0, 0)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnFocusChangeListener { _, has ->
                if (has) requireLocation { DashHub.warmPosition() }
                if (has) selected = null
                h.post { renderResults() } // open / close the Home · Work · Recent list
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    h.removeCallbacksAndMessages(null)
                    val q = s?.toString()?.trim().orEmpty()
                    if (q.length < 3) { found = null; searching = false; renderResults(); return }
                    searching = true; renderResults()
                    h.postDelayed({
                        Places.search(q, DashHub.lastFix) { list ->
                            h.post { searching = false; searchFailed = list == null; found = list ?: emptyList(); renderResults() }
                        }
                    }, 350)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
    }

    /** Typed search: results drop down over the map (the sheet hides meanwhile). */
    private fun renderResults() {
        results.removeAllViews()
        val list = found
        val open = searching || list != null || where.hasFocus()
        panel.visibility = if (open) View.VISIBLE else View.GONE
        sheetVisibility()
        when {
            searching -> results.addView(ui.text("Searching…", 13.5f, ui.soft).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
            list == null -> {
                // Search tapped, nothing typed yet: Home, Work and recent places (like Google Maps).
                if (pickMode == null) {
                    results.addView(savedRow("home", "🏠  Home"))
                    results.addView(ui.divider())
                    results.addView(savedRow("work", "💼  Work"))
                }
                val recent = DashHub.recentPlaces
                if (recent.isNotEmpty()) results.addView(ui.label("Recent"))
                recent.forEachIndexed { i, p -> if (i > 0) results.addView(ui.divider()); results.addView(row(p, recent = true)) }
                results.addView(ui.text(if (Places.usingGoogle) "Long-press a recent place to remove it."
                    else "Searching OpenStreetMap, which misses some newer places. Add a Google key in More for complete search.",
                    12f, ui.soft).apply { setPadding(0, ui.dp(12), 0, 0) })
            }
            list.isEmpty() -> results.addView(ui.text(
                if (searchFailed) "Search didn't work. Check the internet connection and try again."
                else "No places found. Try another name, or add the area (\"mall, Thane\").", 13.5f, if (searchFailed) ui.accent else ui.soft)
                .apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
            else -> list.forEachIndexed { i, p -> if (i > 0) results.addView(ui.divider()); results.addView(row(p, recent = false)) }
        }
        if (!searching && list != null && Places.usingGoogle && GoogleAuth.problem != null) results.addView(ui.text(
            "Google refused your key, so these results are from OpenStreetMap. More → Google Maps shows why and how to fix it.",
            12f, ui.accent).apply { setPadding(0, ui.dp(12), 0, 0) })
    }

    /** The last typed search failed outright (no internet, or every service down). */
    private var searchFailed = false

    private fun row(p: Place, recent: Boolean) = ui.column(
        ui.text(p.name, 16.5f),
        ui.text(listOfNotNull(p.detail.ifBlank { null },
            (p.distM ?: DashHub.lastFix?.let { me -> p.pos?.let { Geo.dist(me, it) } })?.let { Fmt.km(it / 1000) }).joinToString(" · "),
            12.5f, ui.soft).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
    ).apply {
        setPadding(0, ui.dp(10), 0, ui.dp(10))
        setOnClickListener { choose(p) }
        if (recent) setOnLongClickListener { DashHub.forgetPlace(p); true }
    }

    /** One tap: go Home / to Work. Not set yet: tap to set. Long-press: change or remove. */
    private fun savedRow(which: String, label: String): View {
        val p = DashHub.savedPlace(which)
        return ui.column(
            ui.text(label, 16.5f),
            ui.text(p?.let { listOf(it.name, it.detail).filter { s -> s.isNotBlank() }.joinToString(" · ") } ?: "Tap to set",
                12.5f, ui.soft).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
        ).apply {
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            setOnClickListener { if (p != null) choose(p) else { pickMode = which; refreshHome(); where.requestFocus() } }
            setOnLongClickListener {
                android.app.AlertDialog.Builder(a).setTitle(label.drop(3))
                    .setItems(arrayOf("Change", "Remove")) { _, i ->
                        if (i == 0) { pickMode = which; refreshHome(); where.requestFocus() } else DashHub.setSavedPlace(which, null)
                    }.show()
                true
            }
        }
    }

    private fun choose(p: Place) {
        hideKeyboard(); where.clearFocus()
        val mode = pickMode
        where.setText(""); found = null; searching = false; renderResults()
        when (mode) {
            "stop" -> { pickMode = null; DashHub.addStop(p); refresh() }
            "home", "work" -> {
                pickMode = null
                Places.resolve(p) { r -> h.post {
                    if (r?.pos != null) DashHub.setSavedPlace(mode, r.copy(googleId = null, distM = null, rating = null, openNow = null))
                    else android.widget.Toast.makeText(a, "Couldn't get that place's location", android.widget.Toast.LENGTH_SHORT).show()
                    refreshHome()
                } }
            }
            else -> requireLocation {
                DashService.start(a)
                DashHub.plan(p)
            }
        }
    }

    // ───────────── planning & preview ─────────────

    /** Weekend loop: pick a length; a scenic round trip from here comes back for "Start". */
    private fun pickLoop() {
        val kms = intArrayOf(50, 100, 150, 200, 300)
        android.app.AlertDialog.Builder(a).setTitle("Weekend loop")
            .setItems(kms.map { "About $it km round trip" }.toTypedArray()) { _, i ->
                requireLocation { DashService.start(a); DashHub.planLoop(kms[i]) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun planning(p: Place) {
        box.addView(ui.text(p.name, 26f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        box.addView(ui.text(if (p.name.startsWith("Weekend loop")) "Building a scenic loop from here… (takes a few seconds)" else "Finding the best route…", 15f, ui.soft).apply { setPadding(0, ui.dp(6), 0, ui.dp(18)) })
        box.addView(ui.secondary("Cancel") { DashHub.cancelPlan() })
    }

    /** The map 2 km either side of the route (1 km on a long one), for riding it without signal. */
    private fun saveRoute(p: Place, r: Route) {
        fun toast(s: String) = android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_LONG).show()
        if (OfflineMaps.progress != null) { toast("Already saving a map: see More → Offline maps"); return }
        val tiles = listOf(2.0, 1.0).map { OfflineMaps.along(r.shape, it) }.firstOrNull { OfflineMaps.count(it) <= OfflineMaps.MAX_TILES }
            ?: run { toast("Too long to save in one go. Save the map around your stops from More → Offline maps."); return }
        OfflineMaps.save(a, "Route to ${p.name}", "route", tiles) { err -> toast(err ?: "Map along the route saved for offline") }
        toast("Saving the map along the route (${OfflineMaps.count(tiles)} tiles). Progress: More → Offline maps")
    }

    private fun preview(p: Place, r: Route) {
        box.addView(ui.text(p.name, 26f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        if (p.detail.isNotBlank()) box.addView(ui.text(p.detail, 13.5f, ui.soft))
        box.addView(ui.statRow(
            Fmt.km(r.lengthM / 1000) to "distance",
            Fmt.dur(r.timeSec.toInt()) to "about",
            Fmt.time(System.currentTimeMillis() + r.timeSec.toLong() * 1000) to "arrive",
        ))
        if (Router.lastSource.isNotBlank()) box.addView(ui.text(
            when {
                Router.lastSource == "Google" -> "Google route for two-wheelers, with live traffic" +
                    if (DashHub.routeStyle != RouteStyle.FASTEST && !DashHub.planIsLoop) " · ${DashHub.routeStyle.label.lowercase()}" else ""
                DashHub.googleKey.isNotBlank() && DashHub.avoidUnpaved -> "Route from OpenStreetMap: Google can't avoid unpaved roads. Turn off Unpaved for a Google route."
                DashHub.googleKey.isNotBlank() && (DashHub.planIsLoop || DashHub.routeStyle == RouteStyle.SCENIC || DashHub.routeStyle == RouteStyle.SHORTEST) ->
                    "OpenStreetMap's route was ${if (DashHub.routeStyle == RouteStyle.SHORTEST && !DashHub.planIsLoop) "shorter" else "twistier"} than Google's (no live traffic on it)"
                DashHub.googleKey.isNotBlank() -> "Google route unavailable right now, using OpenStreetMap"
                else -> "Route from OpenStreetMap"
            }, 12.5f, if (Router.lastSource == "Google") ui.soft else ui.accent).apply { setPadding(0, ui.dp(6), 0, 0) })
        box.addView(ui.space(14))
        box.addView(RouteMap(a, ui, 0.75f).apply {
            showRoute(r.shape, r.traffic)
            showStops(DashHub.planStops.mapNotNull { it.pos })
            showPosition(r.shape.firstOrNull())
        })
        trafficNote(r)?.let {
            val c = if (it.startsWith("Traffic is clear")) ui.ok else android.graphics.Color.parseColor(if (it.startsWith("Heavy")) "#D93025" else "#F29900")
            box.addView(ui.text(it, 13f, c).apply { setPadding(0, ui.dp(8), 0, 0) })
        }

        // Stops on the way (tap ✕ to remove).
        DashHub.planStops.forEachIndexed { i, s ->
            box.addView(ui.row(
                ui.text("${i + 1}.  ${s.name}", 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                ui.link("✕") { DashHub.removeStop(s) }.apply { gravity = Gravity.END },
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        box.addView(ui.row(
            ui.link("+ Add stop") { pickMode = "stop"; refresh(); where.requestFocus() },
            ui.link("⛽ Fuel on the way") { showFuel() }.apply { gravity = Gravity.END },
        ).apply { setPadding(0, ui.dp(10), 0, 0) })
        box.addView(ui.link("⬇ Save the map along this route (offline)") { saveRoute(p, r) }.apply { setPadding(0, ui.dp(8), 0, 0) })

        tripPlan(r)

        // Kind of route: tap to switch; the route is worked out again.
        box.addView(android.widget.HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, ui.dp(12), 0, 0)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("Route", 13.5f, ui.soft).apply { setPadding(0, 0, ui.dp(10), 0) })
                RouteStyle.values().forEach { s ->
                    addView(chip(s.label, DashHub.routeStyle == s) { on -> if (on) DashHub.routeStyle = s })
                }
            })
        })
        box.addView(ui.text(DashHub.routeStyle.about, 12f, ui.soft).apply { setPadding(0, ui.dp(4), 0, 0) })

        // Avoid: tap to switch; the route is worked out again.
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(10), 0, 0)
            addView(ui.text("Avoid", 13.5f, ui.soft).apply { setPadding(0, 0, ui.dp(10), 0) })
            addView(chip("Tolls", DashHub.avoidTolls) { DashHub.avoidTolls = it })
            addView(chip("Highways", DashHub.avoidHighways) { DashHub.avoidHighways = it })
            addView(chip("Unpaved", DashHub.avoidUnpaved) { DashHub.avoidUnpaved = it })
        })
        box.addView(ui.space(14))
        box.addView(ui.row(ui.secondary("Cancel") { DashHub.cancelPlan() }, ui.primary("Start") { DashHub.startNavigation() }))
    }

    /**
     * Road-trip plan for the previewed route: each stop with its distance and time, then fuel needed,
     * what it will cost (at the last logged price), and when to refuel given the range now.
     */
    private fun tripPlan(r: Route) {
        val km = r.lengthM / 1000
        val cum = DoubleArray(r.shape.size)
        for (i in 1 until r.shape.size) cum[i] = cum[i - 1] + Geo.dist(r.shape[i - 1], r.shape[i])
        val total = cum.last().coerceAtLeast(1.0)
        val stopsAt = r.maneuvers.filter { it.isStop }.map { cum[it.beginIndex.coerceIn(0, cum.lastIndex)] }
        val names = if (DashHub.planIsLoop) emptyList() else DashHub.planStops.map { it.name }
        box.addView(ui.text("TRIP PLAN", 11.5f, ui.soft, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.12f; setPadding(0, ui.dp(16), 0, ui.dp(4))
        })
        if (names.isNotEmpty() && stopsAt.size == names.size) {
            var prev = 0.0
            (names.zip(stopsAt) + ((DashHub.planDest?.name ?: "Destination") to total)).forEachIndexed { i, (n, at) ->
                val legKm = (at - prev) / total * km
                val legSec = ((at - prev) / total * r.timeSec).toInt()
                prev = at
                box.addView(ui.row(
                    ui.text("${i + 1}. $n", 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    ui.text("${Fmt.km(legKm)} · ${Fmt.dur(legSec)}", 13f, ui.soft).apply { gravity = Gravity.END },
                ).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) })
            }
        }
        val bars = DashHub.bike?.takeIf { DashHub.bikeFresh }?.fuelBars ?: DashHub.lastFuelBars
        val range = Range.now(DashHub.store, DashHub.vehicle, bars, DashHub.ride?.takeIf { it.vehicle == DashHub.vehicle.id }?.distanceKm ?: 0.0)
        val f = Range.forTrip(km, range?.km, DashHub.store)
        box.addView(ui.statRow(
            "%.1f L".format(java.util.Locale.US, f.litres) to "fuel needed",
            (f.cost?.let { "₹%,.0f".format(java.util.Locale.US, it) } ?: "—") to "fuel cost",
            (if (f.refuels == 0) "none" else "${f.refuels}×") to "refuels",
        ).apply { setPadding(0, ui.dp(6), 0, 0) })
        box.addView(ui.text(listOfNotNull(
            when {
                range == null -> if (DashHub.vehicle.dash) "Connect the bike once to check the fuel you have." else "Log a full tank to check the fuel you have."
                f.refuels == 0 -> "Enough fuel: ~${range.km} km range (estimate)."
                else -> "Refuel by ~${f.firstRefuelByKm} km: range now ~${range.km} km (estimate). Tap ⛽ Fuel on the way."
            },
            if (f.cost == null) "Log a fill-up with its price to see the cost." else null,
        ).joinToString(" "), 12.5f, if (range != null && f.refuels > 0) ui.accent else ui.soft).apply { setPadding(0, ui.dp(6), 0, 0) })
    }

    /** "Heavy traffic on 1.2 km of the route" (Google routes only). */
    private fun trafficNote(r: Route): String? {
        if (r.traffic.isEmpty()) return null
        var slow = 0.0; var jam = 0.0
        r.traffic.forEachIndexed { i, t -> val d = Geo.dist(r.shape[i], r.shape[i + 1]); if (t == 1) slow += d else if (t == 2) jam += d }
        return when {
            jam > 200 -> "Heavy traffic on ${Fmt.km(jam / 1000)} of the route (red on the map)"
            slow > 300 -> "Slow traffic on ${Fmt.km(slow / 1000)} of the route (orange on the map)"
            else -> "Traffic is clear on this route"
        }
    }

    /** Small on/off pill. */
    private fun chip(label: String, on: Boolean, set: (Boolean) -> Unit) = ui.text(label, 13.5f, if (on) android.graphics.Color.WHITE else ui.ink).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ui.dpf(16f)
            if (on) setColor(ui.accent) else { setColor(android.graphics.Color.TRANSPARENT); setStroke(ui.dp(1), ui.line) }
        }
        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) }
        setOnClickListener { set(!on); DashHub.replan() }
    }

    /** Petrol pumps along the route; pick one to go via it. */
    private fun showFuel() {
        val wait = android.app.AlertDialog.Builder(a).setMessage("Looking for petrol pumps along the route…").setCancelable(true).show()
        DashHub.findFuel { list ->
            runCatching { wait.dismiss() }
            if (a.isFinishing || a.isDestroyed) return@findFuel
            if (list.isNullOrEmpty()) {
                android.widget.Toast.makeText(a,
                    if (list == null) "Couldn't search for petrol pumps right now. Try again in a minute." else "No petrol pumps found close to this route.",
                    android.widget.Toast.LENGTH_LONG).show()
                return@findFuel
            }
            val from = DashHub.navigator?.alongM ?: 0.0
            val labels = list.map { f ->
                val off = when { f.offM < 60 -> "on the route"; f.offM < 1000 -> "${f.offM.toInt()} m off"; else -> "${Fmt.km(f.offM / 1000)} off the route" }
                "${f.place.name}\n${Fmt.km((f.alongM - from) / 1000)} ahead · $off"
            }.toTypedArray()
            val onWay = list.all { it.offM <= 800 }
            android.app.AlertDialog.Builder(a).setTitle(if (onWay) "Petrol pumps on the way" else "Nearest petrol pumps (none on the route)")
                .setItems(labels) { _, i ->
                    val p = list[i].place
                    if (DashHub.navigator != null) DashHub.addStopNow(p) else DashHub.addStop(p)
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    // ───────────── navigating ─────────────

    private var navMap: RouteMap? = null
    private var navigating = false
    private val banner = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bottom = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private lateinit var recenter: android.widget.TextView

    private fun rounded(color: Int, radiusDp: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = ui.dpf(radiusDp)
    }

    private val green = android.graphics.Color.parseColor("#0B8043") // turn banner, like car navigators
    private val sheetColor get() = if (ui.dark) android.graphics.Color.parseColor("#1B1D21") else android.graphics.Color.WHITE

    /** Build the full-screen guidance view once per navigation. */
    private fun buildNavLayer() {
        navLayer.removeAllViews()
        val m = if (DashHub.navigator != null) RouteMap(a, ui, navigation = true) else null
        navMap = m
        m?.let { navLayer.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        if (m == null) navLayer.setBackgroundColor(ui.bg)

        val pad = ui.dp(12)
        navLayer.addView(banner.apply {
            background = rounded(green, 16f)
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
            elevation = ui.dpf(6f)
        }, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(pad, pad, pad, 0) })

        recenter = ui.text("◎  Re-centre", 15f, ui.ink).apply {
            background = rounded(sheetColor, 22f)
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            elevation = ui.dpf(4f)
            visibility = View.GONE
            setOnClickListener { navMap?.recenter() }
        }
        navLayer.addView(recenter, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(pad, 0, 0, ui.dp(172)) }) // above the map's logo / credit
        m?.onFollowChanged = { following -> recenter.visibility = if (following) View.GONE else View.VISIBLE }

        // Round buttons on the right: petrol pumps ahead, voice on/off.
        fun roundButton(label: String, onClick: (android.widget.TextView) -> Unit) = ui.text(label, 20f, ui.ink).apply {
            gravity = Gravity.CENTER
            background = rounded(sheetColor, 26f)
            elevation = ui.dpf(4f)
            setOnClickListener { onClick(this) }
        }
        if (m != null) {
            navLayer.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(roundButton(if (DashHub.voiceOn) "🔊" else "🔇") { b ->
                    DashHub.voiceOn = !DashHub.voiceOn
                    b.text = if (DashHub.voiceOn) "🔊" else "🔇"
                    if (DashHub.voiceOn) Voice.say("Voice guidance on")
                }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
                addView(ui.space(10))
                addView(roundButton("⛽") { showFuel() }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
            }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, pad, ui.dp(172)) }) // clear of the map credit (ⓘ) above the bottom bar
        }

        navLayer.addView(bottom.apply {
            background = rounded(sheetColor, 20f)
            setPadding(ui.dp(20), ui.dp(14), ui.dp(14), ui.dp(14))
            elevation = ui.dpf(6f)
        }, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(pad, 0, pad, pad) })
    }

    private fun renderNavigating(n: NavStep) {
        if (!navigating) {
            navigating = true
            key = ""
            box.removeAllViews()
            scroll.visibility = View.GONE
            navLayer.visibility = View.VISIBLE
            buildNavLayer()
            navMapRoute = null
        }
        val nav = DashHub.navigator
        val k = listOf(n, DashHub.connected, DashHub.lastFix, nav?.snapped).joinToString()
        if (k == key) return
        key = k

        // Turn banner: arrow, distance, instruction (and the road name).
        banner.removeAllViews()
        banner.addView(ui.text(Arrows.of(n.icon), 46f, android.graphics.Color.WHITE).apply { setPadding(0, 0, ui.dp(16), 0) })
        banner.addView(ui.column(
            ui.text(if (n.status == '2') "Re-routing…" else Arrows.dist(n.toTurnM), 30f, android.graphics.Color.WHITE)
                .apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            ui.text(n.instruction, 16.5f, android.graphics.Color.WHITE).apply { maxLines = 2 },
        ), LinearLayout.LayoutParams(0, -2, 1f))

        // Bottom bar: time left, distance, arrival; dash status; end button.
        bottom.removeAllViews()
        val leftMin = n.eta?.let { ((it.timeInMillis - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) }
        bottom.addView(ui.column(
            ui.text(leftMin?.let { Fmt.dur((it * 60).toInt()) } ?: "—", 22f, ui.ok)
                .apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            ui.text(listOfNotNull(n.remainingM?.let { Arrows.dist(it) }, n.eta?.let { "arrive ${Fmt.time(it.timeInMillis)}" },
                Router.lastSource.takeIf { it.isNotBlank() && nav != null })
                .joinToString(" · "), 13.5f, ui.soft),
            ui.text(when {
                DashHub.demo -> "Demo: shows on your dashboard on the bike"
                DashHub.connected -> "● On your dashboard"
                else -> "Bike not connected"
            }, 12.5f, if (DashHub.connected && !DashHub.demo) ui.ok else ui.soft),
        ), LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(ui.text("✕", 20f, android.graphics.Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = rounded(android.graphics.Color.parseColor("#D93025"), 24f)
            setOnClickListener { DashHub.stopNavigation() }
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))

        navMap?.let { m ->
            val r = nav?.route
            // Draw only the road still ahead; show the rider on the road when on route, raw GPS when off it.
            if (r != null) m.showRoute(nav.ahead(), nav.aheadTraffic())
            m.showStops(DashHub.navStops.mapNotNull { it.pos }) // stops still ahead; a reached one disappears
            if (r != null && r !== navMapRoute) navMapRoute = r
            val pos = nav?.snapped ?: DashHub.lastFix
            val heading = DashHub.lastBearing ?: nav?.roadBearing?.toFloat()
            m.showPosition(pos, bearing = heading, speedMps = DashHub.lastSpeed)
        }
    }

    private var navMapRoute: Route? = null
}
