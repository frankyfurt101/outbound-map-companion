package dev.local.outboundcompanion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import outbound.core.Confidence
import outbound.core.DestinationMath
import outbound.core.GuideTable
import outbound.core.PlaceGuide
import outbound.core.Poi
import outbound.core.PoiTable
import outbound.core.Region
import outbound.core.Vec3

/**
 * The Guide tab: region selector, region overview, places list, place detail, general tips.
 * Views are rebuilt only when the region / detail / tab changes (never on a poll); [refreshDistances] only rewrites
 * the distance texts. Selected region, open detail and scroll position live here, so they survive tab switches.
 */
class GuidePanel(
    private val ctx: Context,
    private val guides: GuideTable,
    private val pois: PoiTable,
    private val onShowOnMap: (String) -> Unit,
    private val onDetailChanged: () -> Unit,
) {
    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(12), px(16), px(24)) }
    val view = ScrollView(ctx).apply {
        addView(list); visibility = View.GONE
        clipToPadding = false; setPadding(0, px(Theme.BAR_H), 0, 0); isVerticalScrollBarEnabled = false
    }

    // Remembered while the app stays open.
    private var selectedRegion: String? = null
    private var detail: PlaceGuide? = null
    private var expandedGeneral = -1
    private var pendingY = 0
    private var listY = 0

    // Inputs from the save (set by the activity, cheap).
    private var currentRegion: String? = null
    private var you: Vec3? = null
    private var tracked: String = ""
    private var visible = false

    private val distViews = ArrayList<Pair<TextView, Poi>>()

    val detailOpen: Boolean get() = detail != null

    fun hasGuide(poiId: String?) = guides.placeFor(poiId) != null

    /** Cheap setter; rebuilds only when the shown list would change and the tab is visible. */
    fun updateInputs(region: String?, you: Vec3?, tracked: String) {
        val changed = region != currentRegion || tracked != this.tracked
        currentRegion = region; this.you = you; this.tracked = tracked
        if (changed && visible && detail == null) rebuild()
    }

    fun onShown() { visible = true; if (selectedRegion == null) selectedRegion = defaultRegion(); rebuild() }

    fun onHidden() { if (visible) pendingY = view.scrollY; visible = false }

    fun openPlace(p: PlaceGuide) {
        selectedRegion = p.region
        if (detail == null) listY = if (visible) view.scrollY else pendingY
        detail = p
        pendingY = 0
        if (visible) rebuild()
        onDetailChanged()
    }

    fun closeDetail(): Boolean {
        if (detail == null) return false
        detail = null
        pendingY = listY
        if (visible) rebuild()
        onDetailChanged()
        return true
    }

    private fun defaultRegion(): String =
        currentRegion?.takeIf { Region.fromId(it) != null } ?: Region.entries.first().id

    /** Rewrites the distance column only. */
    fun refreshDistances() {
        if (!visible || detail != null) return
        val y = you
        for ((tv, poi) in distViews) tv.text = distText(y, poi)
    }

    private fun distText(y: Vec3?, poi: Poi): String =
        if (y != null && poi.region == currentRegion) DestinationMath.distanceText(DestinationMath.distance(y.x, y.z, poi.x, poi.z)) else ""

    // ---------------------------------------------------------------- building

    private fun rebuild() {
        list.removeAllViews()
        distViews.clear()
        val d = detail
        if (d != null) buildDetail(d) else buildList()
        val y = pendingY
        view.post { view.scrollTo(0, y) }
    }

    private fun text(s: String, size: Float, color: Int) = TextView(ctx).apply { text = s; textSize = size; setTextColor(color) }

    private fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(Theme.SURFACE, Theme.RADIUS.toFloat(), dp)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(16) }
    }

    private fun header(title: String) = text(title, Theme.TEXT_TITLE, Theme.ACCENT).apply {
        typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL
        minHeight = px(Theme.ROW_H); setPadding(px(16), px(4), px(16), px(4))
    }

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Theme.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, px(1))).apply { leftMargin = px(16); rightMargin = px(16) }
    }

    private fun body(s: String, color: Int = Theme.TEXT) = text(s, 17f, color).apply { setPadding(px(16), px(4), px(16), px(10)); setLineSpacing(0f, 1.1f) }

    private fun dot(c: Confidence) = View(ctx).apply {
        background = roundRect(confColor(c), 5f, dp)
        layoutParams = LinearLayout.LayoutParams(px(10), px(10)).apply { rightMargin = px(12) }
    }

    private fun confColor(c: Confidence) = when (c) { Confidence.HIGH -> Theme.GOOD; Confidence.MEDIUM -> Theme.WARN; Confidence.LOW -> Theme.GREY_DOT }

    private fun buildList() {
        val region = selectedRegion ?: defaultRegion()
        list.addView(regionSelector(region))
        guides.region(region)?.let { rg ->
            val c = card()
            c.addView(header("About this region"))
            if (rg.overview.isNotEmpty()) c.addView(body(rg.overview))
            for (t in rg.tips) c.addView(body("•  $t", Theme.TEXT_DIM))
            if (rg.overview.isEmpty() && rg.tips.isEmpty()) c.addView(body("No notes for this region", Theme.TEXT_DIM))
            list.addView(c)
        }

        val c = card()
        c.addView(header("Places"))
        val places = sortedPlaces(region)
        if (places.isEmpty()) { c.addView(divider()); c.addView(body("No places written up for this region", Theme.TEXT_DIM)) }
        for (p in places) { c.addView(divider()); c.addView(placeRow(p)) }
        list.addView(c)

        val g = card()
        g.addView(header("General tips"))
        val bodies = ArrayList<TextView>()
        val arrowViews = ArrayList<TextView>()
        guides.general.forEachIndexed { i, e ->
            g.addView(divider())
            val title = text(e.title, 18f, Theme.TEXT).apply { gravity = Gravity.CENTER_VERTICAL }
            val arrow = text(if (i == expandedGeneral) "–" else "+", 20f, Theme.TEXT_DIM).apply { typeface = Typeface.DEFAULT_BOLD }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(Theme.ROW_H); setPadding(px(16), px(6), px(16), px(6))
                background = pressable(Color.TRANSPARENT, Theme.SURFACE_HI, 10f, dp)
                addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(12) })
                addView(arrow)
            }
            val b = body(e.text, Theme.TEXT).apply { visibility = if (i == expandedGeneral) View.VISIBLE else View.GONE }
            bodies += b
            arrowViews += arrow
            row.setOnClickListener {
                expandedGeneral = if (expandedGeneral == i) -1 else i
                // Only one open at a time: update in place, no rebuild.
                arrowViews.forEachIndexed { k, a -> a.text = if (k == expandedGeneral) "–" else "+" }
                bodies.forEachIndexed { j, tv -> tv.visibility = if (j == expandedGeneral) View.VISIBLE else View.GONE }
            }
            g.addView(row); g.addView(b)
        }
        if (guides.general.isEmpty()) { g.addView(divider()); g.addView(body("No general tips loaded", Theme.TEXT_DIM)) }
        list.addView(g)
    }

    private fun regionSelector(selected: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; background = roundRect(Theme.SURFACE_HI, 28f, dp); setPadding(px(4), px(4), px(4), px(4))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(16) }
        }
        for (r in Region.entries) {
            val sel = r.id == selected
            box.addView(TextView(ctx).apply {
                text = r.label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 1
                setTextColor(if (sel) Theme.ON_ACCENT else Theme.TEXT_DIM)
                background = if (sel) roundRect(Theme.ACCENT, 24f, dp) else null
                setOnClickListener {
                    if (selectedRegion != r.id) { selectedRegion = r.id; pendingY = 0; rebuild() }
                }
            }, LinearLayout.LayoutParams(0, px(Theme.TOUCH), 1f))
        }
        return box
    }

    private fun sortedPlaces(region: String): List<PlaceGuide> {
        val all = guides.placesIn(region)
        val here = region == currentRegion && you != null
        fun name(p: PlaceGuide) = displayName(p).lowercase()
        fun dist(p: PlaceGuide): Double {
            val poi = pois.find(p.poiId) ?: return Double.MAX_VALUE
            val y = you ?: return Double.MAX_VALUE
            return DestinationMath.distance(y.x, y.z, poi.x, poi.z)
        }
        val cmp: Comparator<PlaceGuide> = if (here) compareBy<PlaceGuide>({ dist(it) }, { name(it) }) else compareBy { name(it) }
        val sorted = all.sortedWith(cmp)
        val pin = sorted.firstOrNull { tracked.isNotEmpty() && it.poiId == tracked } ?: return sorted
        return listOf(pin) + sorted.filter { it !== pin }
    }

    private fun displayName(p: PlaceGuide) = p.playerName.ifBlank { pois.find(p.poiId)?.name ?: p.poiId ?: "Place" }

    private fun placeRow(p: PlaceGuide): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(Theme.ROW_H); setPadding(px(16), px(6), px(16), px(6))
            background = pressable(Color.TRANSPARENT, Theme.SURFACE_HI, 10f, dp)
            setOnClickListener { openPlace(p) }
        }
        row.addView(dot(p.confidence))
        row.addView(text(displayName(p), 18f, Theme.TEXT), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(8) })
        if (tracked.isNotEmpty() && p.poiId == tracked) {
            row.addView(text("Tracking", 13f, Theme.ON_ACCENT).apply {
                typeface = Typeface.DEFAULT_BOLD; background = roundRect(Theme.ACCENT, 12f, dp); setPadding(px(10), px(2), px(10), px(2))
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(10) })
        }
        val poi = pois.find(p.poiId)
        if (poi != null) {
            val tv = text(distText(you, poi), 16f, Theme.TEXT_DIM).apply { typeface = Typeface.DEFAULT_BOLD }
            distViews += tv to poi
            row.addView(tv)
        }
        return row
    }

    private fun buildDetail(p: PlaceGuide) {
        val c = card()
        c.addView(text(displayName(p), 24f, Theme.TEXT).apply {
            typeface = Typeface.DEFAULT_BOLD; setPadding(px(16), px(14), px(16), px(4))
        })
        val reg = Region.fromId(p.region)?.label
        if (reg != null) c.addView(text(reg, Theme.TEXT_SMALL, Theme.TEXT_DIM).apply { setPadding(px(16), 0, px(16), px(6)) })
        if (p.what.isNotEmpty()) c.addView(body(p.what))
        if (p.todo.isNotEmpty()) {
            c.addView(divider()); c.addView(header("What to do"))
            p.todo.forEachIndexed { i, s -> c.addView(body("${i + 1}.  $s")) }
        }
        section(c, "You need", p.requirements)
        section(c, "Rewards", p.rewards)
        section(c, "Tips", p.tips)
        c.addView(divider())
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(Theme.ROW_H); setPadding(px(16), px(4), px(16), px(4))
            addView(dot(p.confidence))
            addView(text(p.confidence.label, 16f, Theme.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        })
        val hosts = p.sources.map { GuideTable.host(it) }.filter { it.isNotEmpty() }.distinct()
        if (hosts.isNotEmpty()) {
            c.addView(text("Sources", Theme.TEXT_SMALL, Theme.ACCENT).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(px(16), px(6), px(16), px(2)) })
            c.addView(text(hosts.joinToString("\n"), Theme.TEXT_SMALL, Theme.TEXT_DIM).apply { setPadding(px(16), 0, px(16), px(12)) })
        }
        if (p.note.isNotEmpty() && p.poiId == null) c.addView(body(p.note, Theme.TEXT_DIM))
        list.addView(c)

        val poi = pois.find(p.poiId)
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (poi != null && poi.region == currentRegion) {
            buttons.addView(btn("Show on map", true) { onShowOnMap(poi.id) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(12) })
        }
        buttons.addView(btn("Back", false) { closeDetail() })
        list.addView(buttons)
        if (poi != null && poi.region != currentRegion) {
            list.addView(text("This place is on the ${Region.fromId(poi.region)?.label ?: poi.region} map, not in your current region.", Theme.TEXT_SMALL, Theme.TEXT_DIM)
                .apply { setPadding(px(4), px(12), px(4), 0) })
        }
    }

    private fun section(c: LinearLayout, title: String, items: List<String>) {
        if (items.isEmpty()) return
        c.addView(divider()); c.addView(header(title))
        for (s in items) c.addView(body("•  $s"))
    }

    private fun btn(label: String, primary: Boolean, onClick: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Theme.ON_ACCENT else Theme.TEXT)
        minHeight = px(Theme.TOUCH); minimumHeight = px(Theme.TOUCH); minWidth = px(96); minimumWidth = px(96)
        stateListAnimator = null
        setPadding(px(20), 0, px(20), 0)
        background = if (primary) pressable(Theme.ACCENT, Color.rgb(90, 232, 250), 24f, dp)
        else pressable(Theme.SURFACE_HI, Color.rgb(48, 60, 74), 24f, dp)
        setOnClickListener { onClick() }
    }
}
