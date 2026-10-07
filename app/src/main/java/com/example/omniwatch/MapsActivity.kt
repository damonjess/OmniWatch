package com.example.omniwatch

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.example.omniwatch.data.db.AppDatabase
import com.example.omniwatch.data.db.CameraEntity
import com.example.omniwatch.data.db.CameraTags
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class MapsActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var cameraOverlay: FolderOverlay
    private lateinit var cameraCountView: TextView
    private lateinit var legendView: TextView
    private lateinit var database: AppDatabase

    /** The bundled council dataset is read from assets once per process. */
    private var councilLoaded = false

    private var fetchJob: Job? = null
    private var lastFetchedBbox: ViewportBounds? = null
    private var lastFetchAt = 0L

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshViewport() }

    private val overpassApi: OverpassApi by lazy { buildOverpassApi() }

    private var renderedCount = 0
    private var isUpdating = false
    private var fetchFailed = false
    private var failedAttempts = 0

    /** Why the last fetch failed, shown next to the count so a stall is never a dead end. */
    private var lastFailure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must run before the MapView is inflated so the tile cache and User-Agent are set.
        configureOsmdroid()

        setContentView(R.layout.activity_maps)

        database = AppDatabase.getDatabase(this)

        mapView = findViewById(R.id.map)
        mapView.setTileSource(OpenStreetMapTileSource)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(SCUNTHORPE)

        cameraCountView = findViewById(R.id.tvCameraCount)
        legendView = findViewById(R.id.tvLegend)
        showLegend()
        cameraOverlay = FolderOverlay()
        mapView.overlays.add(cameraOverlay)

        // Panning or zooming re-queries only the area that came into view. The delay folds a
        // continuous drag into one request instead of one request per frame.
        mapView.addMapListener(DelayedMapListener(viewportListener, VIEWPORT_DEBOUNCE_MS))

        // The bounding box is only meaningful once the view has been laid out.
        mapView.post { refreshViewport(force = true) }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshRunnable)
        fetchJob?.cancel()
        super.onDestroy()
    }

    /**
     * osmdroid renders OpenStreetMap tiles and needs no API key. The tile cache is pointed at
     * internal storage so no storage permission is required, and a real User-Agent is supplied
     * as required by the OSM tile usage policy.
     */
    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        val basePath = File(filesDir, "osmdroid")
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = File(basePath, "tiles")
        // OSM serves a blank placeholder tile to unidentified clients, so a real
        // User-Agent is required for the map to render at all.
        config.userAgentValue = AppUserAgent.value
    }

    private val viewportListener = object : MapListener {
        override fun onScroll(event: ScrollEvent?): Boolean {
            refreshViewport()
            return false
        }

        override fun onZoom(event: ZoomEvent?): Boolean {
            refreshViewport()
            return false
        }
    }

    /**
     * Reloads cameras for the area currently on screen. Cached data is drawn first so panning
     * feels instant, then the live Overpass result replaces it.
     */
    private fun refreshViewport(force: Boolean = false) {
        if (!::mapView.isInitialized) return
        val bounds = mapView.boundingBox.toViewportBounds()?.clampedTo(MAX_QUERY_SPAN_DEGREES)
            ?: return

        if (!force && bounds.coversSameAreaAs(lastFetchedBbox, BBOX_EPSILON)) return

        val elapsed = SystemClock.elapsedRealtime() - lastFetchAt
        if (!force && elapsed < MIN_FETCH_INTERVAL_MS) {
            // Too soon after the previous request: come back when the interval has passed.
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, MIN_FETCH_INTERVAL_MS - elapsed)
            return
        }

        lastFetchAt = SystemClock.elapsedRealtime()
        fetchJob?.cancel()
        setFetching(true)
        fetchJob = lifecycleScope.launch {
            // Council records ship with the app, so they are on the map before any network
            // round trip finishes and stay there when Overpass is unreachable.
            ensureCouncilData()
            renderViewport(bounds)

            val live = fetchOverpassCameras(bounds)
            if (live != null) {
                failedAttempts = 0
                if (live.isNotEmpty()) {
                    withContext(Dispatchers.IO) { database.cameraDao().insertCameras(live) }
                }
                // Remember the area so an unchanged viewport is not queried twice.
                lastFetchedBbox = bounds
            }

            if (isActive) {
                fetchFailed = live == null
                if (fetchFailed) scheduleRetry()
                renderViewport(bounds)
                setFetching(false)
            }
        }
    }

    /**
     * Overpass is often busy or briefly unreachable, and without a retry a failed first fetch
     * would leave the map showing nothing but the bundled council cameras until the user moved
     * the map. Backs off so a struggling server is not hammered.
     */
    private fun scheduleRetry() {
        if (failedAttempts >= MAX_RETRY_ATTEMPTS) return
        val delay = (RETRY_BASE_DELAY_MS shl failedAttempts).coerceAtMost(MAX_RETRY_DELAY_MS)
        failedAttempts++
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, delay)
    }

    /** Loads the bundled council dataset into Room once so viewport queries include it. */
    private suspend fun ensureCouncilData() {
        if (councilLoaded) return
        val councilCameras = withContext(Dispatchers.IO) {
            CouncilDataLoader.loadCouncilCctvFromAssets(this@MapsActivity)
        }
        if (councilCameras.isNotEmpty()) {
            withContext(Dispatchers.IO) { database.cameraDao().insertCameras(councilCameras) }
        }
        councilLoaded = true
    }

    private suspend fun renderViewport(bounds: ViewportBounds) {
        val cameras = withContext(Dispatchers.IO) {
            database.cameraDao().getCamerasIn(bounds.south, bounds.north, bounds.west, bounds.east)
        }
        showMarkers(cameras)
    }

    /**
     * Starts the first endpoint immediately and only launches the mirror as a hedge once the
     * first has been silent for [HEDGE_DELAY_MS]. A single Overpass query takes several seconds,
     * so trying the endpoints one after another made an unresponsive server look like a hang.
     *
     * Returns null when every endpoint failed, so callers can tell "no data" from "no answer".
     */
    private suspend fun fetchOverpassCameras(bounds: ViewportBounds): List<CameraEntity>? =
        coroutineScope {
            val query = OverpassQuery.build(bounds)
            Log.d(TAG, "Overpass query: $query")

            val attempts = OVERPASS_ENDPOINTS.mapIndexed { index, endpoint ->
                async(Dispatchers.IO) {
                    // Staggered: an overloaded first endpoint must not make every mirror answer
                    // at the same instant, which would count as hammering a free service.
                    if (index > 0) delay(HEDGE_DELAY_MS * index)
                    requestCameras(endpoint, query)
                }
            }

            try {
                var found: List<CameraEntity>? = null
                var remaining = attempts.size
                while (found == null && remaining > 0) {
                    // Take whichever attempt answers first; null means that one failed.
                    found = select<Pair<Int, List<CameraEntity>?>> {
                        attempts.forEachIndexed { index, attempt ->
                            attempt.onAwait { index to it }
                        }
                    }.second
                    remaining--
                }
                found
            } finally {
                attempts.forEach { it.cancel() }
            }
        }

    /** Returns null if this endpoint failed, and rethrows cancellation so hedging stays prompt. */
    private suspend fun requestCameras(endpoint: String, query: String): List<CameraEntity>? {
        return try {
            val response = withContext(Dispatchers.IO) {
                overpassApi.getCctvCameras(endpoint, query)
            }

            // A remark means the server rejected or could not finish the query.
            if (response.remark != null) {
                lastFailure = response.remark
                Log.w(TAG, "Overpass endpoint $endpoint returned: ${response.remark}")
                null
            } else {
                response.elements.orEmpty().mapNotNull { it.toCameraEntity() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailure = describe(e)
            Log.w(TAG, "Overpass endpoint $endpoint failed", e)
            null
        }
    }

    /** Short, human-readable reason for a failed Overpass call. */
    private fun describe(e: Exception): String = when (e) {
        is HttpException -> "HTTP ${e.code()}"
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "no network"
        else -> e.javaClass.simpleName
    }

    private fun OverpassElement.toCameraEntity(): CameraEntity? {
        val latitude = lat ?: center?.lat ?: return null
        val longitude = lon ?: center?.lon ?: return null
        val tags = tags.orEmpty()

        return CameraEntity(
            id = "osm_$id",
            lat = latitude,
            lon = longitude,
            title = tags["name"] ?: DEFAULT_CAMERA_TITLE,
            operator = tags["operator"] ?: UNKNOWN_OPERATOR,
            type = tags["surveillance:type"] ?: tags["camera:type"] ?: UNKNOWN_TYPE,
            source = SOURCE_OVERPASS,
            tagsJson = CameraTags.encode(tags),
        )
    }

    private fun buildOverpassApi(): OverpassApi {
        // Overpass answers HTTP 406 to OkHttp's default User-Agent, so identify the app.
        val client = OkHttpClient.Builder()
            // Overpass can be slow when it is busy; the defaults time out too eagerly.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", AppUserAgent.value)
                        .build()
                )
            }
            .build()

        return Retrofit.Builder()
            // Only a placeholder: every call passes its full endpoint URL via @Url.
            .baseUrl(OVERPASS_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OverpassApi::class.java)
    }

    private fun showMarkers(entities: List<CameraEntity>) {
        cameraOverlay.items.clear()
        // Council rows are added last: official hardware then sits above the community-mapped
        // cameras it overlaps, both visually and when the map decides which marker was tapped.
        val ordered = entities.sortedBy { it.source == CouncilDataLoader.SOURCE_COUNCIL }
        ordered.forEach { entity ->
            val item = entity.toClusterItem()
            val marker = Marker(mapView).apply {
                position = GeoPoint(item.lat, item.lon)
                title = item.titleStr
                snippet = "${item.operator} - ${item.type}"
                icon = markerIcon(item.markerColor())
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ ->
                    showCameraBottomSheet(item)
                    true
                }
            }
            cameraOverlay.add(marker)
        }
        mapView.invalidate()
        renderedCount = entities.size
        updateCountView()
    }

    private fun setFetching(fetching: Boolean) {
        isUpdating = fetching
        updateCountView()
    }

    /** Shows how much is on the map and whether a refresh is still in flight. */
    private fun updateCountView() {
        val summary = resources.getQuantityString(
            R.plurals.camera_count,
            renderedCount,
            renderedCount
        )
        val suffix = when {
            isUpdating -> getString(R.string.camera_count_updating)
            fetchFailed -> getString(R.string.camera_count_unreachable, lastFailure.orEmpty())
            else -> ""
        }
        cameraCountView.text = summary + suffix
    }

    private fun showCameraBottomSheet(item: CctvClusterItem) {
        val bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog.setContentView(R.layout.bottom_sheet_camera_detail)

        bottomSheetDialog.findViewById<TextView>(R.id.tvCameraSource)?.text = item.source.uppercase()
        bottomSheetDialog.findViewById<TextView>(R.id.tvCameraTitle)?.text = item.titleStr
        bottomSheetDialog.findViewById<TextView>(R.id.tvOperator)?.text =
            getString(R.string.camera_operator, item.operator)
        bottomSheetDialog.findViewById<TextView>(R.id.tvType)?.text =
            getString(R.string.camera_type, item.type)
        bottomSheetDialog.findViewById<TextView>(R.id.tvCoordinates)?.text =
            getString(R.string.camera_coordinates, item.lat, item.lon)

        renderAttributes(bottomSheetDialog, item)

        bottomSheetDialog.show()
    }

    /** Lists every attribute the source published, so nothing is hidden from the user. */
    private fun renderAttributes(dialog: BottomSheetDialog, item: CctvClusterItem) {
        val header = dialog.findViewById<TextView>(R.id.tvAllTags)
        val container = dialog.findViewById<LinearLayout>(R.id.tagContainer)
        val attributes = item.tags
            .filterKeys { it !in HIDDEN_TAG_KEYS }
            .toSortedMap()

        if (attributes.isEmpty()) {
            header?.visibility = View.GONE
            return
        }

        val density = resources.displayMetrics.density
        attributes.forEach { (key, value) ->
            val row = TextView(this).apply {
                text = getString(R.string.attribute_row, key, value)
                textSize = 14f
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            container?.addView(row)
        }
    }

    /** Council hardware is drawn blue so it stays tellable apart from the community-mapped set. */
    private fun CctvClusterItem.markerColor(): Int =
        if (source == CouncilDataLoader.SOURCE_COUNCIL) COUNCIL_COLOR else MARKER_COLOR

    /** Explains the two marker colours, since they now come from two different sources. */
    private fun showLegend() {
        val dot = "\u25CF"
        val osmLabel = getString(R.string.legend_osm)
        val councilLabel = getString(R.string.legend_council)
        val legend = SpannableString("$dot $osmLabel   $dot $councilLabel")
        val councilDot = legend.indexOf(dot, 1)
        legend.setSpan(ForegroundColorSpan(MARKER_COLOR), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(
            ForegroundColorSpan(COUNCIL_COLOR),
            councilDot,
            councilDot + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        legendView.text = legend
    }

    /** Simple coloured dot marking a camera on the map. */
    private fun markerIcon(color: Int): Drawable {
        val density = resources.displayMetrics.density
        val size = (MARKER_SIZE_DP * density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((MARKER_STROKE_DP * density).toInt(), Color.WHITE)
            setSize(size, size)
        }
    }

    private fun BoundingBox.toViewportBounds(): ViewportBounds = ViewportBounds(
        north = latNorth,
        east = lonEast,
        south = latSouth,
        west = lonWest,
    )

    private companion object {
        const val TAG = "MapsActivity"
        const val SOURCE_OVERPASS = "OVERPASS"
        const val DEFAULT_CAMERA_TITLE = "CCTV Camera"
        const val UNKNOWN_OPERATOR = "Unknown Operator"
        const val UNKNOWN_TYPE = "Unknown Type"
        const val OVERPASS_BASE_URL = "https://overpass-api.de/"
        /**
         * The public Overpass instances are frequently too busy to answer, so several are tried.
         * They are independent servers, which is why a mirror can succeed while the main one 504s.
         */
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        /** Attributes already shown in the summary rows above the attribute list. */
        val HIDDEN_TAG_KEYS = setOf("man_made")

        const val DEFAULT_ZOOM = 12.0
        const val MARKER_SIZE_DP = 16
        const val MARKER_STROKE_DP = 2
        const val VIEWPORT_DEBOUNCE_MS = 800L

        /** How long the first endpoint gets before the mirror is asked in parallel. */
        const val HEDGE_DELAY_MS = 4_000L
        const val MIN_FETCH_INTERVAL_MS = 3_000L

        /**
         * Retries for a failed fetch, backing off to a minute apart. After the last attempt the
         * app waits for the map to move rather than polling a free service in the background.
         */
        const val RETRY_BASE_DELAY_MS = 5_000L
        const val MAX_RETRY_DELAY_MS = 60_000L
        const val MAX_RETRY_ATTEMPTS = 6
        const val MAX_QUERY_SPAN_DEGREES = 0.6
        const val BBOX_EPSILON = 1e-6
        /** Amber stands out against OpenStreetMap's pale basemap. */
        val MARKER_COLOR: Int = "#FB8C00".toColorInt()
        /** Blue reads as "official" against the amber OpenStreetMap markers. */
        val COUNCIL_COLOR: Int = "#1E88E5".toColorInt()
        val SCUNTHORPE = GeoPoint(53.58, -0.65)
    }
}
