package dev.local.outboundcompanion

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.WindowManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import outbound.core.GrantScope
import outbound.core.ChipState
import outbound.core.LivePolicy
import outbound.core.LiveTracker
import outbound.core.LiveSnapshot
import outbound.core.VanDecision
import outbound.core.VanInput
import outbound.core.VanMemory
import outbound.core.VanPolicy
import outbound.core.VanSource
import outbound.core.PositionDecision
import outbound.core.PositionSource
import outbound.core.DestinationMath
import outbound.core.MapCalibration
import outbound.core.FeatureKind
import outbound.core.FeatureTable
import outbound.core.FeatureText
import outbound.core.PoiTable
import outbound.core.GuideTable
import outbound.core.MapViewState
import outbound.core.Pretty
import outbound.core.Region
import outbound.core.StorageParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor() // save listing / decoding / bitmaps
    private val liveIo = Executors.newSingleThreadExecutor() // livepos.bin only, so a slow save load cannot starve live reads
    private val loading = AtomicBoolean(false)

    private lateinit var source: SafSaveSource
    private lateinit var loader: SaveLoader

    private var lastGood: Loaded? = null
    private var lastGoodSig: String? = null
    private var lastListing: SafSaveSource.Listing? = null
    private var updatedAt: String = "-"
    private var note: String = ""

    private var bitmapRegion: Region? = null
    private var bitmap: Bitmap? = null

    private var destroyed = false
    private var refreshing = false
    private var pendingRefresh = false
    private var grantRequested = false
    private var scopeWarning = ""

    // Live position. Main-thread confined: tracker, liveState, treeCache. liveIo-thread confined: liveUri, liveTreeKey,
    // resolveState. nextResolveAt is written by both (main resets it), hence volatile.
    private val liveBusy = AtomicBoolean(false)
    private var liveUri: android.net.Uri? = null
    private var liveTreeKey: String? = null
    private var resolveState = "not found"
    @Volatile private var nextResolveAt = 0L // SystemClock.elapsedRealtime; 0 = resolve now
    private val tracker = LiveTracker()
    private var liveState = "unknown"
    private var vanMemory: VanMemory? = null // main-thread confined; mirrored in prefs (van_*)
    private var treeCache: android.net.Uri? = null // granted tree; refreshed on the save poll / Refresh / folder change

    // views
    private lateinit var mapTab: TextView
    private lateinit var bagTab: TextView
    private lateinit var guideTab: TextView
    private lateinit var guidePanel: GuidePanel
    private lateinit var trackRow: LinearLayout
    private lateinit var trackGuideBtn: TextView
    private lateinit var iconBox: LinearLayout
    private lateinit var iconGuideBtn: TextView
    private var guideTable: GuideTable = GuideTable.EMPTY
    private var guideLoadError = ""
    private var forcePoiId: String? = null
    private var lastGuideRefresh = 0L
    private var tab = TAB_MAP
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var regionText: TextView
    private lateinit var noteView: TextView
    private lateinit var scopeView: TextView
    private lateinit var hintView: TextView
    private lateinit var mapNote: TextView
    private lateinit var cancelGrantBtn: Button
    private lateinit var mapPanel: FrameLayout
    private lateinit var mapView: MapView
    private lateinit var trackChip: TextView
    private lateinit var calibInfo: TextView
    private lateinit var calibBar: LinearLayout
    private lateinit var bagScroll: ScrollView
    private lateinit var bagList: LinearLayout
    private lateinit var grantPanel: LinearLayout
    private lateinit var grantText: TextView
    private val grantDefaultText = "Eden's save folder has not been granted yet.\nPick the \"persistentDataPath\" folder: open nand > user > save > 0000000000000000, then your own profile folder (the long hex name), then 0100ED9024EB8000 > persistentDataPath. Read-only access."
    private lateinit var fabs: LinearLayout
    private lateinit var followBtn: IconButton
    private lateinit var menuScrim: View
    private lateinit var menuCard: LinearLayout
    private lateinit var debugCard: LinearLayout
    private lateinit var debugView: TextView
    private lateinit var settingsCard: LinearLayout
    private lateinit var settings: Settings
    private var followStartDone = false
    private var backCallback: OnBackInvokedCallback? = null
    private var backRegistered = false
    private var calibrating = false
    private var poiTable: PoiTable = PoiTable.EMPTY
    private var poiLoadError = ""
    private var featureTable: FeatureTable = FeatureTable.EMPTY
    private var featureLoadError = ""
    private lateinit var iconChip: TextView
    private var selectedIcon: MapIcon? = null
    private var builtIcons: List<MapIcon> = emptyList()
    private var iconsForState: Any? = null
    private var iconsForKey = ""
    private lateinit var chipStack: LinearLayout
    private var chipInsetFromLayout = false
    private var tappableDest = false
    private var onMapTab = true

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val prefs get() = getSharedPreferences("outbound", MODE_PRIVATE)

    private val poll = object : Runnable {
        override fun run() {
            refresh(force = false)
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = SafSaveSource(this)
        loader = SaveLoader(source, BuildConfig.OUTBOUND_KEY)
        settings = Settings(prefs)
        vanMemory = loadVanMemory()
        loadPoiTable()
        loadFeatureTable()
        loadGuideTable()
        setContentView(buildUi())
        applySettings()
    }

    /** pois.json is copied into the assets by the build (single source: core/src/main/resources). A failure only disables POI features. */
    private fun loadPoiTable() {
        try {
            poiTable = assets.open(PoiTable.RESOURCE).use { PoiTable.parse(String(it.readBytes(), Charsets.UTF_8)) }
        } catch (e: Exception) {
            poiTable = PoiTable.EMPTY
            poiLoadError = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    /** features.json is copied into the assets by the build, like pois.json. A failure only disables the feature icons. */
    private fun loadFeatureTable() {
        try {
            featureTable = assets.open(FeatureTable.RESOURCE).use { FeatureTable.parse(String(it.readBytes(), Charsets.UTF_8)) }
        } catch (e: Exception) {
            featureTable = FeatureTable.EMPTY
            featureLoadError = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    /** guides.json is copied into the assets by the build, like pois.json. A failure only disables the Guide tab content. */
    private fun loadGuideTable() {
        try {
            guideTable = assets.open(GuideTable.RESOURCE).use { GuideTable.parse(String(it.readBytes(), Charsets.UTF_8)) }
        } catch (e: Exception) {
            guideTable = GuideTable.EMPTY
            guideLoadError = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    private val livePoll = object : Runnable {
        override fun run() {
            pollLive()
            main.postDelayed(this, LIVE_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
        main.removeCallbacks(poll)
        poll.run()
        main.removeCallbacks(livePoll)
        livePoll.run()
    }

    override fun onPause() {
        saveView()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.removeCallbacks(poll)
        main.removeCallbacks(livePoll)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        setBackActive(false)
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        liveIo.shutdownNow()
        mapView.setImage(null, 1f)
        bitmap?.recycle()
        bitmap = null
        bitmapRegion = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI construction

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color) }

    private fun lp(w: Int, h: Int, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(px(l), px(t), px(r), px(b)) }

    private fun pill(tv: TextView, color: Int) = tv.apply {
        setPadding(px(14), px(8), px(14), px(8)); background = roundRect(Theme.OVERLAY, 20f, dp, Theme.STROKE); setTextColor(color)
        textSize = Theme.TEXT_SMALL
    }

    /** Filled rounded text button (primary = accent, otherwise subtle surface). */
    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Theme.ON_ACCENT else Theme.TEXT)
        minHeight = px(Theme.TOUCH); minimumHeight = px(Theme.TOUCH); minWidth = px(96); minimumWidth = px(96)
        stateListAnimator = null
        setPadding(px(20), 0, px(20), 0)
        background = if (primary) pressable(Theme.ACCENT, Color.rgb(90, 232, 250), 24f, dp)
        else pressable(Theme.SURFACE_HI, Color.rgb(48, 60, 74), 24f, dp)
        setOnClickListener { onClick() }
    }

    private fun segment(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun menuRow(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = Theme.TEXT_BODY; setTextColor(Theme.TEXT); gravity = Gravity.CENTER_VERTICAL
        minHeight = px(Theme.ROW_H); setPadding(px(16), 0, px(16), 0)
        background = pressable(Color.TRANSPARENT, Theme.SURFACE_HI, 10f, dp)
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Theme.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }
        val full = FrameLayout.LayoutParams(-1, -1)

        // --- map layer (fills the screen)
        mapView = MapView(this).apply {
            onIconTapped = { selectIcon(it) }
            onViewSettled = { saveView() }
            onFollowChanged = { followBtn.active = it }
        }
        mapNote = text("", 18f, Theme.WARN).apply {
            gravity = Gravity.CENTER; background = roundRect(Theme.OVERLAY, Theme.RADIUS.toFloat(), dp)
            setPadding(px(20), px(14), px(20), px(14)); visibility = View.GONE
        }
        mapPanel = FrameLayout(this).apply {
            addView(mapView, FrameLayout.LayoutParams(-1, -1))
            addView(mapNote, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }

        // --- bag layer
        bagList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(12), px(16), px(24)) }
        bagScroll = ScrollView(this).apply {
            addView(bagList); visibility = View.GONE
            clipToPadding = false; setPadding(0, px(Theme.BAR_H), 0, 0); isVerticalScrollBarEnabled = false
        }

        guidePanel = GuidePanel(this, guideTable, poiTable, { showOnMap(it) }, { updateBackCallback() })

        // --- grant panel (below the top bar so the menu stays reachable)
        grantText = text(grantDefaultText, 17f, Theme.TEXT)
        grantText.gravity = Gravity.CENTER
        grantPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Theme.BG)
            setPadding(px(32), px(Theme.BAR_H + 24), px(32), px(24)); visibility = View.GONE
            addView(grantText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(24) })
            addView(button("Grant access to Eden's folder", primary = true) { launchPicker() }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(12) })
            cancelGrantBtn = button("Cancel") { grantRequested = false; showGrant(false) }
            addView(cancelGrantBtn, LinearLayout.LayoutParams(-2, -2))
        }

        // --- zoom / follow buttons (right edge)
        val zin = IconButton(this, IconButton.Icon.PLUS, Theme.FAB, Theme.OVERLAY, true).apply {
            contentDescription = "Zoom in"; setOnClickListener { mapView.zoomIn() }
        }
        val zout = IconButton(this, IconButton.Icon.MINUS, Theme.FAB, Theme.OVERLAY, true).apply {
            contentDescription = "Zoom out"; setOnClickListener { mapView.zoomOut() }
        }
        followBtn = IconButton(this, IconButton.Icon.LOCATE, Theme.FAB, Theme.OVERLAY, true).apply {
            contentDescription = "Center on me (follow)"; setOnClickListener { mapView.setFollow(!mapView.follow) }
        }
        fabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (b in listOf(zin, zout, followBtn)) {
                addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = if (childCount == 0) 0 else px(Theme.FAB_GAP) })
            }
        }

        // --- chips under the bar: tracking + notes/warnings
        trackChip = pill(text("", Theme.TEXT_SMALL, Theme.TEXT), Theme.TEXT).apply {
            setOnClickListener { if (tappableDest) mapView.showDestination() }
        }
        trackGuideBtn = guideButton { trackedGuide()?.let { openGuide(it) } }
        trackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(trackChip)
            addView(trackGuideBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(8) })
        }
        noteView = pill(text("", Theme.TEXT_SMALL, Theme.WARN), Theme.WARN).apply { visibility = View.GONE }
        scopeView = pill(text("", Theme.TEXT_SMALL, Theme.BAD), Theme.BAD).apply { visibility = View.GONE }
        hintView = pill(text("", Theme.TEXT_SMALL, Theme.TEXT_DIM), Theme.TEXT_DIM).apply { visibility = View.GONE }
        val chips = LinearLayout(this).apply {
            chipStack = this
            orientation = LinearLayout.VERTICAL
            for (v in listOf<View>(trackRow, noteView, scopeView, hintView)) {
                addView(v, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(8) })
            }
        }

        // --- calibration bar (only while calibrating)
        calibInfo = text("", 14f, Theme.TEXT).apply { typeface = Typeface.MONOSPACE }
        calibBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = roundRect(Theme.OVERLAY, 16f, dp, Theme.STROKE); setPadding(px(16), px(12), px(16), px(12))
            addView(text("Calibrate map", Theme.TEXT_TITLE, Theme.ACCENT).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(calibInfo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(6); bottomMargin = px(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Next variant") { cycleCalibration() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(12) })
                addView(button("Done", primary = true) { calibrating = false; updateChrome() })
            })
        }

        // --- top bar
        mapTab = segment("Map") { selectTab(TAB_MAP) }
        bagTab = segment("Bag") { selectTab(TAB_BAG) }
        guideTab = segment("Guide") { selectTab(TAB_GUIDE) }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = roundRect(Theme.SURFACE_HI, 24f, dp); setPadding(px(3), px(3), px(3), px(3))
            addView(mapTab, LinearLayout.LayoutParams(px(64), px(42)))
            addView(bagTab, LinearLayout.LayoutParams(px(64), px(42)))
            addView(guideTab, LinearLayout.LayoutParams(px(70), px(42)))
        }
        statusDot = View(this).apply { background = roundRect(Theme.GREY_DOT, 6f, dp) }
        statusText = text("Waiting for save...", 15f, Theme.TEXT).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        regionText = text("", 12f, Theme.TEXT_DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundRect(Theme.SURFACE_HI, 20f, dp); setPadding(px(10), 0, px(10), 0)
            addView(statusDot, LinearLayout.LayoutParams(px(10), px(10)).apply { rightMargin = px(8) })
            addView(statusText)
            addView(regionText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(8) })
        }
        val more = IconButton(this, IconButton.Icon.MORE, Theme.TOUCH, Theme.SURFACE_HI, false).apply {
            contentDescription = "Menu"; setOnClickListener { showMenu(true) }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.BAR); setPadding(px(12), 0, px(12), 0)
            addView(seg)
            // The wrapper takes the space left of the menu button, so a long status text is ellipsized instead of pushing it off screen.
            addView(FrameLayout(context).apply { addView(statusChip, FrameLayout.LayoutParams(-2, px(40), Gravity.START or Gravity.CENTER_VERTICAL)) },
                LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = px(8); rightMargin = px(8) })
            addView(more)
        }

        // --- menu + debug panels
        menuScrim = View(this).apply { visibility = View.GONE; setOnClickListener { showMenu(false) } }
        menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = roundRect(Theme.SURFACE, 16f, dp, Theme.STROKE); setPadding(px(6), px(6), px(6), px(6))
            elevation = 12f * dp
            addView(menuRow("Refresh") { showMenu(false); refresh(force = true) })
            addView(menuRow("Settings") { showMenu(false); showSettings(true) })
            addView(menuRow("Change folder") { showMenu(false); grantRequested = true; showGrant(true) })
            addView(menuRow("Calibrate map") { showMenu(false); calibrating = true; selectTab(TAB_MAP); updateMarkers() })
            addView(menuRow("Debug info") { showMenu(false); showDebug(true) })
        }
        debugView = text("", 13f, Theme.TEXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(false) }
        debugCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = roundRect(Theme.SURFACE, 16f, dp, Theme.STROKE); setPadding(px(16), px(12), px(16), px(12))
            elevation = 12f * dp
            addView(text("Debug info", Theme.TEXT_TITLE, Theme.ACCENT).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(ScrollView(context).apply { addView(debugView) }, LinearLayout.LayoutParams(-1, -2, 1f).apply { topMargin = px(8); bottomMargin = px(12) })
            addView(button("Close", primary = true) { showDebug(false) })
        }

        settingsCard = buildSettingsCard()

        root.addView(mapPanel, full)
        root.addView(bagScroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(guidePanel.view, FrameLayout.LayoutParams(-1, -1))
        root.addView(grantPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(fabs, lp(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL, r = Theme.MARGIN))
        root.addView(chips, lp(-2, -2, Gravity.TOP or Gravity.START, l = Theme.MARGIN, t = Theme.BAR_H + 12, r = 100))
        iconChip = pill(text("", Theme.TEXT_SMALL, Theme.TEXT), Theme.TEXT).apply {
            maxLines = 3; elevation = 6f * dp
            setOnClickListener { selectIcon(null) }
        }
        iconGuideBtn = guideButton { selectedIcon?.let { ic -> guideTable.placeFor(ic.id)?.let { openGuide(it) } } }
        iconBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(iconChip)
            addView(iconGuideBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(8) })
        }
        root.addView(iconBox, lp(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, l = 96, r = 96, b = Theme.MARGIN))
        root.addView(calibBar, lp(-2, -2, Gravity.BOTTOM or Gravity.START, l = Theme.MARGIN, b = Theme.MARGIN))
        root.addView(topBar, FrameLayout.LayoutParams(-1, px(Theme.BAR_H), Gravity.TOP))
        topBarView = topBar
        root.addView(menuScrim, full)
        root.addView(menuCard, lp(px(240), -2, Gravity.TOP or Gravity.END, t = Theme.BAR_H + 4, r = 12))
        root.addView(debugCard, lp(-1, -2, Gravity.TOP, l = 24, t = Theme.BAR_H + 8, r = 24, b = 24))
        root.addView(settingsCard, lp(-1, -2, Gravity.TOP, l = 24, t = Theme.BAR_H + 8, r = 24, b = 24))
        // Edge-arrow safe rect starts below the real bottom of the chip stack (falls back to a constant before layout).
        chips.addOnLayoutChangeListener { v, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom == oldBottom && chipInsetFromLayout) return@addOnLayoutChangeListener
            chipInsetFromLayout = true
            val inset = maxOf(px(Theme.BAR_H).toFloat(), (bottom - mapPanel.top).toFloat())
            if (inset != mapView.arrowInsetTop) { mapView.arrowInsetTop = inset; mapView.invalidate() }
        }
        selectTab(TAB_MAP)
        return root
    }

    /** Small accent-text pill button used next to the map chips; at least 48 dp high. */
    private fun guideButton(onClick: () -> Unit) = pill(TextView(this).apply {
        text = "Guide"; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; minHeight = px(Theme.TOUCH); minWidth = px(64)
        elevation = 6f * dp; visibility = View.GONE; setOnClickListener { onClick() }
    }, Theme.ACCENT)

    private fun trackedGuide() = guideTable.placeFor(lastGood?.state?.trackedPoi)

    private fun openGuide(p: outbound.core.PlaceGuide) {
        guidePanel.openPlace(p)
        selectTab(TAB_GUIDE)
    }

    /** Guide button "Show on map": Map tab, nothing else switched, centre on the POI with follow off, show its chip. */
    private fun showOnMap(poiId: String) {
        val s = lastGood?.state ?: return
        val poi = poiTable.find(poiId) ?: return
        if (poi.region != s.levelScene) return
        forcePoiId = poiId // the POI icon is drawn even when the "Points of interest" setting is off
        selectTab(TAB_MAP)
        updateMarkers()
        val p = MapCalibration.variant(s.levelScene, calibIndex(s.levelScene))
        val (px0, py0) = MapCalibration.project(poi.x, poi.z, p)
        mapView.centerOn(PointF(px0.toFloat(), py0.toFloat()))
        selectIcon(builtIcons.firstOrNull { it.id == poiId && it.kind == IconKind.POI })
    }

    private var topBarView: View? = null

    private fun selectTab(t: Int) {
        val wasGuide = tab == TAB_GUIDE
        tab = t
        onMapTab = t == TAB_MAP
        mapPanel.visibility = if (t == TAB_MAP) View.VISIBLE else View.GONE
        bagScroll.visibility = if (t == TAB_BAG) View.VISIBLE else View.GONE
        guidePanel.view.visibility = if (t == TAB_GUIDE) View.VISIBLE else View.GONE
        fun style(v: TextView, selected: Boolean) {
            v.background = if (selected) roundRect(Theme.ACCENT, 21f, dp) else null
            v.setTextColor(if (selected) Theme.ON_ACCENT else Theme.TEXT_DIM)
        }
        style(mapTab, t == TAB_MAP); style(bagTab, t == TAB_BAG); style(guideTab, t == TAB_GUIDE)
        if (t == TAB_GUIDE) { guidePanel.onShown(); lastGuideRefresh = SystemClock.elapsedRealtime() }
        else if (wasGuide) guidePanel.onHidden()
        // Over the map the bar is translucent; over scrolling lists it is solid so text does not show through.
        topBarView?.setBackgroundColor(if (t == TAB_MAP) Theme.BAR else Theme.BAR_SOLID)
        updateStatusChip()
        updateChrome()
    }

    /** Shows/hides the overlays that only belong to some states. */
    private fun updateChrome() {
        val grant = grantPanel.visibility == View.VISIBLE
        fabs.visibility = if (onMapTab && !grant) View.VISIBLE else View.GONE
        trackRow.visibility = if (settings.showTracking && onMapTab && !grant && trackChip.text.isNotEmpty()) View.VISIBLE else View.GONE
        calibBar.visibility = if (calibrating && onMapTab && !grant) View.VISIBLE else View.GONE
        iconBox.visibility = if (selectedIcon != null && onMapTab && !grant && !calibrating) View.VISIBLE else View.GONE
        updateBackCallback()
    }

    private fun showMenu(show: Boolean) {
        menuScrim.visibility = if (show) View.VISIBLE else View.GONE
        menuCard.visibility = if (show) View.VISIBLE else View.GONE
        updateBackCallback()
    }

    private fun showDebug(show: Boolean) {
        debugCard.visibility = if (show) View.VISIBLE else View.GONE
        updateDebug()
        updateBackCallback()
    }

    private fun showSettings(show: Boolean) {
        settingsCard.visibility = if (show) View.VISIBLE else View.GONE
        updateBackCallback()
    }

    // ---------------------------------------------------------------- back button

    private fun panelOpen() = menuCard.visibility == View.VISIBLE || settingsCard.visibility == View.VISIBLE ||
        debugCard.visibility == View.VISIBLE || (calibrating && calibBar.visibility == View.VISIBLE) ||
        (tab == TAB_GUIDE && guidePanel.detailOpen)

    /** Closes the topmost open panel; returns false when nothing was open. */
    private fun closeTopPanel(): Boolean {
        when {
            menuCard.visibility == View.VISIBLE -> showMenu(false)
            settingsCard.visibility == View.VISIBLE -> showSettings(false)
            debugCard.visibility == View.VISIBLE -> showDebug(false)
            calibrating && calibBar.visibility == View.VISIBLE -> { calibrating = false; updateChrome() }
            tab == TAB_GUIDE && guidePanel.detailOpen -> guidePanel.closeDetail()
            else -> return false
        }
        return true
    }

    /** The back callback is registered only while a panel is open, so with nothing open back behaves as the default. */
    private fun updateBackCallback() = setBackActive(panelOpen())

    private fun setBackActive(on: Boolean) {
        if (on == backRegistered) return
        if (on) {
            val cb = backCallback ?: OnBackInvokedCallback { closeTopPanel() }.also { backCallback = it }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        } else {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        backRegistered = on
    }

    // ---------------------------------------------------------------- settings

    /** Row: label left, control right, at least 52 dp high. */
    private fun settingRow(label: String, control: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(Theme.ROW_H); setPadding(px(4), px(4), px(4), px(4))
        addView(text(label, Theme.TEXT_BODY, Theme.TEXT), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(12) })
        addView(control)
    }

    /** Segmented control in the style of the Map | Bag switch. */
    private fun segmented(options: List<String>, selected: Int, segW: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = roundRect(Theme.SURFACE_HI, 24f, dp); setPadding(px(3), px(3), px(3), px(3))
        }
        val tvs = ArrayList<TextView>()
        fun paint(sel: Int) = tvs.forEachIndexed { i, t ->
            t.background = if (i == sel) roundRect(Theme.ACCENT, 21f, dp) else null
            t.setTextColor(if (i == sel) Theme.ON_ACCENT else Theme.TEXT_DIM)
        }
        options.forEachIndexed { i, o ->
            val t = segment(o) { paint(i); onSelect(i) }
            tvs += t
            box.addView(t, LinearLayout.LayoutParams(px(segW), px(42)))
        }
        paint(selected)
        return box
    }

    private fun toggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
        settingRow(label, segmented(listOf("Off", "On"), if (value) 1 else 0, 64) { onChange(it == 1); applySettings() })

    private fun buildSettingsCard(): LinearLayout {
        val st = settings
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(v: View) {
            if (rows.childCount > 0) rows.addView(divider().apply { (layoutParams as LinearLayout.LayoutParams).apply { leftMargin = 0; rightMargin = 0 } })
            rows.addView(v)
        }
        add(settingRow("Marker size", segmented(listOf("Small", "Medium", "Large"), st.markerSize, 88) { st.markerSize = it; applySettings() }))
        add(toggleRow("Show labels next to markers", st.showLabels) { st.showLabels = it })
        add(toggleRow("Show the van marker", st.showVan) { st.showVan = it })
        add(toggleRow("Show the \"Tracking\" chip", st.showTracking) { st.showTracking = it })
        add(text("Map icons", Theme.TEXT_SMALL, Theme.ACCENT).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(px(4), px(12), px(4), px(4)) })
        add(toggleRow("Points of interest", st.showPois) { st.showPois = it })
        add(toggleRow("Campfires", st.showCampfires) { st.showCampfires = it })
        add(toggleRow("Signal towers", st.showTowers) { st.showTowers = it })
        add(toggleRow("Supply caches", st.showCaches) { st.showCaches = it })
        add(settingRow("Zoom / centre buttons side", segmented(listOf("Left", "Right"), if (st.buttonsLeft) 0 else 1, 88) { st.buttonsLeft = it == 0; applySettings() }))
        add(toggleRow("Keep screen on while visible", st.keepScreenOn) { st.keepScreenOn = it })
        add(toggleRow("Start in follow mode", st.followOnStart) { st.followOnStart = it })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = roundRect(Theme.SURFACE, 16f, dp, Theme.STROKE); setPadding(px(16), px(12), px(16), px(12))
            elevation = 12f * dp
            addView(text("Settings", Theme.TEXT_TITLE, Theme.ACCENT).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(ScrollView(context).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -2, 1f).apply { topMargin = px(8); bottomMargin = px(12) })
            addView(button("Close", primary = true) { showSettings(false) })
        }
    }

    /** Pushes every setting to the views; called at start and after each change. */
    private fun applySettings() {
        mapView.markerScale = settings.markerScale
        mapView.showLabels = settings.showLabels
        mapView.showVan = settings.showVan
        val left = settings.buttonsLeft
        // Edge arrow stays clear of the top bar + chips and of the button column.
        val side = px(Theme.FAB + Theme.MARGIN + 12)
        mapView.arrowInsetLeft = if (left) side.toFloat() else px(16).toFloat()
        mapView.arrowInsetRight = if (left) px(16).toFloat() else side.toFloat()
        if (!chipInsetFromLayout) mapView.arrowInsetTop = px(Theme.BAR_H + 56).toFloat() // until the chip stack is laid out
        mapView.arrowInsetBottom = px(16).toFloat()
        if (lastGood != null) updateMarkers()
        (fabs.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            setMargins(px(if (left) Theme.MARGIN else 0), 0, px(if (left) 0 else Theme.MARGIN), 0)
        }
        fabs.requestLayout()
        applyKeepScreenOn()
        updateChrome()
    }

    private fun applyKeepScreenOn() {
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- folder grant

    private fun showGrant(show: Boolean) {
        if (show) cancelGrantBtn.visibility = if (treeCache != null || source.grantedTree() != null) View.VISIBLE else View.GONE
        grantPanel.visibility = if (show) View.VISIBLE else View.GONE
        updateChrome()
    }

    @Suppress("DEPRECATION")
    private fun launchPicker() {
        startActivityForResult(source.buildPickerIntent(), REQ_TREE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            // Cancelled: if a grant already exists, drop the panel again.
            if (source.grantedTree() != null) { grantRequested = false; showGrant(false) }
            return
        }
        if (source.levelOf(uri) == null) {
            // Too broad (or not Eden's folder): never persist it; say why and reopen the grant panel.
            rejectGrant()
            return
        }
        try {
            source.persistGrant(uri)
            treeCache = source.grantedTree()
            lastGood = null; lastGoodSig = null
            tracker.reset(); setVanMemory(null); liveState = "unknown"; nextResolveAt = 0L
            grantRequested = false
            setScopeWarning(null)
            showGrant(false)
            refresh(force = true)
        } catch (e: SecurityException) {
            setNote("Could not keep read access to that folder")
        } catch (e: SafException) {
            rejectGrant()
        }
    }

    /** Shows the "too broad" message on the grant panel and keeps the panel open. */
    private fun rejectGrant() {
        grantRequested = true
        grantText.text = GrantScope.TOO_BROAD_MESSAGE + "\n\n" + grantDefaultText
        android.widget.Toast.makeText(this, GrantScope.TOO_BROAD_MESSAGE, android.widget.Toast.LENGTH_LONG).show()
        showGrant(true)
    }

    // ---------------------------------------------------------------- loading

    /** Runs [block] on the main thread unless the activity is gone; always ends the in-flight poll. */
    private fun postDone(block: () -> Unit) {
        main.post {
            if (destroyed) return@post
            try { block() } finally { finishPoll() }
        }
    }

    private fun finishPoll() {
        loading.set(false)
        refreshing = false
        if (pendingRefresh) { pendingRefresh = false; refresh(force = true) } else render()
    }

    private fun setScopeWarning(grantedName: String?) {
        scopeWarning = if (grantedName == null || grantedName == SafSaveSource.LEAST_PRIVILEGE_NAME || grantedName == SafSaveSource.RECOMMENDED_NAME) ""
        else "Access covers the whole game folder. Use ... > Change folder and pick persistentDataPath for the least access."
        scopeView.text = scopeWarning
        scopeView.visibility = if (scopeWarning.isEmpty()) View.GONE else View.VISIBLE
        hintView.text = "Live position needs the persistentDataPath folder: use ... > Change folder to re-pick."
        hintView.visibility = if (grantedName == SafSaveSource.LEAST_PRIVILEGE_NAME) View.VISIBLE else View.GONE
    }

    private fun refresh(force: Boolean) {
        if (destroyed) return
        if (source.dropIfTooBroad()) {
            // A broader grant kept by an older install: released, ask again.
            lastGood = null; lastGoodSig = null; lastListing = null
            rejectGrant()
        }
        val tree = source.grantedTree()
        treeCache = tree
        if (force) nextResolveAt = 0L // manual Refresh / folder change: look for livepos.bin again right away
        if (tree == null) {
            setScopeWarning(null)
            if (grantPanel.visibility != View.VISIBLE) showGrant(true)
            return
        }
        if (!grantRequested && grantPanel.visibility == View.VISIBLE) showGrant(false)
        if (force) { refreshing = true; render() }
        if (!loading.compareAndSet(false, true)) {
            if (force) pendingRefresh = true
            return
        }
        // Snapshot main-thread state before going to the I/O thread.
        val goodSnap = lastGood
        val sigSnap = lastGoodSig
        val regionSnap = bitmapRegion
        try {
            io.execute {
                var listing: SafSaveSource.Listing? = null
                try {
                    val l = source.listSavegames(tree)
                    listing = l
                    val sig = loader.signature(l)
                    val reuse = !force && sig == sigSnap && goodSnap != null
                    val loaded = (if (reuse) goodSnap else null) ?: loader.load(l)
                    val region = Region.fromId(loaded.state.levelScene)
                    val bmp = if (region != null && region != regionSnap) loadBitmap(region) else null
                    postDone {
                        // A save in another region: the old live position / bytes / change time no longer apply.
                        val prevScene = lastGood?.state?.levelScene
                        if (prevScene != null && prevScene != loaded.state.levelScene) { tracker.reset(); setVanMemory(null) }
                        lastListing = l; lastGood = loaded; lastGoodSig = sig; updatedAt = nowText()
                        syncVan()
                        setScopeWarning(l.grantedName)
                        setNote(loaded.warning)
                        if (region != null && bmp != null) replaceBitmap(region, bmp.first, bmp.second)
                        else if (region == null || region != bitmapRegion) clearBitmap()
                    }
                } catch (t: Throwable) {
                    // Never surface exception text from parsing/decoding: it could contain file content.
                    val security = t is SecurityException || (t is SafException && t.cause is SecurityException)
                    val msg = when (t) {
                        is LoadException, is SafException -> t.message ?: "load failed"
                        is OutOfMemoryError -> "save too large to process"
                        else -> "unreadable data (${t.javaClass.simpleName})"
                    }
                    postDone {
                        if (listing != null) lastListing = listing
                        if (security) { source.forgetGrant(); treeCache = null; setScopeWarning(null); showGrant(true) }
                        setNote("retrying (keeping last good data): $msg")
                    }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            loading.set(false)
        }
    }

    /** Reads livepos.bin on the io thread; skipped while a previous live read is still queued/running. */
    private fun pollLive() {
        if (destroyed) return
        val tree = treeCache ?: return
        if (!liveBusy.compareAndSet(false, true)) return
        try {
            liveIo.execute {
                var bytes: ByteArray? = null
                var mtime: Long? = null
                var state: String
                try {
                    if (liveTreeKey != tree.toString()) { liveUri = null; liveTreeKey = tree.toString(); nextResolveAt = 0L }
                    if (liveUri == null) {
                        if (SystemClock.elapsedRealtime() >= nextResolveAt) {
                            val r = source.resolveLive(tree)
                            if (r is SafSaveSource.LiveRef.Found) liveUri = r.uri
                            else nextResolveAt = SystemClock.elapsedRealtime() + RESOLVE_RETRY_MS // not there: do not re-list folders every second
                            resolveState = when (r) { is SafSaveSource.LiveRef.Found -> "found"; SafSaveSource.LiveRef.NotReachable -> "not reachable"; else -> "not found" }
                        }
                        state = resolveState
                    } else state = "found"
                    liveUri?.let {
                        bytes = source.readLive(it)
                        // One cheap metadata query per poll; failure only disables the lastModified rule.
                        mtime = try { source.liveModified(it) } catch (_: Exception) { null }
                    }
                } catch (t: Throwable) {
                    liveUri = null // re-resolve, but not on every poll
                    nextResolveAt = SystemClock.elapsedRealtime() + RESOLVE_RETRY_MS
                    resolveState = "read failed"
                    state = resolveState
                }
                val b = bytes
                val mt = mtime
                val st = state
                main.post {
                    try {
                        if (!destroyed) applyLive(st, b, mt)
                    } finally {
                        liveBusy.set(false)
                    }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            liveBusy.set(false)
        }
    }

    /** Change detection lives in [LiveTracker]; this only feeds it and redraws. */
    private fun applyLive(state: String, bytes: ByteArray?, mtime: Long?) {
        liveState = state
        tracker.observe(bytes, mtime, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        syncVan()
        renderLive()
    }

    private fun decision(): PositionDecision = LivePolicy.decide(
        LiveSnapshot(
            position = tracker.position,
            changeAgeMs = tracker.changeAgeMs(SystemClock.elapsedRealtime()),
            liveModified = tracker.modified,
            saveModified = lastGood?.modified?.takeIf { it > 0 },
            nowWallMs = System.currentTimeMillis(),
            changedWallMs = tracker.changedWallMs,
        ),
    )

    /** Pure van rule over the current state; no side effects. Null until a save is loaded. */
    private fun vanDecision(): VanDecision? {
        val g = lastGood ?: return null
        val live = tracker.position
        return VanPolicy.decide(
            VanInput(
                region = g.state.levelScene,
                saveVan = g.state.vehicle,
                saveModifiedMs = g.modified?.takeIf { it > 0 },
                live = live,
                liveFresh = decision().source == PositionSource.LIVE,
                memory = vanMemory,
                nowWallMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Applies the van rule's memory result (remember / clear) after new live data or a new save. */
    private fun syncVan() {
        val d = vanDecision() ?: return
        if (d.memory != vanMemory) setVanMemory(d.memory)
    }

    private fun setVanMemory(m: VanMemory?) {
        vanMemory = m
        val e = prefs.edit()
        if (m == null) e.remove("van_wall").remove("van_x").remove("van_y").remove("van_z").remove("van_region")
        else e.putLong("van_wall", m.wallMs).putFloat("van_x", m.x.toFloat()).putFloat("van_y", m.y.toFloat())
            .putFloat("van_z", m.z.toFloat()).putString("van_region", m.region)
        e.apply()
    }

    private fun loadVanMemory(): VanMemory? {
        val region = prefs.getString("van_region", null) ?: return null
        if (!prefs.contains("van_wall") || !prefs.contains("van_x") || !prefs.contains("van_z")) return null
        return VanMemory(
            prefs.getFloat("van_x", 0f).toDouble(), prefs.getFloat("van_y", 0f).toDouble(),
            prefs.getFloat("van_z", 0f).toDouble(), region, prefs.getLong("van_wall", 0L),
        )
    }

    private fun liveAgeSec(): Long? = tracker.changeAgeMs(SystemClock.elapsedRealtime())?.let { it / 1000 }

    private fun renderLive() {
        if (lastGood != null) { updateStatusChip(); updateMarkers() }
        updateDebug()
    }

    private fun loadBitmap(region: Region): Pair<Bitmap, Float>? {
        var sample = 1
        while (sample <= 4) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = sample
                }
                val b = assets.open("maps/${region.assetFile}").use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: return null
                return b to (MapCalibration.IMAGE_SIZE.toFloat() / b.width)
            } catch (e: OutOfMemoryError) {
                sample *= 2
            } catch (e: java.io.IOException) {
                return null
            }
        }
        return null
    }

    private fun replaceBitmap(region: Region, bmp: Bitmap, scale: Float) {
        saveView() // remember the old region's view before switching
        val old = bitmap
        bitmap = bmp; bitmapRegion = region
        mapView.setImage(bmp, scale, loadView(region))
        if (settings.followOnStart && !followStartDone) { followStartDone = true; mapView.setFollow(true) }
        if (old != null && old !== bmp) old.recycle()
    }

    private fun clearBitmap() {
        saveView()
        mapView.setImage(null, 1f)
        bitmap?.recycle()
        bitmap = null; bitmapRegion = null
    }

    private fun setNote(s: String) {
        note = s
        noteView.text = s
        noteView.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        updateDebug()
    }

    // ---------------------------------------------------------------- map view persistence (per region)

    private fun saveView() {
        val id = bitmapRegion?.id ?: return
        val st = mapView.viewState() ?: return
        prefs.edit().putFloat("view_${id}_zoom", st.zoom).putFloat("view_${id}_cx", st.centerX)
            .putFloat("view_${id}_cy", st.centerY).putBoolean("view_${id}_follow", st.follow).apply()
    }

    private fun loadView(r: Region): MapViewState? {
        if (!prefs.contains("view_${r.id}_zoom")) return null
        return MapViewState(
            prefs.getFloat("view_${r.id}_zoom", 1f), prefs.getFloat("view_${r.id}_cx", 0f),
            prefs.getFloat("view_${r.id}_cy", 0f), prefs.getBoolean("view_${r.id}_follow", false),
        )
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        val g = lastGood
        updateStatusChip()
        if (g == null) {
            updateDebug()
            return
        }
        val s = g.state
        val region = Region.fromId(s.levelScene)
        updateMarkers()
        updateChrome()
        if (region == null || region != bitmapRegion) {
            mapNote.text = "Map picture unavailable for ${region?.label ?: s.levelScene}"
            mapNote.visibility = View.VISIBLE
        } else {
            mapNote.visibility = View.GONE
        }
        renderBag(g)
        updateDebug()
    }

    /** Plain-words status: green "Live", amber "Paused · last seen ...", otherwise grey "Saved N min ago". */
    private fun updateStatusChip() {
        val g = lastGood
        val d = decision()
        val dot = when (d.chip) { ChipState.LIVE -> Theme.GOOD; ChipState.PAUSED -> Theme.WARN; ChipState.SAVED -> Theme.GREY_DOT }
        val label = when {
            refreshing -> "Refreshing…"
            g == null -> "No save loaded"
            d.chip == ChipState.LIVE -> "Live"
            d.chip == ChipState.PAUSED -> Pretty.pausedAgo(d.ageMs)
            else -> Pretty.savedAgo(g.modified?.takeIf { it > 0 }?.let { System.currentTimeMillis() - it })
        }
        statusText.text = label
        (statusDot.background as GradientDrawable).setColor(if (g == null) Theme.GREY_DOT else dot)
        regionText.text = g?.let { Region.fromId(it.state.levelScene)?.label ?: it.state.levelScene } ?: ""
        regionText.visibility = if (tab == TAB_MAP && regionText.text.isNotEmpty()) View.VISIBLE else View.GONE // keeps the top bar on one line
    }

    private fun calibIndex(regionId: String) = prefs.getInt("calib_$regionId", 0)

    private fun cycleCalibration() {
        val s = lastGood?.state ?: return
        val next = (calibIndex(s.levelScene) + 1) % MapCalibration.VARIANT_COUNT
        prefs.edit().putInt("calib_${s.levelScene}", next).apply()
        updateMarkers()
    }

    private fun updateMarkers() {
        val s = lastGood?.state
        if (s == null) { mapView.setMarkers(null, null); calibInfo.text = "No data"; return }
        val idx = calibIndex(s.levelScene)
        val p = MapCalibration.variant(s.levelScene, idx)
        fun pt(v: outbound.core.Vec3?) = v?.let { MapCalibration.project(it.x, it.z, p).let { (a, b) -> PointF(a.toFloat(), b.toFloat()) } }
        val d = decision()
        val live = d.position
        val you = if (live != null) outbound.core.Vec3(live.x.toDouble(), live.y.toDouble(), live.z.toDouble()) else s.player
        val style = when (d.source) {
            PositionSource.LIVE -> MapView.PlayerStyle.LIVE
            PositionSource.LIVE_STALE -> MapView.PlayerStyle.STALE
            PositionSource.SAVE -> MapView.PlayerStyle.SAVE
        }
        val van = vanDecision()?.position ?: s.vehicle
        mapView.setMarkers(pt(you), pt(van), style)

        // Destination: pin only in the region shown; the chip names the region otherwise.
        val poi = poiTable.find(s.trackedPoi)
        val here = poi != null && poi.region == s.levelScene
        val pin = if (here) poi.let { MapView.Pin(it.id, pt(outbound.core.Vec3(it.x, it.y, it.z))!!, it.name) } else null
        mapView.setDestination(pin)
        mapView.setIcons(iconsFor(s, idx, ::pt))
        youWorld = you
        refreshIconChip()
        tappableDest = pin != null
        trackGuideBtn.visibility = if (guideTable.placeFor(s.trackedPoi) != null) View.VISIBLE else View.GONE
        guidePanel.updateInputs(s.levelScene, you, s.trackedPoi)
        if (tab == TAB_GUIDE) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastGuideRefresh >= GUIDE_REFRESH_MS) { lastGuideRefresh = now; guidePanel.refreshDistances() }
        }
        trackChip.text = "Tracking: " + when {
            s.trackedPoi.isEmpty() -> "none"
            poi == null -> Pretty.destination(s.trackedPoi)
            !here -> poi.name + (Region.fromId(poi.region)?.let { " (${it.label})" } ?: "")
            you != null -> poi.name + " · " + DestinationMath.distanceText(DestinationMath.distance(you.x, you.z, poi.x, poi.z))
            else -> poi.name
        }
        fun raw(v: outbound.core.Vec3?) = v?.let { "x=%.1f z=%.1f".format(it.x, it.z) } ?: "n/a"
        calibInfo.text = "Variant ${idx + 1}/${MapCalibration.VARIANT_COUNT}: ${p.variantName}\nYou ${raw(you)}${when (d.source) { PositionSource.LIVE -> " (live)"; PositionSource.LIVE_STALE -> " (paused)"; else -> "" }}\nVan ${raw(van)}"
    }

    private var youWorld: outbound.core.Vec3? = null

    /** The icon list for the region shown; rebuilt only when the save, calibration variant or an icon setting changes. */
    private fun iconsFor(s: outbound.core.SaveState, calib: Int, pt: (outbound.core.Vec3?) -> PointF?): List<MapIcon> {
        val st = settings
        val key = "${s.levelScene}/$calib/${st.showPois}/${st.showCampfires}/${st.showTowers}/${st.showCaches}/$forcePoiId"
        if (iconsForState === s && iconsForKey == key) return builtIcons
        val out = ArrayList<MapIcon>(128)
        fun at(x: Double, y: Double, z: Double) = pt(outbound.core.Vec3(x, y, z))!!
        if (st.showCaches) for (f in featureTable.inRegion(s.levelScene, FeatureKind.CACHE)) {
            val stored = s.supplyCaches[f.id]
            val state = when { stored == null -> IconState.NORMAL; stored.isEmpty() -> IconState.DIM; else -> IconState.FULL }
            val detail = if (stored != null && stored.isNotEmpty()) FeatureText.contents(stored) else ""
            out += MapIcon(f.id, IconKind.CACHE, at(f.x, f.y, f.z), FeatureText.title(f), detail, f.x, f.z, state)
        }
        if (st.showCampfires) for (f in featureTable.inRegion(s.levelScene, FeatureKind.CAMPFIRE)) {
            val state = if (f.id in s.interactedCampfires) IconState.NORMAL else IconState.DIM
            out += MapIcon(f.id, IconKind.CAMPFIRE, at(f.x, f.y, f.z), FeatureText.title(f), "", f.x, f.z, state)
        }
        if (st.showTowers) for (f in featureTable.inRegion(s.levelScene, FeatureKind.TOWER)) {
            val state = if (f.id in s.usedSignalTowers) IconState.USED else IconState.NORMAL
            out += MapIcon(f.id, IconKind.TOWER, at(f.x, f.y, f.z), FeatureText.title(f), "", f.x, f.z, state)
        }
        for (p in poiTable.inRegion(s.levelScene)) {
            if (!st.showPois && p.id != forcePoiId) continue
            out += MapIcon(p.id, IconKind.POI, at(p.x, p.y, p.z), p.name, "", p.x, p.z, IconState.NORMAL)
        }
        builtIcons = out; iconsForState = s; iconsForKey = key
        return out
    }

    private fun selectIcon(ic: MapIcon?) {
        selectedIcon = ic
        if (ic == null || ic.id != forcePoiId) forcePoiId = null
        refreshIconChip()
        updateChrome()
    }

    /** Chip text: name (+ cache contents) and the distance from you; cleared when the icon is no longer drawn. */
    private fun refreshIconChip() {
        val sel = selectedIcon
        val cur = sel?.let { old -> builtIcons.firstOrNull { it.id == old.id && it.kind == old.kind } }
        if (cur == null) { selectedIcon = null; iconBox.visibility = View.GONE; return }
        selectedIcon = cur
        iconGuideBtn.visibility = if (cur.kind == IconKind.POI && guideTable.placeFor(cur.id) != null) View.VISIBLE else View.GONE
        val you = youWorld
        val dist = if (you != null) " · " + DestinationMath.distanceText(DestinationMath.distance(you.x, you.z, cur.wx, cur.wz)) else ""
        iconChip.text = cur.title + (if (cur.detail.isNotEmpty()) ": " + cur.detail else "") + dist
        updateChrome()
    }

    private fun renderBag(g: Loaded) {
        val s = g.state
        bagList.removeAllViews()
        section("Backpack", s.playerStorage, "Nothing in your backpack")
        section("Tools", s.playerTools, "No tools")
        section("Van storage", s.vehicleStorage, "Van storage is empty")
        if (s.backpacksNonEmpty) {
            val card = card()
            card.addView(sectionHeader("Player backpacks (raw)", null))
            card.addView(text(s.playerBackpacksRaw, 14f, Theme.TEXT_DIM).apply { setPadding(px(16), px(4), px(16), px(16)) })
            bagList.addView(card)
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(Theme.SURFACE, Theme.RADIUS.toFloat(), dp)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(16) }
    }

    private fun sectionHeader(title: String, count: Int?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(Theme.ROW_H); setPadding(px(16), px(4), px(16), px(4))
        addView(text(title, Theme.TEXT_TITLE, Theme.ACCENT).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        if (count != null) addView(text(count.toString(), 15f, Theme.TEXT).apply {
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = roundRect(Theme.SURFACE_HI, 14f, dp); setPadding(px(12), px(3), px(12), px(3))
        })
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Theme.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, px(1))).apply { leftMargin = px(16); rightMargin = px(16) }
    }

    private fun section(title: String, raw: List<String>, emptyText: String) {
        val rows = StorageParser.parseAll(raw).sortedBy { StorageParser.prettyName(it.id).lowercase() }
        val card = card()
        card.addView(sectionHeader(title, rows.size))
        if (rows.isEmpty()) {
            card.addView(divider())
            card.addView(text(emptyText, 16f, Theme.TEXT_DIM).apply {
                gravity = Gravity.CENTER_VERTICAL; minHeight = px(Theme.ROW_H); setPadding(px(16), 0, px(16), 0)
            })
        }
        for (e in rows) {
            card.addView(divider())
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; minimumHeight = px(Theme.ROW_H); gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(16), 0) }
            row.addView(text(StorageParser.prettyName(e.id), 18f, Theme.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            e.count?.let { row.addView(text(it.toString(), 18f, Theme.TEXT_DIM).apply { typeface = Typeface.DEFAULT_BOLD }) }
            card.addView(row)
        }
        bagList.addView(card)
    }

    /** Every technical detail that used to be in the status / debug lines. */
    private fun updateDebug() {
        if (debugCard.visibility != View.VISIBLE) return
        val tree = treeCache
        val l = lastListing
        val g = lastGood
        val s = g?.state
        val region = s?.let { Region.fromId(it.levelScene)?.label ?: it.levelScene } ?: "-"
        val played = s?.let { "${it.timePlayedSec / 3600}:${"%02d".format((it.timePlayedSec % 3600) / 60)}" } ?: "-"
        val modified = g?.let { x -> x.modified?.takeIf { it > 0 }?.let { TIME_FULL.format(Instant.ofEpochMilli(it)) } ?: "unknown" } ?: "-"
        val trackedPoi = s?.let { poiTable.find(it.trackedPoi) }
        val dec = decision()
        val src = when (dec.source) {
            PositionSource.LIVE -> "LIVE ${liveAgeSec() ?: 0}s"
            PositionSource.LIVE_STALE -> Pretty.pausedAgo(dec.ageMs).lowercase().replace("paused · ", "paused, ")
            PositionSource.SAVE -> "from save"
        }
        val vd = vanDecision()
        val vanSrc = when (vd?.source) {
            VanSource.LIVE -> "live"
            VanSource.LAST_SEEN -> "last seen " + Pretty.pausedAgo(vd.ageMs).removePrefix("Paused · last seen ")
            else -> "save"
        }
        val lp = tracker.position
        val lines = mutableListOf(
            "Region: $region",
            "Played: $played",
            "File: ${g?.fileName ?: "-"}",
            "Modified: $modified",
            "Updated: $updatedAt",
            "Position source: $src",
            "Tracking id: ${s?.trackedPoi?.ifEmpty { "none" } ?: "-"}",
            "Tracking in table: ${if (s == null || s.trackedPoi.isEmpty()) "-" else if (trackedPoi == null) "no" else "yes"}",
            "Tracking region: ${trackedPoi?.region ?: "-"}",
            "Tracking world x/z: ${trackedPoi?.let { "%.1f / %.1f".format(it.x, it.z) } ?: "-"}",
            "POI table: ${if (poiLoadError.isEmpty()) "${poiTable.size} entries" else "load failed ($poiLoadError)"}",
            "Guide table: ${if (guideLoadError.isEmpty()) "${guideTable.general.size} general, ${guideTable.regions.size} regions, ${guideTable.places.size} places (${guideTable.places.count { it.poiId != null }} linked to POIs)" else "load failed ($guideLoadError)"}",
            "Features: ${if (featureLoadError.isEmpty()) "${featureTable.size} entries" else "load failed ($featureLoadError)"}",
            "Features here: ${featureCountsText(s?.levelScene)}",
            "Towers used: ${s?.let { st -> "${featureTable.inRegion(st.levelScene, FeatureKind.TOWER).count { it.id in st.usedSignalTowers }} of ${featureTable.inRegion(st.levelScene, FeatureKind.TOWER).size} here, ${st.usedSignalTowers.size} in save" } ?: "-"}",
            "Live file: ${liveStateText()}",
            "Player state: ${lp?.playerState?.toString() ?: "-"}",
            "In vehicle: ${if (lp == null) "-" else if (lp.inVehicle) "yes" else "no"}",
            "Van source: $vanSrc",
            "Tree authority: ${tree?.authority ?: "none"}",
            "Files listed: ${l?.entries?.size ?: "-"}",
            "lastModified populated: ${l?.modifiedPopulated ?: "-"}",
            "Selection: ${g?.selectionPath ?: "-"}",
            "Folder: ${l?.folderPath ?: "-"}",
        )
        if (refreshing) lines += "Refreshing: yes"
        if (note.isNotEmpty()) lines += "Note: $note"
        if (scopeWarning.isNotEmpty()) lines += "Warning: $scopeWarning"
        debugView.text = lines.joinToString("\n")
    }

    private fun featureCountsText(region: String?): String =
        if (region == null) "-" else FeatureKind.entries.joinToString(", ") { "${it.json} ${featureTable.inRegion(region, it).size}" }

    private fun liveStateText(): String {
        val age = liveAgeSec()
        val stale = if (liveState == "found" && tracker.position != null && decision().source != PositionSource.LIVE) "stale" else liveState
        return stale + when {
            age != null -> ", last change ${age}s ago"
            tracker.position != null -> ", no change seen yet"
            else -> ", no valid data"
        }
    }

    private fun nowText() = TIME_HMS.format(Instant.now())

    companion object {
        private const val REQ_TREE = 41
        private const val TAB_MAP = 0
        private const val TAB_BAG = 1
        private const val TAB_GUIDE = 2
        private const val GUIDE_REFRESH_MS = 3_000L
        private const val POLL_MS = 15_000L
        private const val LIVE_MS = 1_000L
        private const val RESOLVE_RETRY_MS = 30_000L
        private val TIME_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        private val TIME_HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
