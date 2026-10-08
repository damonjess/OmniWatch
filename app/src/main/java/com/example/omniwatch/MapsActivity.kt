package com.example.omniwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.example.omniwatch.data.db.AppDatabase
import com.example.omniwatch.data.db.CameraEntity
import com.example.omniwatch.data.db.CameraTags
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
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
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class MapsActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var fovOverlay: FolderOverlay
    private lateinit var osmOverlay: FolderOverlay
    private lateinit var councilOverlay: FolderOverlay
    private lateinit var trafficOverlay: FolderOverlay
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
        
        // Move camera to Scunthorpe / M180 junction area
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(GeoPoint(53.58, -0.65))

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        locationOverlay.enableMyLocation()
        locationOverlay.isDrawAccuracyEnabled = true
        mapView.overlays.add(locationOverlay)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }

        cameraCountView = findViewById(R.id.tvCameraCount)
        legendView = findViewById(R.id.tvLegend)
        showLegend()
        fovOverlay = FolderOverlay()
        osmOverlay = FolderOverlay()
        councilOverlay = FolderOverlay()
        trafficOverlay = FolderOverlay()
        mapView.overlays.add(fovOverlay)
        mapView.overlays.add(osmOverlay)
        mapView.overlays.add(councilOverlay)
        mapView.overlays.add(trafficOverlay)

        // Panning or zooming re-queries only the area that came into view. The delay folds a
        // continuous drag into one request instead of one request per frame.
        mapView.addMapListener(DelayedMapListener(viewportListener, VIEWPORT_DEBOUNCE_MS))
        
        val fabLocateMe = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabLocateMe)
        fabLocateMe.setOnClickListener {
            val myLocation = locationOverlay.myLocation
            if (myLocation != null) {
                mapView.controller.animateTo(myLocation, 15.0, 1000L)
            } else {
                android.widget.Toast.makeText(this, "Location not available yet", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // The bounding box is only meaningful once the view has been laid out.
        mapView.post { refreshViewport(force = true) }
        
        fetchLiveTrafficCameras()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        if (::locationOverlay.isInitialized) locationOverlay.enableMyLocation()
    }

    private fun fetchLiveTrafficCameras() {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", AppUserAgent.value)
                        .build()
                )
            }
            .build()

        val retrofitTfl = Retrofit.Builder()
            .baseUrl("https://api.tfl.gov.uk")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val tflApi = retrofitTfl.create(TrafficCameraApi::class.java)

        val retrofitNh = Retrofit.Builder()
            .baseUrl("https://webtris.nationalhighways.co.uk")
            .client(
                client.newBuilder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Ocp-Apim-Subscription-Key", BuildConfig.TRAFFIC_API_KEY)
                                .build()
                        )
                    }
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val nhApi = retrofitNh.create(NationalHighwaysApi::class.java)

        lifecycleScope.launch(Dispatchers.IO) {
            // Fetch live CCTV cameras with valid visual feeds (e.g., TfL JamCams)
            try {
                val tflPlaces = tflApi.getLiveCameras()
                val tflEntities = tflPlaces.mapNotNull { place ->
                    if (place.lat == 0.0 && place.lon == 0.0) return@mapNotNull null
                    val imgUrl = place.additionalProperties?.firstOrNull {
                        it.key.equals("imageUrl", ignoreCase = true) || it.key.equals("file", ignoreCase = true)
                    }?.value

                    // Enforce that a valid visual CCTV image feed URL exists (excludes non-visual sensors)
                    if (imgUrl.isNullOrBlank()) return@mapNotNull null

                    CameraEntity(
                        id = "tfl_${place.commonName.hashCode()}_${place.lat}_${place.lon}",
                        lat = place.lat,
                        lon = place.lon,
                        title = place.commonName.ifBlank { "TfL Traffic CCTV Camera" },
                        operator = "Transport for London",
                        type = "Traffic Camera",
                        source = SOURCE_TRAFFIC,
                        tagsJson = CameraTags.encode(
                            buildMap {
                                put("liveImageUrl", imgUrl)
                                place.additionalProperties?.forEach { prop ->
                                    if (prop.key.isNotBlank() && prop.value.isNotBlank()) {
                                        put(prop.key, prop.value)
                                    }
                                }
                            }
                        ),
                        isTrafficCamera = true
                    )
                }

                if (tflEntities.isNotEmpty()) {
                    database.cameraDao().insertCameras(tflEntities)
                    withContext(Dispatchers.Main) {
                        refreshViewport(force = true)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch live traffic cameras", e)
            }

            // Fetch National Highways traffic feeds (e.g., M180, M4, M25, M6)
            try {
                val nhResponse = nhApi.getWebtrisSites()
                val nhEntities = nhResponse.sites.orEmpty().mapNotNull { site ->
                    if (site.latitude == 0.0 && site.longitude == 0.0) return@mapNotNull null
                    val title = site.description?.ifBlank { site.name } ?: site.name ?: "National Highways CCTV"
                    
                    // Drop any nodes that are just MIDAS or TMU sensors (non-camera traffic sensors)
                    val isNotSensor = !title.contains("MIDAS", ignoreCase = true) &&
                            !title.contains("TMU", ignoreCase = true) &&
                            !(site.name?.contains("MIDAS", ignoreCase = true) == true) &&
                            !(site.name?.contains("TMU", ignoreCase = true) == true) &&
                            !(site.description?.contains("MIDAS", ignoreCase = true) == true) &&
                            !(site.description?.contains("TMU", ignoreCase = true) == true)
                    if (!isNotSensor) return@mapNotNull null

                    val imageUrl = "https://cctv.trafficengland.com/feeds/cctv/${site.id}.jpg"
                    if (imageUrl.isBlank()) return@mapNotNull null

                    CameraEntity(
                        id = "nh_${site.id}",
                        lat = site.latitude,
                        lon = site.longitude,
                        title = title,
                        operator = "National Highways",
                        type = "Traffic Camera",
                        source = "National Highways",
                        tagsJson = CameraTags.encode(
                            mapOf(
                                "liveImageUrl" to imageUrl,
                                "siteId" to site.id,
                                "status" to (site.status ?: "Unknown")
                            )
                        ),
                        isTrafficCamera = true
                    )
                }

                if (nhEntities.isNotEmpty()) {
                    database.cameraDao().clearNationalHighwaysCameras()
                    database.cameraDao().clearSensorCameras()
                    database.cameraDao().insertCameras(nhEntities)
                    withContext(Dispatchers.Main) {
                        refreshViewport(force = true)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch National Highways traffic feeds", e)
            }
        }
    }

    override fun onPause() {
        if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation()
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
            withContext(Dispatchers.IO) {
                database.cameraDao().clearCouncilCameras()
                database.cameraDao().clearSensorCameras()
                database.cameraDao().insertCameras(councilCameras)
            }
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
     * Starts the first endpoint immediately and launches mirrors as hedges if the active request
     * takes longer than [HEDGE_DELAY_MS] or fails.
     *
     * Returns null when every endpoint failed, so callers can tell "no data" from "no answer".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fetchOverpassCameras(bounds: ViewportBounds): List<CameraEntity>? =
        coroutineScope {
            val query = OverpassQuery.build(bounds)
            Log.d(TAG, "Overpass query: $query")

            val channel = Channel<List<CameraEntity>?>(Channel.UNLIMITED)
            val jobs = mutableListOf<Job>()

            var index = 0
            var activeJobs = 0

            fun launchNext() {
                if (index >= OVERPASS_ENDPOINTS.size) return
                val endpoint = OVERPASS_ENDPOINTS[index++]
                activeJobs++
                jobs += launch(Dispatchers.IO) {
                    val result = requestCameras(endpoint, query)
                    channel.send(result)
                }
            }

            launchNext()

            var found: List<CameraEntity>? = null
            while (activeJobs > 0 && found == null) {
                val response = select<List<CameraEntity>?> {
                    channel.onReceive { it }
                    onTimeout(HEDGE_DELAY_MS) {
                        launchNext()
                        null
                    }
                }

                if (response != null) {
                    found = response
                } else {
                    activeJobs--
                    if (activeJobs == 0) {
                        launchNext()
                    }
                }
            }

            jobs.forEach { it.cancel() }
            found
        }

    /** Returns null if this endpoint failed, and rethrows cancellation so hedging stays prompt. */
    private suspend fun requestCameras(endpoint: String, query: String): List<CameraEntity>? {
        return try {
            val response = withContext(Dispatchers.IO) {
                runCatching { overpassApi.getCctvCamerasPost(endpoint, query) }
                    .getOrElse { overpassApi.getCctvCamerasGet(endpoint, query) }
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

        val typeStr = tags["surveillance:type"] ?: tags["camera:type"] ?: tags["highway"] ?: tags["man_made"] ?: UNKNOWN_TYPE
        val isTraffic = tags["highway"] == "speed_camera" ||
                tags["enforcement"] == "speed_camera" ||
                typeStr.contains("speed", ignoreCase = true) ||
                typeStr.contains("traffic", ignoreCase = true) ||
                tags["surveillance:type"]?.contains("traffic", ignoreCase = true) == true ||
                tags["camera:type"]?.contains("traffic", ignoreCase = true) == true ||
                tags["surveillance:kind"]?.contains("traffic", ignoreCase = true) == true

        return CameraEntity(
            id = "osm_$id",
            lat = latitude,
            lon = longitude,
            title = tags["name"] ?: DEFAULT_CAMERA_TITLE,
            operator = tags["operator"] ?: UNKNOWN_OPERATOR,
            type = typeStr,
            source = SOURCE_OVERPASS,
            tagsJson = CameraTags.encode(tags),
            isTrafficCamera = isTraffic,
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
        fovOverlay.items.clear()
        osmOverlay.items.clear()
        councilOverlay.items.clear()
        trafficOverlay.items.clear()

        val nodes = entities.map { entity ->
            val node = entity.toNode()
            val geoPoint = GeoPoint(node.lat, node.lon)

            // Render field-of-view (FOV) cone if camera direction is provided in tags
            parseDirection(node.tags)?.let { azimuth ->
                val fovPolygon = drawFovCone(geoPoint, azimuth)
                fovOverlay.add(fovPolygon)
            }

            node
        }

        renderCamerasToMap(nodes)

        renderedCount = entities.size
        updateCountView()
    }

    /**
     * Centralized routing function that creates osmdroid Markers, assigns the icon color
     * based on camera type/source, and routes each marker to the appropriate FolderOverlay.
     */
    private fun renderCamerasToMap(nodes: List<CctvNode>) {
        nodes.forEach { node ->
            Marker(mapView).apply {
                position = GeoPoint(node.lat, node.lon)
                title = node.titleStr
                snippet = if (node.operator.isNotBlank() || node.type.isNotBlank()) "${node.operator} - ${node.type}" else ""

                when {
                    node.isTrafficCamera -> {
                        icon = markerIcon(TRAFFIC_COLOR)
                        trafficOverlay.add(this)
                    }
                    node.isCouncil -> {
                        icon = markerIcon(COUNCIL_COLOR)
                        councilOverlay.add(this)
                    }
                    else -> {
                        icon = markerIcon(MARKER_COLOR)
                        osmOverlay.add(this)
                    }
                }

                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ ->
                    showCameraBottomSheet(node)
                    true
                }
            }
        }
        mapView.invalidate()
    }

    /** Extracts camera direction angle in degrees from tags if present. */
    private fun parseDirection(tags: Map<String, String>): Double? {
        val dirStr = tags["camera:direction"]
            ?: tags["direction"]
            ?: tags["surveillance:direction"]
            ?: tags["camera:heading"]
            ?: return null

        dirStr.toDoubleOrNull()?.let { return it }

        return when (dirStr.uppercase().trim()) {
            "N" -> 0.0
            "NNE" -> 22.5
            "NE" -> 45.0
            "ENE" -> 67.5
            "E" -> 90.0
            "ESE" -> 112.5
            "SE" -> 135.0
            "SSE" -> 157.5
            "S" -> 180.0
            "SSW" -> 202.5
            "SW" -> 225.0
            "WSW" -> 247.5
            "W" -> 270.0
            "WNW" -> 292.5
            "NW" -> 315.0
            "NNW" -> 337.5
            else -> null
        }
    }

    /** Projects a coordinate outward given distance in meters and bearing in degrees. */
    private fun destinationPoint(center: GeoPoint, distanceMeters: Double, bearingDegrees: Double): GeoPoint {
        val r = 6371000.0 // Earth radius in meters
        val distRad = distanceMeters / r
        val brngRad = Math.toRadians(bearingDegrees)
        val lat1Rad = Math.toRadians(center.latitude)
        val lon1Rad = Math.toRadians(center.longitude)

        val lat2Rad = Math.asin(
            Math.sin(lat1Rad) * Math.cos(distRad) +
            Math.cos(lat1Rad) * Math.sin(distRad) * Math.cos(brngRad)
        )
        val lon2Rad = lon1Rad + Math.atan2(
            Math.sin(brngRad) * Math.sin(distRad) * Math.cos(lat1Rad),
            Math.cos(distRad) - Math.sin(lat1Rad) * Math.sin(lat2Rad)
        )

        return GeoPoint(Math.toDegrees(lat2Rad), Math.toDegrees(lon2Rad))
    }

    /** Draws a semi-transparent Field-of-View (FOV) polygon cone onto the map. */
    private fun drawFovCone(
        center: GeoPoint,
        azimuthDegree: Double,
        distanceMeters: Double = 50.0,
        fovSpreadDegree: Double = 60.0,
    ): Polygon {
        val leftHeading = azimuthDegree - (fovSpreadDegree / 2.0)
        val rightHeading = azimuthDegree + (fovSpreadDegree / 2.0)

        val leftPoint = destinationPoint(center, distanceMeters, leftHeading)
        val rightPoint = destinationPoint(center, distanceMeters, rightHeading)

        return Polygon(mapView).apply {
            points = listOf(center, leftPoint, rightPoint, center)
            fillPaint.color = Color.argb(80, 0, 120, 255) // semi-transparent blue
            outlinePaint.color = Color.argb(120, 0, 120, 255)
            outlinePaint.strokeWidth = 1.5f
        }
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

    private fun showCameraBottomSheet(node: CctvNode) {
        val bottomSheetDialog = BottomSheetDialog(this)
        
        if (node.isTrafficCamera) {
            val view = android.view.LayoutInflater.from(this).inflate(R.layout.bottom_sheet_camera, null)
            val tvLocation = view.findViewById<TextView>(R.id.tvCameraLocation)
            val ivFeed = view.findViewById<ImageView>(R.id.ivCameraFeed)
            val tvLiveIndicator = view.findViewById<TextView>(R.id.tvLiveIndicator)

            tvLocation.text = node.titleStr

            if (!node.imageUrl.isNullOrBlank()) {
                // Fix missing protocols from the API response
                val fixedUrl = if (node.imageUrl.startsWith("//")) {
                    "https:${node.imageUrl}"
                } else {
                    node.imageUrl
                }

                val request = ImageRequest.Builder(this)
                    .data(fixedUrl)
                    .crossfade(true)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Ocp-Apim-Subscription-Key", BuildConfig.TRAFFIC_API_KEY)
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .error(android.R.drawable.ic_delete)
                    .target(ivFeed)
                    .build()

                ivFeed.context.imageLoader.enqueue(request)
                tvLiveIndicator.visibility = View.VISIBLE
            } else {
                ivFeed.setImageResource(android.R.drawable.ic_menu_camera)
                tvLiveIndicator.visibility = View.GONE
            }
            bottomSheetDialog.setContentView(view)
        } else {
            val view = android.view.LayoutInflater.from(this).inflate(R.layout.bottom_sheet_camera_detail, null)
            val tvSource = view.findViewById<TextView>(R.id.tvCameraSource)
            val tvTitle = view.findViewById<TextView>(R.id.tvCameraTitle)
            val tvOperator = view.findViewById<TextView>(R.id.tvOperator)
            val tvType = view.findViewById<TextView>(R.id.tvType)
            val tvCoordinates = view.findViewById<TextView>(R.id.tvCoordinates)
            val tagContainer = view.findViewById<LinearLayout>(R.id.tagContainer)
            val btnVerifyImagery = view.findViewById<MaterialButton>(R.id.btnVerifyImagery)

            tvSource.text = node.source
            tvTitle.text = node.titleStr
            tvOperator.text = getString(R.string.camera_operator, node.operator)
            tvType.text = getString(R.string.camera_type, node.type)
            tvCoordinates.text = getString(R.string.camera_coordinates, node.lat, node.lon)

            tagContainer.removeAllViews()
            if (node.tags.isNotEmpty()) {
                node.tags.forEach { (key, value) ->
                    val tagView = TextView(this).apply {
                        text = getString(R.string.attribute_row, key, value)
                        textSize = 14f
                        setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
                        setPadding(0, 4, 0, 4)
                    }
                    tagContainer.addView(tagView)
                }
            } else {
                view.findViewById<TextView>(R.id.tvAllTags).visibility = View.GONE
            }

            btnVerifyImagery.setOnClickListener {
                // Open street imagery map or street view based on lat/lon
                val uri = Uri.parse("google.streetview:cbll=${node.lat},${node.lon}")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage("com.google.android.apps.maps")
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    // Fallback to browser
                    val browserUri = Uri.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=${node.lat},${node.lon}")
                    startActivity(Intent(Intent.ACTION_VIEW, browserUri))
                }
            }
            bottomSheetDialog.setContentView(view)
        }

        bottomSheetDialog.show()
    }

    /** Explains the marker colours, since they now come from different sources. */
    private fun showLegend() {
        val dot = "\u25CF"
        val osmLabel = getString(R.string.legend_osm)
        val councilLabel = getString(R.string.legend_council)
        val trafficLabel = getString(R.string.legend_traffic)
        val legend = SpannableString("$dot $osmLabel   $dot $councilLabel   $dot $trafficLabel")
        val councilDot = legend.indexOf(dot, 1)
        val trafficDot = legend.indexOf(dot, councilDot + 1)
        legend.setSpan(ForegroundColorSpan(MARKER_COLOR), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(
            ForegroundColorSpan(COUNCIL_COLOR),
            councilDot,
            councilDot + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        legend.setSpan(
            ForegroundColorSpan(TRAFFIC_COLOR),
            trafficDot,
            trafficDot + 1,
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
        north = maxOf(latNorth, latSouth),
        east = maxOf(lonEast, lonWest),
        south = minOf(latNorth, latSouth),
        west = minOf(lonEast, lonWest),
    )

    private companion object {
        const val TAG = "MapsActivity"
        const val SOURCE_OVERPASS = "OVERPASS"
        const val SOURCE_TRAFFIC = "TRAFFIC"
        const val DEFAULT_CAMERA_TITLE = "CCTV Camera"
        const val UNKNOWN_OPERATOR = "Unknown Operator"
        const val UNKNOWN_TYPE = "Unknown Type"
        const val OVERPASS_BASE_URL = "https://overpass-api.de/"
        /**
         * Reliable global Overpass instances. They are independent servers, which is why a mirror
         * can succeed while another 500s or 504s.
         */
        val OVERPASS_ENDPOINTS = listOf(
            "https://lz4.overpass-api.de/api/interpreter",
            "https://z.overpass-api.de/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        const val MARKER_SIZE_DP = 16
        const val MARKER_STROKE_DP = 2
        const val VIEWPORT_DEBOUNCE_MS = 1500L

        /** How long the primary endpoint gets before a mirror is asked in parallel. */
        const val HEDGE_DELAY_MS = 2_500L
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
        /** Red for live traffic cameras. */
        val TRAFFIC_COLOR: Int = "#D32F2F".toColorInt()
    }
}
