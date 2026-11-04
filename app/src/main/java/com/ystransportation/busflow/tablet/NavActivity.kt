package com.ystransportation.busflow.tablet

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mapbox.api.directions.v5.models.Bearing
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.bindgen.Expected
import com.mapbox.common.location.Location
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.lifecycle.requireMapboxNavigation
import com.mapbox.navigation.core.mapmatching.MapMatchingAPICallback
import com.mapbox.navigation.core.mapmatching.MapMatchingExtras
import com.mapbox.navigation.core.mapmatching.MapMatchingFailure
import com.mapbox.navigation.core.mapmatching.MapMatchingOptions
import com.mapbox.navigation.core.mapmatching.MapMatchingSuccessfulResult
import com.mapbox.navigation.core.replay.route.ReplayRouteMapper
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.ui.base.util.MapboxNavigationConsumer
import com.mapbox.navigation.ui.maps.NavigationStyles
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.voice.api.MapboxSpeechApi
import com.mapbox.navigation.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.navigation.voice.model.SpeechAnnouncement
import com.mapbox.navigation.voice.model.SpeechError
import com.mapbox.navigation.voice.model.SpeechValue
import com.mapbox.navigation.voice.model.SpeechVolume
import com.ystransportation.busflow.tablet.databinding.ActivityNavBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * Turn-by-turn on the exact roads the office set for the route.
 *
 * How it works:
 *  - The approved path (a line along the roads) is cut into pieces of up to 100 points and each piece
 *    is snapped to Mapbox roads with Map Matching, so Mapbox gives turn-by-turn and voice for exactly that line.
 *  - Off the path: Mapbox's own reroute is OFF. We find the nearest spot on the planned path ahead,
 *    guide the driver back to it, then continue on the planned path.
 *  - No approved path: plain directions through the stops in order.
 *  - Stops are checked off when the bus comes within 40 m; a stop the bus drives past is marked skipped.
 *  - Practice mode drives the route by itself (no GPS) so it can be tried at a desk.
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class NavActivity : AppCompatActivity() {

    private enum class Mode { PREPARING, ON_PATH, REJOINING, SWITCHING, FINISHED }

    private class Seg(val start: Int, val end: Int, var route: NavigationRoute?)
    private class StopState(val stop: Stop, val target: Point, var pathIdx: Int = 0, var status: Int = 0, var at: String? = null) // 0 to do, 1 done, 2 skipped

    private lateinit var b: ActivityNavBinding
    private lateinit var plan: Plan
    private var simulate = false

    private lateinit var navigationCamera: NavigationCamera
    private lateinit var viewportDataSource: MapboxNavigationViewportDataSource
    private lateinit var maneuverApi: MapboxManeuverApi
    private lateinit var routeLineApi: MapboxRouteLineApi
    private lateinit var routeLineView: MapboxRouteLineView
    private val routeArrowApi = MapboxRouteArrowApi()
    private lateinit var routeArrowView: MapboxRouteArrowView
    private lateinit var speechApi: MapboxSpeechApi
    private lateinit var voicePlayer: MapboxVoiceInstructionsPlayer
    private val navigationLocationProvider = NavigationLocationProvider()
    private val replayRouteMapper = ReplayRouteMapper()

    // The planned path the bus must follow, and how far along it the bus is
    private var path: List<Point> = emptyList()
    private var cum = DoubleArray(0)
    private var progressIdx = 0
    private val segments = mutableListOf<Seg>()
    private var segIndex = -1
    private var mode = Mode.PREPARING
    private var rejoinFrom = 0
    private var rejoinTo = 0
    private var lastRejoinAt = 0L
    private var offRouteCount = 0
    private var exactRoads = false

    private val stops = mutableListOf<StopState>()
    private var nextStop = 0

    private var styleReady = false
    private var lastLocation: Location? = null
    private val firstLocation = CompletableDeferred<Point>()
    private var started = false
    private var muted = false

    // Driver mode (signed in on a bus tablet): this run, the runs after it, and what's at each stop
    private var runId: String? = null
    private var chain = org.json.JSONArray()
    private var stopsByRoute = JSONObject()
    private val students = HashMap<String, List<String>>()
    private var nextTimer: android.os.CountDownTimer? = null

    private val density = Resources.getSystem().displayMetrics.density

    // ── Mapbox observers ────────────────────────────────────────────────────────

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) {}
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val loc = locationMatcherResult.enhancedLocation
            navigationLocationProvider.changePosition(location = loc, keyPoints = locationMatcherResult.keyPoints)
            viewportDataSource.onLocationChanged(loc)
            viewportDataSource.evaluate()
            lastLocation = loc
            val p = Point.fromLngLat(loc.longitude, loc.latitude)
            if (!firstLocation.isCompleted) firstLocation.complete(p)
            onPosition(p)
            trackPoint(loc)
        }
    }

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        viewportDataSource.onRouteProgressChanged(routeProgress)
        viewportDataSource.evaluate()
        b.mapView.mapboxMap.style?.let { style ->
            routeArrowView.renderManeuverUpdate(style, routeArrowApi.addUpcomingManeuverArrow(routeProgress))
        }
        val maneuvers = maneuverApi.getManeuvers(routeProgress)
        maneuvers.fold(
            { },
            {
                b.maneuverView.visibility = View.VISIBLE
                b.maneuverView.renderManeuvers(maneuvers)
            },
        )
    }

    private val routesObserver = RoutesObserver { result ->
        val style = b.mapView.mapboxMap.style
        if (result.navigationRoutes.isNotEmpty()) {
            routeLineApi.setNavigationRoutes(result.navigationRoutes) { value ->
                b.mapView.mapboxMap.style?.let { routeLineView.renderRouteDrawData(it, value) }
            }
            viewportDataSource.onRouteChanged(result.navigationRoutes.first())
            viewportDataSource.evaluate()
        } else if (style != null) {
            routeLineApi.clearRouteLine { value -> routeLineView.renderClearRouteLineValue(style, value) }
            routeArrowView.render(style, routeArrowApi.clearArrows())
            viewportDataSource.clearRouteData()
            viewportDataSource.evaluate()
        }
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onWaypointArrival(routeProgress: RouteProgress) {}
        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {}
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) { onRouteEnd() }
    }

    private val offRouteObserver = OffRouteObserver { offRoute -> if (offRoute) onOffRoute() }

    private val voiceObserver = VoiceInstructionsObserver { vi ->
        // Each piece of the path "ends" somewhere: don't say "you have arrived" there
        val text = vi.announcement() ?: ""
        val lastPiece = mode == Mode.ON_PATH && segIndex == segments.lastIndex
        if (!lastPiece && (text.contains("arriv", ignoreCase = true) || text.contains("destination", ignoreCase = true))) return@VoiceInstructionsObserver
        speechApi.generate(vi, speechCallback)
    }

    private val playerCallback = MapboxNavigationConsumer<SpeechAnnouncement> { speechApi.clean(it) }
    private val speechCallback = MapboxNavigationConsumer<Expected<SpeechError, SpeechValue>> { expected ->
        expected.fold(
            { error -> voicePlayer.play(error.fallback, playerCallback) },
            { value -> voicePlayer.play(value.announcement, playerCallback) },
        )
    }

    private val mapboxNavigation: MapboxNavigation by requireMapboxNavigation(
        onCreatedObserver = object : MapboxNavigationObserver {
            @SuppressLint("MissingPermission")
            override fun onAttached(mapboxNavigation: MapboxNavigation) {
                mapboxNavigation.registerRoutesObserver(routesObserver)
                mapboxNavigation.registerLocationObserver(locationObserver)
                mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
                mapboxNavigation.registerVoiceInstructionsObserver(voiceObserver)
                mapboxNavigation.registerArrivalObserver(arrivalObserver)
                mapboxNavigation.registerOffRouteObserver(offRouteObserver)
                // We bring the driver back to OUR path ourselves
                mapboxNavigation.setRerouteEnabled(false)
                if (simulate) mapboxNavigation.startReplayTripSession() else mapboxNavigation.startTripSession()
                if (!started) { started = true; lifecycleScope.launch { prepare() }; startTracking() }
            }

            override fun onDetached(mapboxNavigation: MapboxNavigation) {
                mapboxNavigation.unregisterRoutesObserver(routesObserver)
                mapboxNavigation.unregisterLocationObserver(locationObserver)
                mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
                mapboxNavigation.unregisterVoiceInstructionsObserver(voiceObserver)
                mapboxNavigation.unregisterArrivalObserver(arrivalObserver)
                mapboxNavigation.unregisterOffRouteObserver(offRouteObserver)
                if (simulate) mapboxNavigation.mapboxReplayer.finish()
                mapboxNavigation.setNavigationRoutes(emptyList())
                mapboxNavigation.stopTripSession()
            }
        },
        onInitialize = { MapboxNavigationApp.setup(NavigationOptions.Builder(this).build()) },
    )

    // ── Screen setup ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityNavBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Either a whole plan (picked from the route list), or a run of the signed-in driver to load
        plan = Plan.from(JSONObject(intent.getStringExtra("plan") ?: "{}"))
        runId = intent.getStringExtra("run_id")
        chain = try { org.json.JSONArray(intent.getStringExtra("chain") ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
        stopsByRoute = try { JSONObject(intent.getStringExtra("stops_by_route") ?: "{}") } catch (e: Exception) { JSONObject() }
        simulate = intent.getBooleanExtra("simulate", false)

        viewportDataSource = MapboxNavigationViewportDataSource(b.mapView.mapboxMap)
        navigationCamera = NavigationCamera(b.mapView.mapboxMap, b.mapView.camera, viewportDataSource)
        b.mapView.camera.addCameraAnimationsLifecycleListener(NavigationBasicGesturesHandler(navigationCamera))
        navigationCamera.registerNavigationCameraStateChangeObserver { state ->
            b.recenter.visibility = when (state) {
                NavigationCameraState.TRANSITION_TO_FOLLOWING, NavigationCameraState.FOLLOWING -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        viewportDataSource.overviewPadding = if (landscape) EdgeInsets(40.0 * density, 40.0 * density, 140.0 * density, 120.0 * density) else EdgeInsets(160.0 * density, 40.0 * density, 200.0 * density, 40.0 * density)
        viewportDataSource.followingPadding = if (landscape) EdgeInsets(40.0 * density, 300.0 * density, 140.0 * density, 300.0 * density) else EdgeInsets(200.0 * density, 40.0 * density, 220.0 * density, 40.0 * density)

        val distanceOptions = DistanceFormatterOptions.Builder(this).build()
        maneuverApi = MapboxManeuverApi(MapboxDistanceFormatter(distanceOptions))
        speechApi = MapboxSpeechApi(this, Locale.US.language)
        voicePlayer = MapboxVoiceInstructionsPlayer(this, Locale.US.language)
        routeLineApi = MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build())
        routeLineView = MapboxRouteLineView(MapboxRouteLineViewOptions.Builder(this).routeLineBelowLayerId("road-label-navigation").build())
        routeArrowView = MapboxRouteArrowView(RouteArrowOptions.Builder(this).build())

        b.mapView.mapboxMap.loadStyle(NavigationStyles.NAVIGATION_DAY_STYLE) { style ->
            addPlanLayers(style)
            routeLineView.initializeLayers(style)
            styleReady = true
            drawPlan()
        }
        b.mapView.location.apply {
            setLocationProvider(navigationLocationProvider)
            locationPuck = LocationPuck2D(bearingImage = ImageHolder.from(com.mapbox.navigation.ui.components.R.drawable.mapbox_navigation_puck_icon))
            puckBearingEnabled = true
            enabled = true
        }

        b.endButton.setOnClickListener { confirmEnd() }
        b.recenter.setOnClickListener { navigationCamera.requestNavigationCameraToFollowing() }
        b.routeOverview.setOnClickListener { navigationCamera.requestNavigationCameraToOverview() }
        muted = Prefs.muted(this)
        applyMute()
        b.soundButton.setOnClickListener { muted = !muted; Prefs.setMuted(this, muted); applyMute() }
        status(if (simulate) "PRACTICE · Preparing route…" else "Preparing route…", Color.parseColor("#334155"), Color.parseColor("#E2E8F0"))
        b.nextStop.text = plan.code.ifEmpty { "Loading route…" }
        b.nextDetail.text = if (plan.code.isEmpty()) "" else "${plan.day} · ${plan.stops.size} stops"
    }

    override fun onDestroy() {
        super.onDestroy()
        nextTimer?.cancel()
        maneuverApi.cancel()
        routeLineApi.cancel()
        routeLineView.cancel()
        speechApi.cancel()
        voicePlayer.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { confirmEnd() }

    private fun applyMute() {
        if (muted) b.soundButton.mute() else b.soundButton.unmute()
        voicePlayer.volume(SpeechVolume(if (muted) 0f else 1f))
    }

    private fun confirmEnd() {
        if (mode == Mode.FINISHED) { if (nextTimer != null) startNext() else finish(); return }
        AlertDialog.Builder(this)
            .setTitle("End this route?")
            .setMessage("${stops.count { it.status == 1 }} of ${stops.size} stops done.")
            .setPositiveButton("End route") { _, _ -> markDone(); finish() }
            .setNegativeButton("Keep driving", null)
            .show()
    }

    private fun status(text: String, fg: Int, bg: Int) {
        b.statusLine.text = text
        b.statusLine.setTextColor(fg)
        b.statusLine.setBackgroundColor(bg)
    }

    private fun showOnPathStatus() {
        val prefix = if (simulate) "PRACTICE · " else ""
        if (exactRoads) status("${prefix}On the planned roads", Color.parseColor("#047857"), Color.parseColor("#D1FAE5"))
        else status("${prefix}Directions through the stops (no set roads)", Color.parseColor("#B45309"), Color.parseColor("#FEF3C7"))
    }

    private fun say(text: String) {
        voicePlayer.play(SpeechAnnouncement.Builder(text).build(), playerCallback)
    }

    // ── Getting the route ready ─────────────────────────────────────────────────

    /** Driver mode: load the run's route for today, with only the stops the driver has today. */
    private suspend fun loadRunPlan(): Boolean {
        val routeId = intent.getStringExtra("route_id") ?: return true
        if (plan.routeId.isNotEmpty()) return true
        val date = intent.getStringExtra("date") ?: todayNY().toString()
        return try {
            val (j, _) = Api.route(this, routeId, date)
            var p = Plan.from(j)
            // Same stops as his My Day (a closed school's stops are already taken off there)
            val mine = stopsByRoute.optJSONArray(routeId)
            if (mine != null && mine.length() > 0) {
                val ids = HashSet<String>()
                for (i in 0 until mine.length()) {
                    val st = mine.getJSONObject(i)
                    ids.add(st.optString("id"))
                    val names = st.optJSONArray("students")
                    if (names != null && names.length() > 0) students[st.optString("id")] = (0 until names.length()).map { names.optString(it) }
                }
                p = p.copy(stops = p.stops.filter { ids.contains(it.id) })
            }
            plan = p
            b.nextStop.text = plan.code
            b.nextDetail.text = "${plan.stops.size} stops"
            true
        } catch (e: ApiError) {
            fail(e.message ?: "Could not load the route.")
            false
        }
    }

    private suspend fun prepare() {
        if (!loadRunPlan()) return
        if (plan.stops.isEmpty()) { fail("This route has no stops on the map."); return }
        exactRoads = plan.hasPath
        if (exactRoads) {
            path = Geo.decode(plan.polyline!!)
            cum = Geo.cumulative(path)
            var startIdx = 0
            if (!simulate) {
                status("Waiting for GPS…", Color.parseColor("#334155"), Color.parseColor("#E2E8F0"))
                val here = withTimeoutOrNull(30000) { firstLocation.await() }
                if (here == null) { fail("No GPS signal. Go outside or near a window and try again."); return }
                // Start from the nearest spot of the first part of the path (the bus may already be on its way)
                val (idx, d) = Geo.nearestInWindow(path, cum, here, 0, 0.0, min(cum.last() * 0.5, 8000.0))
                startIdx = if (d < 120) idx else 0
                progressIdx = startIdx
            }
            setupStops()
            drawPlan()
            if (!buildMatchedSegments(startIdx)) return
        } else {
            val yardLat = plan.yardLat
            val yardLng = plan.yardLng
            val origin: Point = if (simulate) {
                if (yardLat != null && yardLng != null) Point.fromLngLat(yardLng, yardLat) else Point.fromLngLat(plan.stops[0].lng, plan.stops[0].lat)
            } else {
                status("Waiting for GPS…", Color.parseColor("#334155"), Color.parseColor("#E2E8F0"))
                withTimeoutOrNull(30000) { firstLocation.await() } ?: run { fail("No GPS signal. Go outside or near a window and try again."); return }
            }
            if (!buildStopSegments(origin)) return
            setupStops()
            drawPlan()
        }
        // Practice: the tablet "drives" the planned path by itself
        if (simulate) startReplay(path)
        status("Ready", Color.parseColor("#047857"), Color.parseColor("#D1FAE5"))
        navigationCamera.requestNavigationCameraToFollowing()
        // Not at the path yet (real drive): first guide the bus onto it
        val here = lastLocation?.let { Point.fromLngLat(it.longitude, it.latitude) }
        if (!simulate && here != null && exactRoads && Geo.dist(here, path[progressIdx]) > 120) {
            startRejoin(here, progressIdx, firstTime = true)
        } else {
            startSegment(0)
        }
        val first = stops.getOrNull(nextStop)
        say(if (first != null) "Starting ${spoken(plan.code)}. First stop: ${first.stop.shortName}." else "Starting ${spoken(plan.code)}.")
    }

    private fun fail(msg: String) {
        mode = Mode.FINISHED
        status("Can't start", Color.parseColor("#B91C1C"), Color.parseColor("#FEE2E2"))
        b.nextStop.text = msg
        b.nextDetail.text = "Tap End to go back."
    }

    private fun startReplay(points: List<Point>) {
        val replayer = mapboxNavigation.mapboxReplayer
        replayer.stop()
        replayer.clearEvents()
        val events = replayRouteMapper.mapPointList(points)
        replayer.pushEvents(events)
        if (events.isNotEmpty()) replayer.seekTo(events[0])
        replayer.playbackSpeed(1.5)
        replayer.play()
    }

    /** Cuts the planned path into pieces and snaps each to Mapbox roads (turn-by-turn for exactly this line). */
    private suspend fun buildMatchedSegments(from: Int): Boolean {
        val idx = Geo.sample(path, cum, from, path.lastIndex)
        val pieces = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < idx.size - 1) {
            val j = min(i + 99, idx.size - 1)
            pieces.add(i to j)
            i = j
        }
        segments.clear()
        pieces.forEachIndexed { n, (a, z) ->
            status("Preparing route ${n + 1} of ${pieces.size}…", Color.parseColor("#334155"), Color.parseColor("#E2E8F0"))
            val pts = idx.subList(a, z + 1).map { path[it] }
            val route = matchOrRoute(pts) ?: run { fail("Could not prepare the route (no internet?). Try again."); return false }
            segments.add(Seg(idx[a], idx[z], route))
        }
        return segments.isNotEmpty()
    }

    /** No approved path: plain directions yard/here → stop 1 → stop 2 … (25 points per request). */
    private suspend fun buildStopSegments(origin: Point): Boolean {
        val pts = listOf(origin) + plan.stops.map { stopTarget(it) }
        val all = ArrayList<Point>()
        val ranges = mutableListOf<Pair<Int, NavigationRoute>>()
        var i = 0
        var n = 0
        val total = (pts.size + 22) / 24
        while (i < pts.size - 1) {
            n++
            status("Preparing route $n of $total…", Color.parseColor("#334155"), Color.parseColor("#E2E8F0"))
            val chunk = pts.subList(i, min(i + 25, pts.size))
            val route = requestDirections(chunk, null, if (i == 0) lastLocation?.bearing else null)
                ?: run { fail("Could not get directions (no internet?). Try again."); return false }
            val geo = route.directionsRoute.geometry()?.let { PolylineUtils.decode(it, 6) } ?: emptyList()
            all.addAll(if (all.isEmpty()) geo else geo.drop(1))
            ranges.add(all.size - 1 to route)
            i += chunk.size - 1
        }
        path = all
        cum = Geo.cumulative(path)
        segments.clear()
        var start = 0
        ranges.forEach { (end, route) -> segments.add(Seg(start, end, route)); start = end }
        return segments.isNotEmpty() && path.size > 1
    }

    private fun stopTarget(s: Stop): Point =
        if (s.curbLat != null && s.curbLng != null) Point.fromLngLat(s.curbLng, s.curbLat) else Point.fromLngLat(s.lng, s.lat)

    private fun setupStops() {
        stops.clear()
        var from = 0
        plan.stops.forEach { s ->
            val t = stopTarget(s)
            val st = StopState(s, t)
            st.pathIdx = Geo.firstNear(path, t, from)
            from = st.pathIdx
            stops.add(st)
        }
        // Stops before where the bus starts don't count
        nextStop = stops.indexOfFirst { cum[it.pathIdx] >= cum[progressIdx] - 30 }.let { if (it < 0) stops.size else it }
        for (k in 0 until nextStop) stops[k].status = 2
        updatePanel(null)
    }

    // ── Running ─────────────────────────────────────────────────────────────────

    private fun startSegment(k: Int) {
        val seg = segments.getOrNull(k) ?: run { onFinished(); return }
        val route = seg.route ?: return
        segIndex = k
        mode = Mode.ON_PATH
        mapboxNavigation.setNavigationRoutes(listOf(route))
        showOnPathStatus()
    }

    private fun onRouteEnd() {
        when (mode) {
            Mode.REJOINING -> resumeFrom(rejoinTo)
            Mode.ON_PATH -> if (segIndex < segments.lastIndex) startSegment(segIndex + 1) else onFinished()
            else -> {}
        }
    }

    private fun onFinished() {
        if (mode == Mode.FINISHED) return
        mode = Mode.FINISHED
        mapboxNavigation.setNavigationRoutes(emptyList())
        if (simulate) mapboxNavigation.mapboxReplayer.stop()
        val done = stops.count { it.status == 1 }
        val skipped = stops.count { it.status == 2 }
        status("Route finished", Color.parseColor("#047857"), Color.parseColor("#D1FAE5"))
        b.nextStop.text = "Route finished"
        b.nextDetail.text = "$done of ${stops.size} stops done" + (if (skipped > 0) " · $skipped skipped" else "") + (if (offRouteCount > 0) " · off the path $offRouteCount×" else "")
        b.endButton.text = "Close"
        b.maneuverView.visibility = View.INVISIBLE
        markDone()
        val next = if (chain.length() > 0) chain.getJSONObject(0) else null
        if (next != null && !simulate) {
            // Back-to-back: the next route starts by itself (or right away with a tap)
            say("Route finished. Next route: ${spoken(next.optString("code"))}.")
            b.endButton.text = "Start now"
            nextTimer = object : android.os.CountDownTimer(20000, 1000) {
                override fun onTick(ms: Long) { b.nextDetail.text = "Next: ${next.optString("code")} — starting in ${ms / 1000 + 1} s" }
                override fun onFinish() { startNext() }
            }.start()
        } else if (runId != null && !simulate) {
            say("Route finished. That was your last route. Clock out when you're back.")
            b.nextDetail.text = b.nextDetail.text.toString() + "\nLast route done — tap Close, then clock out."
        } else {
            say("Route finished.")
        }
    }

    // ── Live tracking: the office sees where the bus is (driver mode only, never in Practice) ──
    private val tracking get() = runId != null && !simulate
    private val pendingPoints = org.json.JSONArray()
    private val offRouteEvents = org.json.JSONArray()
    private var goingToStart = false
    private var lastPointAt = 0L
    private var lastPoint: Point? = null
    private var sending = false
    private var finalSent = false

    private fun nowIso(): String = java.time.Instant.now().toString()

    private fun trackPoint(loc: Location) {
        if (!tracking) return
        val p = Point.fromLngLat(loc.longitude, loc.latitude)
        val now = System.currentTimeMillis()
        val moved = lastPoint?.let { Geo.dist(it, p) } ?: Double.MAX_VALUE
        // A point every 2 s while moving, every 20 s while standing
        if (now - lastPointAt < 2000 || (moved < 10 && now - lastPointAt < 20000)) return
        lastPointAt = now; lastPoint = p
        if (pendingPoints.length() < 3000) pendingPoints.put(org.json.JSONArray().put(loc.latitude).put(loc.longitude).put(now / 1000))
    }

    private fun startTracking() {
        if (!tracking) return
        // The trip's log (track, stops) every 20 s
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(20000)
                if (mode == Mode.FINISHED) break
                sendTrack(false)
            }
        }
        // The live position every 3 s, so the office sees exactly where the bus is
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000)
                if (mode == Mode.FINISHED) break
                sendPosition()
            }
        }
    }

    private var posId: String? = null
    private var lastPosSent = 0L
    private var posSending = false

    private suspend fun sendPosition() {
        val loc = lastLocation ?: return
        if (posSending || loc.timestamp == lastPosSent) return
        posSending = true
        try {
            val j = Api.call(this, org.json.JSONObject()
                .put("action", "pos").put("lat", loc.latitude).put("lng", loc.longitude).put("at", loc.timestamp)
                .put("heading", loc.bearing ?: org.json.JSONObject.NULL)
                .put("speed_mph", loc.speed?.let { it * 2.237 } ?: org.json.JSONObject.NULL)
                .put("state", trackState()).put("run_id", runId).put("route_code", plan.code)
                .put("pos_id", posId ?: org.json.JSONObject.NULL))
            posId = j.optString("pos_id").ifEmpty { posId }
            lastPosSent = loc.timestamp
        } catch (e: ApiError) {
            // No signal: the next one goes when it's back (the trip log keeps every point)
        } finally {
            posSending = false
        }
    }

    private fun sendSoon() { if (tracking) lifecycleScope.launch { sendTrack(false) } }

    private fun trackState(): String = when (mode) {
        Mode.FINISHED -> "finished"
        Mode.REJOINING -> if (goingToStart) "going_to_start" else "off_path"
        else -> if (exactRoads) "on_path" else "no_set_roads"
    }

    private fun trackBody(finished: Boolean): org.json.JSONObject {
        val pts = org.json.JSONArray()
        for (i in 0 until pendingPoints.length()) pts.put(pendingPoints.get(i))
        val st = org.json.JSONArray()
        stops.filter { it.status != 0 }.forEach { s ->
            st.put(org.json.JSONObject().put("id", s.stop.id).put("number", s.stop.number).put("name", s.stop.shortName)
                .put("time", s.stop.time ?: org.json.JSONObject.NULL).put("status", if (s.status == 1) "done" else "skipped").put("at", s.at ?: org.json.JSONObject.NULL))
        }
        val nxt = stops.getOrNull(nextStop)
        var late: Double? = null
        if (nxt?.stop?.time != null) {
            val m = Regex("^(\\d{1,2}):(\\d{2})").find(nxt.stop.time!!)
            if (m != null) {
                val now = java.time.ZonedDateTime.now(NY)
                late = (now.hour * 60 + now.minute) - (m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()).toDouble()
            }
        }
        val loc = lastLocation
        return org.json.JSONObject()
            .put("action", "track").put("run_id", runId).put("route_id", plan.routeId).put("route_code", plan.code)
            .put("date", intent.getStringExtra("date") ?: todayNY().toString())
            .put("path_id", plan.json.optJSONObject("path")?.optString("id") ?: org.json.JSONObject.NULL)
            .put("points", pts).put("stops", st).put("stops_total", stops.size)
            .put("off_route_events", offRouteEvents)
            .put("state", if (finished) "finished" else trackState()).put("exact_roads", exactRoads)
            .put("next_stop", if (nxt == null) org.json.JSONObject.NULL else org.json.JSONObject().put("number", nxt.stop.number).put("name", nxt.stop.shortName).put("time", nxt.stop.time ?: org.json.JSONObject.NULL))
            .put("late_minutes", late?.takeIf { it > 0 } ?: 0.0)
            .put("heading", loc?.bearing ?: org.json.JSONObject.NULL)
            .put("speed_mph", loc?.speed?.let { it * 2.237 } ?: org.json.JSONObject.NULL)
            .put("finished", finished)
    }

    private suspend fun sendTrack(finished: Boolean) {
        if (!tracking || plan.routeId.isEmpty()) return
        if (sending && !finished) return
        sending = true
        val body = trackBody(finished)
        val sent = body.getJSONArray("points").length()
        try {
            Api.call(this, org.json.JSONObject(body.toString()))
            // Drop what went through (new points may have come in meanwhile)
            val rest = org.json.JSONArray()
            for (i in sent until pendingPoints.length()) rest.put(pendingPoints.get(i))
            while (pendingPoints.length() > 0) pendingPoints.remove(0)
            for (i in 0 until rest.length()) pendingPoints.put(rest.get(i))
        } catch (e: ApiError) {
            // No signal at the end of the route: keep it on the tablet, sent from My Day later
            if (finished) Api.saveTrack(this, body)
        } finally {
            sending = false
        }
    }

    private fun markDone() {
        val r = runId ?: return
        // Last report for this trip; runs on its own so it isn't cut off when this screen closes
        if (tracking && !finalSent) {
            finalSent = true
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()).launch { sendTrack(true) }
        }
        Prefs.markRunDone(this, intent.getStringExtra("date") ?: todayNY().toString(), r)
    }

    private fun startNext() {
        nextTimer?.cancel()
        val next = if (chain.length() > 0) chain.getJSONObject(0) else return
        val rest = org.json.JSONArray()
        for (i in 1 until chain.length()) rest.put(chain.get(i))
        startActivity(
            android.content.Intent(this, NavActivity::class.java)
                .putExtra("route_id", next.optString("route_id"))
                .putExtra("run_id", next.optString("run_id"))
                .putExtra("date", intent.getStringExtra("date"))
                .putExtra("chain", rest.toString())
                .putExtra("stops_by_route", stopsByRoute.toString()),
        )
        finish()
    }

    /** Called for every GPS update: moves progress along the path, checks stops, spots a rejoin. */
    private fun onPosition(p: Point) {
        if (path.size < 2 || mode == Mode.PREPARING || mode == Mode.FINISHED) return
        val (idx, d) = Geo.nearestInWindow(path, cum, p, progressIdx, 30.0, 800.0)
        val heading = lastLocation?.bearing
        val aligned = heading == null || idx >= path.lastIndex || Geo.angleDiff(heading, Geo.bearing(path[idx], path[min(idx + 1, path.lastIndex)])) < 60
        if (d < 35 && aligned && idx > progressIdx) progressIdx = idx
        // Back on the planned path while being guided back: continue on it right here
        if (mode == Mode.REJOINING && d < 20 && aligned && idx >= rejoinFrom - 5) resumeFrom(idx)
        checkStops(p)
    }

    private fun checkStops(p: Point) {
        var changed: StopState? = null
        val last = min(stops.size - 1, nextStop + 3)
        for (i in nextStop..last) {
            if (Geo.dist(p, stops[i].target) <= 40.0) {
                for (j in nextStop until i) if (stops[j].status == 0) { stops[j].status = 2; stops[j].at = nowIso() }
                stops[i].status = 1
                stops[i].at = nowIso()
                nextStop = i + 1
                changed = stops[i]
                break
            }
        }
        // Drove past the next stop without stopping there
        while (nextStop < stops.size && cum[progressIdx] - cum[stops[nextStop].pathIdx] > 120) {
            stops[nextStop].status = 2
            stops[nextStop].at = nowIso()
            nextStop++
            if (changed == null) changed = stops[nextStop - 1]
        }
        if (changed != null) {
            drawStops()
            sendSoon()
            if (changed.status == 1) {
                val nxt = stops.getOrNull(nextStop)
                say("Stop ${changed.stop.number}, ${changed.stop.shortName}." + if (nxt != null) " Next: ${nxt.stop.shortName}." else " That was the last stop.")
            }
        }
        updatePanel(p)
    }

    private fun updatePanel(p: Point?) {
        if (mode == Mode.FINISHED) return
        val nxt = stops.getOrNull(nextStop)
        if (nxt == null) {
            b.nextStop.text = if (plan.pathIncludesYard && exactRoads) "Back to the yard" else "Last stop done"
            b.nextDetail.text = "${stops.count { it.status == 1 }} of ${stops.size} stops done"
            return
        }
        b.nextStop.text = "Stop ${nxt.stop.number}: ${nxt.stop.shortName}"
        val kids = students[nxt.stop.id].orEmpty()
        // Along the path once on it; before that (going to the start, or off the path) at least the straight-line distance
        val along = if (p != null) max(max(0.0, cum[nxt.pathIdx] - cum[progressIdx]), Geo.dist(p, nxt.target)) else null
        b.nextDetail.text = listOfNotNull(
            fmtTime(nxt.stop.time).ifEmpty { null },
            along?.let { fmtMiles(it) },
            "${nextStop + 1} of ${stops.size}",
        ).joinToString(" · ") + if (kids.isNotEmpty()) "\n" + kids.joinToString(", ") else ""
    }

    // ── Off the path: back to the planned path (never a new shortcut) ───────────

    private fun onOffRoute() {
        if (mode != Mode.ON_PATH && mode != Mode.REJOINING) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRejoinAt < 8000) return
        val loc = lastLocation ?: return
        offRouteCount++
        offRouteEvents.put(org.json.JSONObject().put("at", nowIso()).put("lat", loc.latitude).put("lng", loc.longitude))
        sendSoon()
        startRejoin(Point.fromLngLat(loc.longitude, loc.latitude), progressIdx, firstTime = false)
    }

    private fun startRejoin(here: Point, from: Int, firstTime: Boolean) {
        lastRejoinAt = SystemClock.elapsedRealtime()
        val (near, _) = Geo.nearestInWindow(path, cum, here, from, 50.0, 3000.0)
        // Join a little ahead, so the bus doesn't have to turn around
        val join = Geo.indexAtDistance(cum, near, 150.0)
        val then = Geo.indexAtDistance(cum, join, 250.0)
        rejoinFrom = join
        rejoinTo = then
        mode = Mode.REJOINING
        goingToStart = firstTime
        status(if (firstTime) "Going to the start of the route" else "Off the path — going back to it", Color.parseColor("#B45309"), Color.parseColor("#FEF3C7"))
        if (!firstTime) say("Off route. Returning to the planned route.")
        lifecycleScope.launch {
            val pts = if (then > join) listOf(here, path[join], path[then]) else listOf(here, path[join])
            val route = requestDirections(pts, if (pts.size == 3) listOf(0, 2) else null, lastLocation?.bearing)
            if (mode != Mode.REJOINING) return@launch
            if (route != null) mapboxNavigation.setNavigationRoutes(listOf(route))
            else status("Can't find a way back — follow the gray line", Color.parseColor("#B91C1C"), Color.parseColor("#FEE2E2"))
        }
    }

    /** Continue on the planned path from path point [idx]. */
    private fun resumeFrom(idx: Int) {
        if (mode == Mode.SWITCHING || mode == Mode.FINISHED) return
        progressIdx = max(progressIdx, idx)
        goingToStart = false
        val k = segments.indexOfFirst { idx < it.end }
        if (k < 0) { onFinished(); return }
        val seg = segments[k]
        // Close to the start of a prepared piece: just use it
        if (cum[idx] - cum[seg.start] < 40) { startSegment(k); return }
        mode = Mode.SWITCHING
        lifecycleScope.launch {
            val ids = Geo.sample(path, cum, idx, seg.end)
            val route = if (ids.size >= 2) matchOrRoute(ids.map { path[it] }) else null
            if (mode != Mode.SWITCHING) return@launch
            if (route == null) { mode = Mode.ON_PATH; startSegment(min(k + 1, segments.lastIndex)); return@launch }
            segments[k] = Seg(idx, seg.end, route)
            startSegment(k)
        }
    }

    // ── Mapbox requests ─────────────────────────────────────────────────────────

    /** Map Matching for exactly these points; if Mapbox can't match them cleanly, directions through them. */
    private suspend fun matchOrRoute(points: List<Point>): NavigationRoute? {
        if (points.size < 2) return null
        requestMatch(points)?.let { return it }
        // Fallback: up to 25 evenly spread points of the piece as pass-through points
        val step = max(1, (points.size + 23) / 24)
        val picked = points.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
        if (picked.last() != points.last()) picked.add(points.last())
        val trimmed = if (picked.size > 25) picked.take(24) + points.last() else picked
        return requestDirections(trimmed, listOf(0, trimmed.size - 1), null)
    }

    private suspend fun requestMatch(points: List<Point>): NavigationRoute? = suspendCancellableCoroutine { cont ->
        val options = MapMatchingOptions.Builder()
            .coordinates(points)
            .waypoints(listOf(0, points.size - 1))
            .radiuses(points.mapIndexed { i, _ -> if (i == 0 || i == points.size - 1) 30.0 else 25.0 })
            .bannerInstructions(true)
            .voiceInstructions(true)
            .roundaboutExits(true)
            .language("en")
            .voiceUnits(MapMatchingExtras.VOICE_UNITS_IMPERIAL)
            .build()
        val id = mapboxNavigation.requestMapMatching(options, object : MapMatchingAPICallback {
            override fun success(result: MapMatchingSuccessfulResult) {
                // Several matches = Mapbox couldn't follow the line in one go; use the fallback instead
                if (cont.isActive) cont.resume(if (result.matches.size == 1) result.matches[0].navigationRoute else null)
            }
            override fun failure(failure: MapMatchingFailure) { if (cont.isActive) cont.resume(null) }
            override fun onCancel() { if (cont.isActive) cont.resume(null) }
        })
        cont.invokeOnCancellation { mapboxNavigation.cancelMapMatchingRequest(id) }
    }

    /** Directions through [points]; [waypointIdx] = which points are real stops (others are pass-through). */
    private suspend fun requestDirections(points: List<Point>, waypointIdx: List<Int>?, originBearing: Double?): NavigationRoute? =
        suspendCancellableCoroutine { cont ->
            val builder = RouteOptions.builder()
                .applyDefaultNavigationOptions()
                .applyLanguageAndVoiceUnitOptions(this)
                .coordinatesList(points)
                .alternatives(false)
            if (waypointIdx != null) builder.waypointIndicesList(waypointIdx)
            if (originBearing != null) {
                val bearings = ArrayList<Bearing?>()
                bearings.add(Bearing.builder().angle(originBearing).degrees(45.0).build())
                repeat(points.size - 1) { bearings.add(null) }
                builder.bearingsList(bearings)
            }
            val id = mapboxNavigation.requestRoutes(builder.build(), object : NavigationRouterCallback {
                override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) { if (cont.isActive) cont.resume(routes.firstOrNull()) }
                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) { if (cont.isActive) cont.resume(null) }
                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) { if (cont.isActive) cont.resume(null) }
            })
            cont.invokeOnCancellation { mapboxNavigation.cancelRouteRequest(id) }
        }

    // ── Map drawing: the whole planned path (gray) and the stops ────────────────

    private fun addPlanLayers(style: Style) {
        style.addSource(geoJsonSource("bf-plan"))
        style.addLayerBelow(lineLayer("bf-plan-line", "bf-plan") {
            lineColor(Color.parseColor("#64748B"))
            lineWidth(10.0)
            lineOpacity(0.35)
            lineCap(LineCap.ROUND)
            lineJoin(LineJoin.ROUND)
        }, "road-label-navigation")
        style.addSource(geoJsonSource("bf-stops-todo"))
        style.addSource(geoJsonSource("bf-stops-done"))
        style.addLayer(circleLayer("bf-done-c", "bf-stops-done") {
            circleColor(Color.parseColor("#94A3B8"))
            circleRadius(9.0)
            circleStrokeColor(Color.WHITE)
            circleStrokeWidth(2.0)
        })
        style.addLayer(circleLayer("bf-todo-c", "bf-stops-todo") {
            circleColor(Color.parseColor("#0D9688"))
            circleRadius(13.0)
            circleStrokeColor(Color.WHITE)
            circleStrokeWidth(2.5)
        })
        style.addLayer(symbolLayer("bf-todo-t", "bf-stops-todo") {
            textField(Expression.get("n"))
            textSize(12.0)
            textColor(Color.WHITE)
            textAllowOverlap(true)
            textIgnorePlacement(true)
            textFont(listOf("DIN Pro Bold", "Arial Unicode MS Bold"))
        })
    }

    private fun drawPlan() {
        if (!styleReady) return
        val style = b.mapView.mapboxMap.style ?: return
        if (path.size > 1) {
            style.getSourceAs<GeoJsonSource>("bf-plan")?.featureCollection(
                FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(path)))), "",
            )
        }
        drawStops()
    }

    private fun drawStops() {
        if (!styleReady) return
        val style = b.mapView.mapboxMap.style ?: return
        val todo = stops.filter { it.status == 0 }.map { s -> Feature.fromGeometry(s.target).also { it.addStringProperty("n", s.stop.number.toString()) } }
        val done = stops.filter { it.status != 0 }.map { s -> Feature.fromGeometry(s.target) }
        style.getSourceAs<GeoJsonSource>("bf-stops-todo")?.featureCollection(FeatureCollection.fromFeatures(todo), "")
        style.getSourceAs<GeoJsonSource>("bf-stops-done")?.featureCollection(FeatureCollection.fromFeatures(done), "")
    }

    /** "BASM-01A" → "B A S M 0 1 A" reads badly; say letters and numbers in groups. */
    private fun spoken(code: String): String = code.replace("-", " ")
}
