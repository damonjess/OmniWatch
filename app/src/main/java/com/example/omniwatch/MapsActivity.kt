package com.example.omniwatch

import android.Manifest
import android.app.Activity
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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.omniwatch.data.db.AppDatabase
import com.example.omniwatch.data.db.CameraEntity
import com.example.omniwatch.data.db.CameraTags
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
    private lateinit var highwayOverlay: FolderOverlay
    private lateinit var walesOverlay: FolderOverlay
    private lateinit var niOverlay: FolderOverlay
    private lateinit var essexOverlay: FolderOverlay
    private lateinit var trafficVisionOverlay: FolderOverlay
    private lateinit var webcamOverlay: FolderOverlay
    private lateinit var insecamOverlay: FolderOverlay
    private lateinit var issOverlay: FolderOverlay
    private lateinit var cameraCountView: TextView
    private lateinit var legendView: TextView
    private lateinit var database: AppDatabase

    /** The bundled council dataset is read from assets once per process. */
    private var councilLoaded = false
    private var webcamLoaded = false
    private var trafficVisionLoaded = false
    private var activeWebcamPlayer: ExoPlayer? = null
    private var activeInsecamView: InsecamMjpegView? = null
    private val selectedCameraFilters = linkedSetOf<String>()

    /** The station's last known position, the marker that follows it, and the job that polls it. */
    private var issPosition: IssPosition? = null
    private var issMarker: Marker? = null
    private var issTrackingJob: Job? = null

    /** Country under the station, shown in the tracker sheet's "OVER" row. */
    private var issRegion: String? = null

    /** Non-null only while the tracker sheet is open, so its telemetry is updated on screen. */
    private var issSheet: IssSheetViews? = null
    private var issDialog: BottomSheetDialog? = null

    /** When "OVER" was last refreshed; a country changes slowly and needs no polling. */
    private var lastIssRegionAt = 0L

    private val issApi: IssApi by lazy { buildIssApi() }

    private var fetchJob: Job? = null
    private var lastFetchedBbox: ViewportBounds? = null
    private var lastFetchAt = 0L

    /**
     * Mirror that last answered, tried first on the next viewport. Neutral networks mostly reach
     * the first endpoint anyway; where one host is blocked the app stops starting there.
     */
    private var preferredOverpassEndpoint: String? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshViewport() }

    private val overpassApi: OverpassApi by lazy { buildOverpassApi() }

    private var renderedCount = 0
    private var isUpdating = false
    private var fetchFailed = false
    private var failedAttempts = 0

    /** Why the last fetch failed, shown next to the count so a stall is never a dead end. */
    private var lastFailure: String? = null

    private val publicWebcamsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val lat = data.getDoubleExtra(PublicWebcamsActivity.EXTRA_LAT, 0.0)
            val lon = data.getDoubleExtra(PublicWebcamsActivity.EXTRA_LON, 0.0)
            if (lat != 0.0 || lon != 0.0) {
                mapView.controller.animateTo(GeoPoint(lat, lon), 15.0, 1000L)
                mapView.post { refreshViewport(force = true) }
            }
            openSelectedWebcam(data.getStringExtra(PublicWebcamsActivity.EXTRA_ID))
        }
    }

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
        highwayOverlay = FolderOverlay()
        walesOverlay = FolderOverlay()
        niOverlay = FolderOverlay()
        essexOverlay = FolderOverlay()
        trafficVisionOverlay = FolderOverlay()
        webcamOverlay = FolderOverlay()
        insecamOverlay = FolderOverlay()
        issOverlay = FolderOverlay()
        selectedCameraFilters.addAll(loadCameraFilters())
        mapView.overlays.add(fovOverlay)
        mapView.overlays.add(osmOverlay)
        mapView.overlays.add(councilOverlay)
        mapView.overlays.add(trafficOverlay)
        mapView.overlays.add(highwayOverlay)
        mapView.overlays.add(walesOverlay)
        mapView.overlays.add(niOverlay)
        mapView.overlays.add(essexOverlay)
        mapView.overlays.add(trafficVisionOverlay)
        mapView.overlays.add(webcamOverlay)
        mapView.overlays.add(insecamOverlay)
        mapView.overlays.add(issOverlay)
        applyCameraFilters()

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

        val btnPublicWebcams = findViewById<MaterialButton>(R.id.btnPublicWebcams)
        btnPublicWebcams?.setOnClickListener {
            publicWebcamsLauncher.launch(Intent(this, PublicWebcamsActivity::class.java))
        }

        val btnIss = findViewById<MaterialButton>(R.id.btnIss)
        btnIss?.setOnClickListener { centreOnIssAndOpenSheet() }

        val btnCameraFilters = findViewById<MaterialButton>(R.id.btnCameraFilters)
        btnCameraFilters?.setOnClickListener { showCameraFiltersDialog() }

        // The bounding box is only meaningful once the view has been laid out.
        mapView.post { refreshViewport(force = true) }

        fetchLiveTrafficCameras()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        if (::locationOverlay.isInitialized) locationOverlay.enableMyLocation()
        if (::issOverlay.isInitialized) startIssTracking()
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
            .baseUrl("https://openhighways.uk/")
            .client(client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val nhApi = retrofitNh.create(NationalHighwaysApi::class.java)

        lifecycleScope.launch(Dispatchers.IO) {
            // Clean up legacy sensor entries before loading the current traffic sources.
            database.cameraDao().clearSensorCameras()

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

            // Load public National Highways and regional cameras from the catalogue.
            try {
                val nhEntities = nhApi.getCameras().mapNotNull { camera ->
                    if (!camera.active || camera.latitude == 0.0 && camera.longitude == 0.0) return@mapNotNull null
                    val source = when (camera.source?.lowercase()) {
                        "national_highways" -> SOURCE_NATIONAL_HIGHWAYS
                        "traffic_wales" -> SOURCE_TRAFFIC_WALES
                        "northern_ireland" -> SOURCE_TRAFFICWATCH_NI
                        "essex" -> SOURCE_ESSEX_HIGHWAYS
                        else -> return@mapNotNull null
                    }
                    val imageUrl = camera.imageUrl?.let { rawUrl ->
                        when {
                            rawUrl.startsWith("https://") -> rawUrl
                            rawUrl.startsWith("/") -> "https://openhighways.uk$rawUrl"
                            else -> "https://openhighways.uk/$rawUrl"
                        }
                    }
                        ?: return@mapNotNull null
                    val title = camera.name?.takeIf { it.isNotBlank() }
                        ?: listOfNotNull(camera.road, camera.direction).joinToString(" ").ifBlank { "$source CCTV" }
                    CameraEntity(
                        id = "${source}_${camera.internalId ?: camera.id ?: return@mapNotNull null}",
                        lat = camera.latitude,
                        lon = camera.longitude,
                        title = title,
                        operator = source,
                        type = "Traffic Camera",
                        source = source,
                        tagsJson = CameraTags.encode(
                            mapOf(
                                "liveImageUrl" to imageUrl,
                                "siteId" to (camera.internalId ?: camera.id.toString()),
                                "road" to (camera.road ?: ""),
                                "direction" to (camera.direction ?: ""),
                            )
                        ),
                        isTrafficCamera = true,
                    )
                }
                if (nhEntities.isNotEmpty()) {
                    database.cameraDao().clearNationalHighwaysCameras()
                    database.cameraDao().clearRegionalTrafficCameras()
                    database.cameraDao().insertCameras(nhEntities)
                    withContext(Dispatchers.Main) { refreshViewport(force = true) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch National Highways traffic feeds", e)
            }
        }
    }

    override fun onPause() {
        stopIssTracking()
        if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation()
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacksAndMessages(null)
        fetchJob?.cancel()
        issTrackingJob?.cancel()
        activeWebcamPlayer?.release()
        activeWebcamPlayer = null
        activeInsecamView?.stop()
        activeInsecamView = null
        super.onDestroy()
    }

    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        val basePath = File(filesDir, "osmdroid")
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = File(basePath, "tiles")
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

    private fun refreshViewport(force: Boolean = false) {
        if (!::mapView.isInitialized) return
        val viewport = mapView.boundingBox.toViewportBounds() ?: return
        // Only the Overpass request is limited to a bounded area, because it queries a shared
        // public API. The local camera table is cheap to query, so it is asked for the whole
        // viewport: clamping the render bounds hid every camera outside the central slice, which
        // kept most of the 5,717-camera TrafficVision catalogue off the map.
        val queryBounds = viewport.clampedTo(MAX_QUERY_SPAN_DEGREES) ?: return

        if (!force && queryBounds.coversSameAreaAs(lastFetchedBbox, BBOX_EPSILON)) return

        val elapsed = SystemClock.elapsedRealtime() - lastFetchAt
        if (!force && elapsed < MIN_FETCH_INTERVAL_MS) {
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, MIN_FETCH_INTERVAL_MS - elapsed)
            return
        }

        lastFetchAt = SystemClock.elapsedRealtime()
        fetchJob?.cancel()
        setFetching(true)
        fetchJob = lifecycleScope.launch {
            ensureCouncilData()
            ensurePublicWebcamData()
            ensureTrafficVisionData()
            renderViewport(viewport)

            // Cleared first so the header never shows a reason left over from an earlier viewport.
            lastFailure = null
            val live = fetchOverpassCameras(queryBounds)
            if (live != null) {
                failedAttempts = 0
                if (live.isNotEmpty()) {
                    withContext(Dispatchers.IO) { database.cameraDao().insertCameras(live) }
                }
                lastFetchedBbox = queryBounds
            }

            if (isActive) {
                fetchFailed = live == null
                if (fetchFailed) scheduleRetry()
                renderViewport(viewport)
                setFetching(false)
            }
        }
    }

    private fun scheduleRetry() {
        if (failedAttempts >= MAX_RETRY_ATTEMPTS) return
        val delay = (RETRY_BASE_DELAY_MS shl failedAttempts).coerceAtMost(MAX_RETRY_DELAY_MS)
        failedAttempts++
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, delay)
    }

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

    private suspend fun ensurePublicWebcamData() {
        if (webcamLoaded) return
        val webcams = withContext(Dispatchers.IO) {
            PublicWebcamDataLoader.loadFromAssets(this@MapsActivity)
        }
        if (webcams.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                database.cameraDao().clearPublicWebcams()
                database.cameraDao().insertCameras(webcams)
            }
        }
        webcamLoaded = true
    }

    private suspend fun ensureTrafficVisionData() {
        if (trafficVisionLoaded) return
        val cameras = withContext(Dispatchers.IO) {
            TrafficVisionDataLoader.loadFromAssets(this@MapsActivity)
        }
        if (cameras.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                database.cameraDao().clearTrafficVisionCameras()
                database.cameraDao().insertCameras(cameras)
            }
        }
        trafficVisionLoaded = true
    }

    private suspend fun renderViewport(bounds: ViewportBounds) {
        val cameras = withContext(Dispatchers.IO) {
            database.cameraDao().getCamerasIn(bounds.south, bounds.north, bounds.west, bounds.east)
        }
        showMarkers(cameras)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fetchOverpassCameras(bounds: ViewportBounds): List<CameraEntity>? =
        coroutineScope {
            val query = OverpassQuery.build(bounds)
            Log.d(TAG, "Overpass query: $query")

            // Each reply carries the mirror that produced it, so the next viewport can start with
            // the endpoint that is actually reachable on this network.
            val channel = Channel<Pair<String, List<CameraEntity>?>>(Channel.UNLIMITED)
            val jobs = mutableListOf<Job>()
            val endpoints = OverpassEndpoints.ordered(preferredOverpassEndpoint)

            var index = 0
            var activeJobs = 0

            fun launchNext() {
                if (index >= endpoints.size) return
                val endpoint = endpoints[index++]
                activeJobs++
                jobs += launch(Dispatchers.IO) {
                    val result = requestCameras(endpoint, query)
                    channel.send(endpoint to result)
                }
            }

            launchNext()

            var found: List<CameraEntity>? = null
            while (activeJobs > 0 && found == null) {
                val response = select<Pair<String, List<CameraEntity>?>?> {
                    channel.onReceive { it }
                    onTimeout(HEDGE_DELAY_MS) {
                        launchNext()
                        null
                    }
                }

                val cameras = response?.second
                if (cameras != null) {
                    found = cameras
                    preferredOverpassEndpoint = response.first
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

    private suspend fun requestCameras(endpoint: String, query: String): List<CameraEntity>? {
        return try {
            // POST is the documented method, but mirrors differ: some answer POST and fail GET,
            // others the reverse. Cancellation is rethrown rather than swallowed, so a cancelled
            // viewport fetch cannot keep the coroutine working through the GET fallback.
            val response = withContext(Dispatchers.IO) {
                try {
                    overpassApi.getCctvCamerasPost(endpoint, query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    overpassApi.getCctvCamerasGet(endpoint, query)
                }
            }

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

        val operatorStr = tags["operator"] ?: UNKNOWN_OPERATOR
        val typeStr = tags["surveillance:type"] ?: tags["camera:type"] ?: tags["highway"] ?: tags["man_made"] ?: UNKNOWN_TYPE

        val ref = tags["ref"] ?: tags["asset_ref"]
        var liveImgUrl: String? = null

        if ((operatorStr.contains("National Highways", ignoreCase = true) ||
             operatorStr.contains("Highways England", ignoreCase = true)) &&
             ref != null) {
            liveImgUrl = "https://public.highwaystrafficcameras.co.uk/cctvpublicaccess/images/${ref.padStart(5, '0')}.jpg"
        }

        val isTraffic = tags["highway"] == "speed_camera" ||
                tags["enforcement"] == "speed_camera" ||
                typeStr.contains("speed", ignoreCase = true) ||
                typeStr.contains("traffic", ignoreCase = true) ||
                tags["surveillance:type"]?.contains("traffic", ignoreCase = true) == true ||
                tags["camera:type"]?.contains("traffic", ignoreCase = true) == true ||
                tags["surveillance:kind"]?.contains("traffic", ignoreCase = true) == true ||
                liveImgUrl != null

        val mergedTags = tags.toMutableMap()
        if (liveImgUrl != null) {
            mergedTags["liveImageUrl"] = liveImgUrl
        }

        return CameraEntity(
            id = "osm_$id",
            lat = latitude,
            lon = longitude,
            title = tags["name"] ?: DEFAULT_CAMERA_TITLE,
            operator = operatorStr,
            type = typeStr,
            source = SOURCE_OVERPASS,
            tagsJson = CameraTags.encode(mergedTags),
            isTrafficCamera = isTraffic,
        )
    }

    private fun buildOverpassApi(): OverpassApi {
        val client = OkHttpClient.Builder()
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
        highwayOverlay.items.clear()
        walesOverlay.items.clear()
        niOverlay.items.clear()
        essexOverlay.items.clear()
        trafficVisionOverlay.items.clear()
        webcamOverlay.items.clear()
        insecamOverlay.items.clear()

        val nodes = entities.map { entity ->
            val node = entity.toNode()
            val geoPoint = GeoPoint(node.lat, node.lon)

            if (isCameraFilterSelected(node)) parseDirection(node.tags)?.let { azimuth ->
                val fovPolygon = drawFovCone(geoPoint, azimuth)
                fovOverlay.add(fovPolygon)
            }

            node
        }

        renderCamerasToMap(nodes)

        renderedCount = nodes.count(::isCameraFilterSelected)
        updateCountView()
        applyCameraFilters()
    }

    private fun showCameraFiltersDialog() {
        val options = cameraFilterOptions()
        val draftSelection = selectedCameraFilters.toMutableSet()
        val checked = options.map {
            if (it.key == FILTER_ALL) draftSelection.containsAll(CAMERA_FILTER_KEYS)
            else it.key in draftSelection
        }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_filter_title)
            .setMultiChoiceItems(options.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                val key = options[which].key
                if (key == FILTER_ALL) {
                    if (isChecked) draftSelection.addAll(CAMERA_FILTER_KEYS)
                    else draftSelection.clear()
                } else if (isChecked) {
                    draftSelection.add(key)
                } else {
                    draftSelection.remove(key)
                }
            }
            .setNegativeButton(R.string.camera_filter_cancel, null)
            .setPositiveButton(R.string.camera_filter_apply) { _, _ ->
                selectedCameraFilters.clear()
                selectedCameraFilters.addAll(draftSelection)
                saveCameraFilters()
                applyCameraFilters()
                renderCurrentViewport()
            }
            .setNeutralButton(R.string.camera_filter_clear_all) { _, _ ->
                selectedCameraFilters.clear()
                saveCameraFilters()
                applyCameraFilters()
                renderCurrentViewport()
            }
            .show()
    }

    private fun renderCurrentViewport() {
        if (!::mapView.isInitialized) return
        lifecycleScope.launch {
            val bounds = mapView.boundingBox.toViewportBounds() ?: return@launch
            renderViewport(bounds)
        }
    }

    private fun applyCameraFilters() {
        if (!::osmOverlay.isInitialized) return
        osmOverlay.isEnabled = FILTER_OSM in selectedCameraFilters
        councilOverlay.isEnabled = FILTER_COUNCIL in selectedCameraFilters
        trafficOverlay.isEnabled = FILTER_LONDON in selectedCameraFilters
        highwayOverlay.isEnabled = FILTER_HIGHWAYS in selectedCameraFilters
        walesOverlay.isEnabled = FILTER_WALES in selectedCameraFilters
        niOverlay.isEnabled = FILTER_NI in selectedCameraFilters
        essexOverlay.isEnabled = FILTER_ESSEX in selectedCameraFilters
        trafficVisionOverlay.isEnabled = FILTER_TRAFFICVISION in selectedCameraFilters
        webcamOverlay.isEnabled = FILTER_WEBCAMS in selectedCameraFilters
        insecamOverlay.isEnabled = FILTER_INSECAM in selectedCameraFilters
        issOverlay.isEnabled = FILTER_ISS in selectedCameraFilters
        fovOverlay.isEnabled = selectedCameraFilters.isNotEmpty()
        mapView.invalidate()
    }

    private fun isCameraFilterSelected(node: CctvNode): Boolean = when {
        node.source == SOURCE_INSECAM -> FILTER_INSECAM in selectedCameraFilters
        node.isWebcam -> FILTER_WEBCAMS in selectedCameraFilters
        node.source == SOURCE_NATIONAL_HIGHWAYS -> FILTER_HIGHWAYS in selectedCameraFilters
        node.source == SOURCE_TRAFFIC_WALES -> FILTER_WALES in selectedCameraFilters
        node.source == SOURCE_TRAFFICWATCH_NI -> FILTER_NI in selectedCameraFilters
        node.source == SOURCE_ESSEX_HIGHWAYS -> FILTER_ESSEX in selectedCameraFilters
        node.source == SOURCE_TRAFFICVISION -> FILTER_TRAFFICVISION in selectedCameraFilters
        node.source == SOURCE_TRAFFIC -> FILTER_LONDON in selectedCameraFilters
        node.isCouncil -> FILTER_COUNCIL in selectedCameraFilters
        else -> FILTER_OSM in selectedCameraFilters
    }

    private fun loadCameraFilters(): Set<String> {
        val stored = getSharedPreferences(FILTER_PREFS, MODE_PRIVATE)
            .getStringSet(FILTER_SELECTION, null)
        return stored?.intersect(CAMERA_FILTER_KEYS) ?: CAMERA_FILTER_KEYS
    }

    private fun saveCameraFilters() {
        getSharedPreferences(FILTER_PREFS, MODE_PRIVATE)
            .edit()
            .putStringSet(FILTER_SELECTION, selectedCameraFilters.toSet())
            .apply()
    }

    private data class CameraFilterOption(val key: String, val label: String)

    private fun cameraFilterOptions(): List<CameraFilterOption> = listOf(
        CameraFilterOption(FILTER_ALL, getString(R.string.camera_filter_all)),
        CameraFilterOption(FILTER_OSM, getString(R.string.camera_filter_osm)),
        CameraFilterOption(FILTER_COUNCIL, getString(R.string.camera_filter_council)),
        CameraFilterOption(FILTER_LONDON, getString(R.string.camera_filter_london)),
        CameraFilterOption(FILTER_HIGHWAYS, getString(R.string.camera_filter_highways)),
        CameraFilterOption(FILTER_WALES, getString(R.string.camera_filter_wales)),
        CameraFilterOption(FILTER_NI, getString(R.string.camera_filter_ni)),
        CameraFilterOption(FILTER_ESSEX, getString(R.string.camera_filter_essex)),
        CameraFilterOption(FILTER_TRAFFICVISION, getString(R.string.camera_filter_trafficvision)),
        CameraFilterOption(FILTER_WEBCAMS, getString(R.string.camera_filter_webcams)),
        CameraFilterOption(FILTER_INSECAM, getString(R.string.camera_filter_insecam)),
        CameraFilterOption(FILTER_ISS, getString(R.string.camera_filter_iss)),
    )

    private fun renderCamerasToMap(nodes: List<CctvNode>) {
        nodes.forEach { node ->
            Marker(mapView).apply {
                position = GeoPoint(node.lat, node.lon)
                title = node.titleStr
                snippet = if (node.operator.isNotBlank() || node.type.isNotBlank()) "${node.operator} - ${node.type}" else ""

                when {
                    node.source == SOURCE_INSECAM -> {
                        icon = markerIcon(INSECAM_COLOR)
                        insecamOverlay.add(this)
                    }
                    node.isWebcam -> {
                        icon = markerIcon(WEBCAM_COLOR)
                        webcamOverlay.add(this)
                    }
                    node.source == SOURCE_NATIONAL_HIGHWAYS -> {
                        icon = markerIcon(HIGHWAY_COLOR)
                        highwayOverlay.add(this)
                    }
                    node.source == SOURCE_TRAFFIC_WALES -> {
                        icon = markerIcon(WALES_COLOR)
                        walesOverlay.add(this)
                    }
                    node.source == SOURCE_TRAFFICWATCH_NI -> {
                        icon = markerIcon(NI_COLOR)
                        niOverlay.add(this)
                    }
                    node.source == SOURCE_ESSEX_HIGHWAYS -> {
                        icon = markerIcon(ESSEX_COLOR)
                        essexOverlay.add(this)
                    }
                    node.source == SOURCE_TRAFFICVISION -> {
                        icon = markerIcon(TRAFFICVISION_COLOR)
                        trafficVisionOverlay.add(this)
                    }
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

    private fun destinationPoint(center: GeoPoint, distanceMeters: Double, bearingDegrees: Double): GeoPoint {
        val r = 6371000.0
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
            fillPaint.color = Color.argb(80, 0, 120, 255)
            outlinePaint.color = Color.argb(120, 0, 120, 255)
            outlinePaint.strokeWidth = 1.5f
        }
    }

    private fun setFetching(fetching: Boolean) {
        isUpdating = fetching
        updateCountView()
    }

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

        when {
            node.source == SOURCE_INSECAM -> showInsecamMjpegSheet(bottomSheetDialog, node)
            canPlayDirectStream(node) -> showLiveWebcamSheet(bottomSheetDialog, node)
            shouldEmbedPlayer(node) && hasEmbeddableWebcamPage(node) ->
                showExternalWebcamSheet(bottomSheetDialog, node)
            !node.imageUrl.isNullOrBlank() -> showTrafficCameraSheet(bottomSheetDialog, node)
            hasEmbeddableWebcamPage(node) -> showExternalWebcamSheet(bottomSheetDialog, node)
            else -> showDetailSheet(bottomSheetDialog, node)
        }

        bottomSheetDialog.show()
    }

    /**
     * True when the entry should open an embedded player page rather than a still image. Traffic
     * camera entries that publish a player page or a YouTube id qualify even though they also
     * carry a thumbnail; a plain snapshot entry does not.
     */
    private fun shouldEmbedPlayer(node: CctvNode): Boolean =
        node.isWebcam || node.imageUrl.isNullOrBlank() || !node.tags["youtubeVideoId"].isNullOrBlank()

    /**
     * Shows an operator's embedded player page. Returns the inflated view so callers that add their
     * own content (the ISS tracker sheet) can reach it, and runs [onDismiss] once the WebView has
     * been torn down.
     */
    private fun showExternalWebcamSheet(
        bottomSheetDialog: BottomSheetDialog,
        node: CctvNode,
        onDismiss: (() -> Unit)? = null,
    ): View {
        val view = android.view.LayoutInflater.from(this).inflate(R.layout.bottom_sheet_webcam_external, null)
        val tvStatus = view.findViewById<TextView>(R.id.tvExternalWebcamStatus)
        val sourceLabel = if (node.source.isNotBlank()) node.source.uppercase() else "LIVE WEBCAM"
        view.findViewById<TextView>(R.id.tvExternalWebcamSource).text = "$sourceLabel • ${node.streamType ?: "WEB"}"
        view.findViewById<TextView>(R.id.tvExternalWebcamTitle).text = node.titleStr
        val webcamView = view.findViewById<WebView>(R.id.webcamWebView)
        webcamView.settings.javaScriptEnabled = true
        webcamView.settings.domStorageEnabled = true
        webcamView.settings.mediaPlaybackRequiresUserGesture = false
        webcamView.settings.loadWithOverviewMode = false
        webcamView.settings.useWideViewPort = false

        val hideClutterJs = """
            (function() {
                function cleanWebcamPage() {
                    try {
                        var meta = document.querySelector('meta[name="viewport"]');
                        if (!meta) {
                            meta = document.createElement('meta');
                            meta.name = 'viewport';
                            (document.head || document.documentElement).appendChild(meta);
                        }
                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                    } catch(e) {}

                    try {
                        var btns = document.querySelectorAll('.fc-cta-consent, .fc-primary-button, .fc-button, .qc-cmp2-summary-section button, button[mode="primary"], .qc-cmp2-btn[mode="primary"], #qc-cmp2-ui button, button[class*="consent"], button[class*="accept"]');
                        for (var i = 0; i < btns.length; i++) {
                            btns[i].click();
                        }
                    } catch(e) {}

                    var styleId = 'omniwatch-webcam-cleaner';
                    if (!document.getElementById(styleId)) {
                        var style = document.createElement('style');
                        style.id = styleId;
                        style.innerHTML = '.fc-consent-root, #fc-consent-root, .fc-dialog-overlay, .fc-dialog-container, div[class*="fc-"], div[id*="fc-"], ' +
                            '#qc-cmp2-container, .qc-cmp2-container, [id*="qc-cmp"], [class*="qc-cmp"], #qc-cmp2-ui, ' +
                            'iframe[title*="consent"], iframe[src*="consent"], iframe[src*="fundingchoices"], #onetrust-consent-sdk, .cc-window, ' +
                            'header, footer, nav, .header, .footer, .navbar, .breadcrumb, .adsbygoogle, .cam-vert, .wa, ' +
                            '.descr, #skw-wall, .sidebar, .comments, .skw-header, .skw-footer, .skw-nav, .skw-sidebar, ' +
                            'div[class*="ad-"], div[id*="ad-"], .social-share, .related-cams { ' +
                            'display: none !important; visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; } ' +
                            'html, body { background: #000000 !important; margin: 0 !important; padding: 0 !important; width: 100% !important; height: 100% !important; } ' +
                            '#skylinewebcams, #webcam, #live, .embed-responsive, video, ' +
                            'iframe[src*="youtube"], iframe[src*="twitch"], iframe[src*="player"], .player-container, .video-container { ' +
                            'display: block !important; width: 100% !important; height: 100% !important; min-height: 280px !important; max-width: 100% !important; max-height: 100% !important; ' +
                            'position: relative !important; top: 0 !important; left: 0 !important; margin: 0 auto !important; padding: 0 !important; border: none !important; object-fit: contain !important; z-index: 9999999 !important; }';
                        var targetHead = document.head || document.documentElement;
                        if (targetHead) {
                            targetHead.appendChild(style);
                        }
                    }

                    var v = document.querySelector('video');
                    if (v && v.paused) {
                        v.play().catch(function(e){});
                    }
                }

                cleanWebcamPage();
                if (!window.__omniwatchTimer) {
                    window.__omniwatchTimer = setInterval(cleanWebcamPage, 300);
                }
            })();
        """.trimIndent()

        webcamView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                tvStatus.text = getString(R.string.webcam_page_unavailable)
                tvStatus.visibility = View.VISIBLE
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: ""
                if (urlStr.contains("fundingchoicesmessages.google.com") ||
                    urlStr.contains("quantcast.com") ||
                    urlStr.contains("consensu.org") ||
                    urlStr.contains("cookie-script.com") ||
                    urlStr.contains("onetrust.com") ||
                    urlStr.contains("cookiebot.com") ||
                    urlStr.contains("cmp.quantcast.com") ||
                    urlStr.contains("fundingchoices")) {
                    return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tvStatus.visibility = View.GONE
                view?.evaluateJavascript(hideClutterJs, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(hideClutterJs, null)
            }
        }

        webcamView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress > 10) {
                    view?.evaluateJavascript(hideClutterJs, null)
                }
            }
        }

        val pageUrl = WebcamPages.embeddableUrl(node.streamUrl ?: node.websiteUrl)
        if (pageUrl != null) {
            // Every embedded page needs a Referer, and it must identify a real embedding origin.
            // The ISS stream is a YouTube live stream, and YouTube rejects an embed whose Referer
            // is a YouTube host with player error 152-4, so it keeps the ordinary webcam referer.
            val referer = when {
                pageUrl.contains("trafficvision", ignoreCase = true) ||
                    node.source == SOURCE_TRAFFICVISION -> TRAFFICVISION_REFERER
                else -> DEFAULT_WEBCAM_REFERER
            }
            val extraHeaders = mutableMapOf("Referer" to referer)
            webcamView.loadUrl(toEmbeddedWebcamUrl(pageUrl), extraHeaders)
        }
        bottomSheetDialog.setOnDismissListener {
            webcamView.stopLoading()
            webcamView.destroy()
            onDismiss?.invoke()
        }
        bottomSheetDialog.setContentView(view)
        return view
    }

    private fun showTrafficCameraSheet(bottomSheetDialog: BottomSheetDialog, node: CctvNode) {
        val view = android.view.LayoutInflater.from(this).inflate(R.layout.bottom_sheet_camera, null)
        val tvLocation = view.findViewById<TextView>(R.id.tvCameraLocation)
        val ivFeed = view.findViewById<ImageView>(R.id.ivCameraFeed)
        val tvLiveIndicator = view.findViewById<TextView>(R.id.tvLiveIndicator)

        tvLocation.text = node.titleStr

        val url = node.imageUrl.orEmpty()
        val secureUrl = when {
            url.startsWith("http://", ignoreCase = true) -> url.replace("http://", "https://", ignoreCase = true)
            url.startsWith("//") -> "https:$url"
            else -> url
        }

        fun buildRequest(refresh: Boolean): ImageRequest {
            val builder = ImageRequest.Builder(this)
                .data(secureUrl)
                .addHeader("User-Agent", BROWSER_USER_AGENT)
                .addHeader("Referer", TRAFFICVISION_REFERER)

            if (node.source == SOURCE_NATIONAL_HIGHWAYS || secureUrl.contains("amazonaws.com") || secureUrl.contains("highwaystrafficcameras.co.uk")) {
                builder.addHeader("Ocp-Apim-Subscription-Key", BuildConfig.TRAFFIC_API_KEY)
            }

            return if (refresh) {
                // A still-image camera keeps serving new frames, so the sheet re-fetches them
                // instead of showing whatever Coil cached when the marker was first tapped.
                builder.crossfade(false)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(ivFeed)
                    .build()
            } else {
                builder.crossfade(true)
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .error(android.R.drawable.ic_delete)
                    .target(ivFeed)
                    .build()
            }
        }

        ivFeed.context.imageLoader.enqueue(buildRequest(refresh = false))
        tvLiveIndicator.visibility = View.VISIBLE
        bottomSheetDialog.setContentView(view)

        // Snapshot-only cameras publish a single image URL, so the sheet polls it while it is open
        // to keep the view current rather than frozen on the first frame.
        if (secureUrl.isNotBlank()) {
            val refreshHandler = Handler(Looper.getMainLooper())
            val refresh = object : Runnable {
                override fun run() {
                    ivFeed.context.imageLoader.enqueue(buildRequest(refresh = true))
                    refreshHandler.postDelayed(this, SNAPSHOT_REFRESH_MS)
                }
            }
            refreshHandler.postDelayed(refresh, SNAPSHOT_REFRESH_MS)
            bottomSheetDialog.setOnDismissListener { refreshHandler.removeCallbacksAndMessages(null) }
        }
    }

    private fun showDetailSheet(bottomSheetDialog: BottomSheetDialog, node: CctvNode) {
        val view = android.view.LayoutInflater.from(this).inflate(R.layout.bottom_sheet_camera_detail, null)
        val tvSource = view.findViewById<TextView>(R.id.tvCameraSource)
        val tvTitle = view.findViewById<TextView>(R.id.tvCameraTitle)
        val tvOperator = view.findViewById<TextView>(R.id.tvOperator)
        val tvType = view.findViewById<TextView>(R.id.tvType)
        val tvCoordinates = view.findViewById<TextView>(R.id.tvCoordinates)
        val tagContainer = view.findViewById<LinearLayout>(R.id.tagContainer)
        val btnVerifyImagery = view.findViewById<MaterialButton>(R.id.btnVerifyImagery)

        tvSource.text = node.source.ifBlank { "TRAFFIC SENSOR" }
        tvTitle.text = node.titleStr
        tvOperator.text = getString(R.string.camera_operator, node.operator.ifBlank { "National Highways" })
        tvType.text = getString(R.string.camera_type, node.type.ifBlank { "MIDAS Traffic Sensor" })
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
            view.findViewById<TextView>(R.id.tvAllTags)?.visibility = View.GONE
        }

        btnVerifyImagery.setOnClickListener {
            val uri = Uri.parse("google.streetview:cbll=${node.lat},${node.lon}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                val browserUri = Uri.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=${node.lat},${node.lon}")
                startActivity(Intent(Intent.ACTION_VIEW, browserUri))
            }
        }
        bottomSheetDialog.setContentView(view)
    }

    /**
     * True when the entry points at a page a WebView can actually load. Photos tagged only with
     * something like `contact:webcam=CPE510` have no page, so they open the detail sheet instead
     * of a browser view that could only ever show an error.
     */
    private fun hasEmbeddableWebcamPage(node: CctvNode): Boolean =
        WebcamPages.embeddableUrl(node.streamUrl ?: node.websiteUrl) != null

    /**
     * True when the in-app player can open the feed without a browser page: a direct HLS or MP4
     * stream (TrafficVision video and hybrid cameras, HLS public webcams) or a Skyline page whose
     * signed playlist is resolved first.
     */
    private fun canPlayDirectStream(node: CctvNode): Boolean {
        val streamUrl = node.streamUrl ?: return false
        if (streamUrl.isBlank()) return false
        return CameraStreams.isDirectVideo(streamUrl, node.streamType) ||
            PublicWebcamStreams.isSkylinePage(streamUrl)
    }

    /** Resolves an Insecam viewer page and renders its multipart MJPEG feed natively. */
    private fun showInsecamMjpegSheet(bottomSheetDialog: BottomSheetDialog, node: CctvNode) {
        val view = android.view.LayoutInflater.from(this).inflate(R.layout.bottom_sheet_webcam, null)
        val tvLocation = view.findViewById<TextView>(R.id.tvWebcamLocation)
        val tvSource = view.findViewById<TextView>(R.id.tvWebcamSource)
        val playerView = view.findViewById<PlayerView>(R.id.webcamPlayerView)
        val mjpegView = view.findViewById<InsecamMjpegView>(R.id.insecamMjpegView)
        val tvStatus = view.findViewById<TextView>(R.id.tvWebcamStatus)

        tvLocation.text = node.titleStr
        tvSource.text = getString(R.string.insecam_source_live)
        tvStatus.text = getString(R.string.webcam_connecting)
        playerView.visibility = View.GONE
        mjpegView.visibility = View.VISIBLE

        activeInsecamView?.stop()
        activeInsecamView = mjpegView
        var dismissed = false
        bottomSheetDialog.setOnDismissListener {
            dismissed = true
            mjpegView.stop()
            if (activeInsecamView === mjpegView) activeInsecamView = null
        }
        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()

        lifecycleScope.launch {
            val streamUrl = node.streamUrl
                ?.let { InsecamStreams.resolveStreamUrl(it) }
            if (streamUrl.isNullOrBlank()) {
                if (!dismissed) tvStatus.text = getString(R.string.webcam_stream_unavailable)
                return@launch
            }
            if (dismissed || isFinishing || isDestroyed) return@launch
            mjpegView.start(streamUrl, object : InsecamMjpegView.Listener {
                override fun onConnected() {
                    if (!dismissed) tvStatus.text = getString(R.string.webcam_connecting)
                }

                override fun onFrame() {
                    if (!dismissed) tvStatus.text = getString(R.string.webcam_live, node.operator.ifBlank { node.titleStr })
                }

                override fun onError(error: Throwable) {
                    Log.w(TAG, "Insecam MJPEG playback failed for ${node.titleStr}", error)
                    if (!dismissed) tvStatus.text = getString(R.string.webcam_playback_failed)
                }
            })
        }
    }

    /**
     * Plays a webcam in the ExoPlayer sheet. Providers that hide their playlist behind a page
     * (SkylineWebcams) have it resolved first, so the sheet opens straight away in a connecting
     * state and the player is attached once the playlist URL is known.
     */
    private fun showLiveWebcamSheet(bottomSheetDialog: BottomSheetDialog, node: CctvNode) {
        val view = android.view.LayoutInflater.from(this).inflate(R.layout.bottom_sheet_webcam, null)
        val tvLocation = view.findViewById<TextView>(R.id.tvWebcamLocation)
        val tvSource = view.findViewById<TextView>(R.id.tvWebcamSource)
        val playerView = view.findViewById<PlayerView>(R.id.webcamPlayerView)
        val tvStatus = view.findViewById<TextView>(R.id.tvWebcamStatus)

        tvLocation.text = node.titleStr
        tvSource.text = when (node.source) {
            SOURCE_TRAFFICVISION -> getString(R.string.traffic_camera_source_live)
            SOURCE_INSECAM -> getString(R.string.insecam_source_live)
            else -> getString(R.string.webcam_source_live)
        }
        tvStatus.text = getString(R.string.webcam_connecting)

        activeWebcamPlayer?.release()
        activeWebcamPlayer = null

        var player: ExoPlayer? = null
        var dismissed = false
        var fellBackToSnapshot = false
        bottomSheetDialog.setOnDismissListener {
            dismissed = true
            player?.release()
            if (activeWebcamPlayer === player) activeWebcamPlayer = null
        }
        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()

        val directUrl = node.streamUrl
            ?.takeIf { CameraStreams.isDirectVideo(it, node.streamType) }
        lifecycleScope.launch {
            val playUrl = directUrl
                ?: node.streamUrl?.takeIf { PublicWebcamStreams.isSkylinePage(it) }?.let { PublicWebcamStreams.resolveLiveHlsUrl(it) }
                ?: node.streamUrl?.takeIf { node.source == SOURCE_INSECAM }?.let { InsecamStreams.resolveStreamUrl(it) }
            if (playUrl.isNullOrBlank()) {
                tvStatus.text = getString(R.string.webcam_stream_unavailable)
                return@launch
            }
            // The sheet may be closed while the playlist is still resolving; attaching a player to
            // a dismissed sheet would leak it because the dismiss listener has already run.
            if (dismissed || isFinishing || isDestroyed) return@launch

            val newPlayer = ExoPlayer.Builder(this@MapsActivity)
                .setMediaSourceFactory(mediaSourceFactory(node))
                .build()
            player = newPlayer
            activeWebcamPlayer = newPlayer
            playerView.player = newPlayer
            newPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        tvStatus.text = getString(
                            R.string.webcam_live,
                            node.operator.ifBlank { node.titleStr },
                        )
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Live playback failed for ${node.titleStr}", error)
                    // Video and hybrid cameras also publish a snapshot, so a stream that has gone
                    // offline falls back to the still frame instead of a dead player.
                    val snapshot = node.imageUrl
                    if (!fellBackToSnapshot && !dismissed && !snapshot.isNullOrBlank()) {
                        fellBackToSnapshot = true
                        tvStatus.text = getString(R.string.webcam_snapshot_fallback)
                        bottomSheetDialog.dismiss()
                        showSnapshotSheet(node)
                    } else {
                        tvStatus.text = getString(R.string.webcam_playback_failed)
                    }
                }
            })
            // Some cameras only expose the .m3u8 in the token query, so the HLS type is stated
            // explicitly rather than inferred from the URI. A progressive MP4 is left for the
            // player to infer from the file itself.
            val mediaItem = MediaItem.Builder().setUri(playUrl)
            val kind = CameraStreams.kind(playUrl, node.streamType)
            if (kind == CameraStreams.HLS) {
                mediaItem.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            // A progressive MP4 is a short agency clip, so it is looped to read as a live feed.
            if (kind == CameraStreams.MP4) {
                newPlayer.repeatMode = Player.REPEAT_MODE_ONE
            }
            newPlayer.setMediaItem(mediaItem.build())
            newPlayer.prepare()
            newPlayer.playWhenReady = true
        }
    }

    /** Opens the still-image sheet, used for snapshot cameras and as the video fallback. */
    private fun showSnapshotSheet(node: CctvNode) {
        if (isFinishing || isDestroyed) return
        val dialog = BottomSheetDialog(this)
        showTrafficCameraSheet(dialog, node)
        dialog.show()
    }

    /**
     * Follows the station while the screen is visible. The feed is polled rather than interpolated:
     * it is a free public API, one request every few seconds keeps the marker on the current orbit,
     * and tracking stops completely in onPause.
     */
    private fun startIssTracking() {
        if (issTrackingJob?.isActive == true) return
        issTrackingJob = lifecycleScope.launch {
            while (isActive) {
                val position = fetchIssPosition()
                if (position != null) {
                    issPosition = position
                    updateIssMarker(position)
                    issSheet?.let { updateIssTelemetry(it, position) }
                    refreshIssRegionIfNeeded(position)
                }
                delay(ISS_REFRESH_MS)
            }
        }
    }

    private fun stopIssTracking() {
        issTrackingJob?.cancel()
        issTrackingJob = null
    }

    private suspend fun fetchIssPosition(): IssPosition? = withContext(Dispatchers.IO) {
        try {
            issApi.getPosition()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ISS position lookup failed", e)
            null
        }
    }

    /**
     * Refreshes the country under the station for the "OVER" row. Only runs while the sheet is
     * open, and at most every [ISS_REGION_REFRESH_MS], because this is a second request against the
     * same public API and the answer changes slowly.
     */
    private suspend fun refreshIssRegionIfNeeded(position: IssPosition) {
        if (issSheet == null) return
        val elapsed = SystemClock.elapsedRealtime() - lastIssRegionAt
        if (lastIssRegionAt != 0L && elapsed < ISS_REGION_REFRESH_MS) return
        lastIssRegionAt = SystemClock.elapsedRealtime()

        val region = withContext(Dispatchers.IO) {
            try {
                IssTelemetry.formatRegion(
                    issApi.getRegion(position.latitude, position.longitude).countryCode
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Also the ordinary answer over open ocean, where there is no country to report.
                null
            }
        }

        issRegion = region
        issSheet?.let { updateIssTelemetry(it, position) }
    }

    /** Creates the station marker on first fix, then moves it on every later poll. */
    private fun updateIssMarker(position: IssPosition) {
        val marker = issMarker ?: Marker(mapView).apply {
            title = getString(R.string.iss_title)
            icon = markerIcon(ISS_COLOR, ISS_MARKER_SIZE_DP)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ ->
                showIssSheet()
                true
            }
            issOverlay.add(this)
        }
        marker.position = GeoPoint(position.latitude, position.longitude)
        issMarker = marker
        mapView.invalidate()
    }

    /** Centres the map on the station and opens its tracker sheet. */
    private fun centreOnIssAndOpenSheet() {
        val position = issPosition
        if (position == null) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.iss_unavailable),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        mapView.controller.animateTo(
            GeoPoint(position.latitude, position.longitude),
            ISS_ZOOM_LEVEL,
            ISS_CENTRE_ANIMATION_MS,
        )
        showIssSheet()
    }

    /**
     * Opens the ISS tracker sheet: NASA's live high-definition view of the station plus its live
     * telemetry, reusing the embedded-player sheet so the live stream behaves like any other feed.
     */
    private fun showIssSheet() {
        if (issDialog?.isShowing == true) return
        val position = issPosition ?: return

        val dialog = BottomSheetDialog(this)
        issDialog = dialog
        val view = showExternalWebcamSheet(dialog, issNode(position)) {
            if (issDialog === dialog) issDialog = null
            issSheet = null
        }

        val telemetry = IssSheetViews(
            over = view.findViewById(R.id.tvIssOver),
            lat = view.findViewById(R.id.tvIssLat),
            lng = view.findViewById(R.id.tvIssLng),
            alt = view.findViewById(R.id.tvIssAlt),
            vel = view.findViewById(R.id.tvIssVel),
            visibility = view.findViewById(R.id.tvIssVis),
        )
        view.findViewById<LinearLayout>(R.id.issTelemetry).visibility = View.VISIBLE
        issSheet = telemetry
        updateIssTelemetry(telemetry, position)
        dialog.show()
    }

    /**
     * The station as a map entry. It is deliberately not a row in the camera table: TrafficVision's
     * ISS record carries a frozen position, and a moving row would fight the viewport queries.
     */
    private fun issNode(position: IssPosition): CctvNode = CctvNode(
        lat = position.latitude,
        lon = position.longitude,
        titleStr = getString(R.string.iss_title),
        source = SOURCE_ISS,
        operator = "NASA",
        type = "Space station",
        // A YouTube live stream, which the embedded-player sheet already knows how to load.
        streamUrl = ISS_WATCH_URL,
        streamType = "LIVE YOUTUBE",
    )

    private fun updateIssTelemetry(views: IssSheetViews, position: IssPosition) {
        views.over.text = issRegion ?: getString(R.string.iss_value_pending)
        views.lat.text = IssTelemetry.formatCoordinate(position.latitude)
        views.lng.text = IssTelemetry.formatCoordinate(position.longitude)
        views.alt.text = IssTelemetry.formatAltitudeKm(position.altitude)
        views.vel.text = IssTelemetry.formatVelocityKmh(position.velocity)
        views.visibility.text = IssTelemetry.formatVisibility(position.visibility)
    }

    private fun buildIssApi(): IssApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", AppUserAgent.value)
                        .build()
                )
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(ISS_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IssApi::class.java)
    }

    /**
     * Builds the media source for a player sheet. TrafficVision's own HLS proxy rejects requests
     * that do not carry the site Referer (HTTP 403), so those headers are attached for its cameras
     * only; public webcams keep the player's default request headers.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun mediaSourceFactory(node: CctvNode): MediaSource.Factory {
        if (node.source != SOURCE_TRAFFICVISION) return DefaultMediaSourceFactory(this)
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(
            mapOf(
                "Referer" to TRAFFICVISION_REFERER,
                "User-Agent" to BROWSER_USER_AGENT,
            )
        )
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
    }

    /** Selecting a directory entry opens its feed straight away instead of a second marker tap. */
    private fun openSelectedWebcam(webcamId: String?) {
        if (webcamId.isNullOrBlank()) return
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) {
                PublicWebcamDataLoader.loadFromAssets(this@MapsActivity)
                    .firstOrNull { webcam -> webcam.id == webcamId }
            } ?: return@launch
            showCameraBottomSheet(entity.toNode())
        }
    }

    private fun toEmbeddedWebcamUrl(url: String): String {
        val youtubeMatch = Regex("(?:youtube\\.com/(?:watch\\?v=|live/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{6,})").find(url)
        if (youtubeMatch != null) {
            val videoId = youtubeMatch.groupValues[1]
            return "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1"
        }
        val twitchMatch = Regex("(?:twitch\\.tv/|player\\.twitch\\.tv/\\?channel=)([A-Za-z0-9_]{3,})").find(url)
        if (twitchMatch != null && !url.contains("player.twitch.tv")) {
            val channel = twitchMatch.groupValues[1]
            return "https://player.twitch.tv/?channel=$channel&parent=www.cmassets.co.uk&autoplay=true"
        }
        return url
    }

    private fun showLegend() {
        val dot = "\u25CF"
        val osmLabel = getString(R.string.legend_osm)
        val councilLabel = getString(R.string.legend_council)
        val londonLabel = getString(R.string.legend_london)
        val highwayLabel = getString(R.string.legend_highway)
        val walesLabel = getString(R.string.legend_wales)
        val niLabel = getString(R.string.legend_ni)
        val essexLabel = getString(R.string.legend_essex)
        val trafficVisionLabel = getString(R.string.legend_trafficvision)
        val webcamLabel = getString(R.string.legend_webcam)
        val insecamLabel = getString(R.string.legend_insecam)
        val issLabel = getString(R.string.legend_iss)
        val legend = SpannableString("$dot $osmLabel   $dot $councilLabel   $dot $londonLabel   $dot $highwayLabel   $dot $walesLabel   $dot $niLabel   $dot $essexLabel   $dot $trafficVisionLabel   $dot $webcamLabel   $dot $insecamLabel   $dot $issLabel")
        val councilDot = legend.indexOf(dot, 1)
        val londonDot = legend.indexOf(dot, councilDot + 1)
        val highwayDot = legend.indexOf(dot, londonDot + 1)
        val walesDot = legend.indexOf(dot, highwayDot + 1)
        val niDot = legend.indexOf(dot, walesDot + 1)
        val essexDot = legend.indexOf(dot, niDot + 1)
        val trafficVisionDot = legend.indexOf(dot, essexDot + 1)
        val webcamDot = legend.indexOf(dot, trafficVisionDot + 1)
        val insecamDot = legend.indexOf(dot, webcamDot + 1)
        val issDot = legend.indexOf(dot, insecamDot + 1)
        legend.setSpan(ForegroundColorSpan(MARKER_COLOR), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(
            ForegroundColorSpan(COUNCIL_COLOR),
            councilDot,
            councilDot + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        legend.setSpan(
            ForegroundColorSpan(TRAFFIC_COLOR),
            londonDot,
            londonDot + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        legend.setSpan(
            ForegroundColorSpan(HIGHWAY_COLOR),
            highwayDot,
            highwayDot + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        legend.setSpan(ForegroundColorSpan(WALES_COLOR), walesDot, walesDot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(ForegroundColorSpan(NI_COLOR), niDot, niDot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(ForegroundColorSpan(ESSEX_COLOR), essexDot, essexDot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(ForegroundColorSpan(TRAFFICVISION_COLOR), trafficVisionDot, trafficVisionDot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(ForegroundColorSpan(WEBCAM_COLOR), webcamDot, webcamDot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(ForegroundColorSpan(INSECAM_COLOR), insecamDot, insecamDot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legend.setSpan(ForegroundColorSpan(ISS_COLOR), issDot, issDot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        legendView.text = legend
    }

    private fun markerIcon(color: Int, sizeDp: Int = MARKER_SIZE_DP): Drawable {
        val density = resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
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

    /** The tracker sheet's value views, held so each poll updates them in place. */
    private class IssSheetViews(
        val over: TextView,
        val lat: TextView,
        val lng: TextView,
        val alt: TextView,
        val vel: TextView,
        val visibility: TextView,
    )

    private companion object {
        const val TAG = "MapsActivity"
        const val SNAPSHOT_REFRESH_MS = 7_000L
        const val TRAFFICVISION_REFERER = "https://trafficvision.live/"
        const val DEFAULT_WEBCAM_REFERER = "https://www.cmassets.co.uk/"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val SOURCE_OVERPASS = "OVERPASS"
        const val SOURCE_TRAFFIC = "TRAFFIC"
        const val SOURCE_NATIONAL_HIGHWAYS = "National Highways"
        const val SOURCE_TRAFFIC_WALES = "Traffic Wales"
        const val SOURCE_TRAFFICWATCH_NI = "TrafficWatchNI"
        const val SOURCE_ESSEX_HIGHWAYS = "Essex Highways"
        const val SOURCE_TRAFFICVISION = "TrafficVision"
        const val SOURCE_INSECAM = "INSECAM"
        const val SOURCE_ISS = "ISS"
        const val FILTER_PREFS = "camera_filter_preferences"
        const val FILTER_SELECTION = "selected_sources"
        const val FILTER_ALL = "ALL"
        const val FILTER_OSM = "OSM"
        const val FILTER_COUNCIL = "COUNCIL"
        const val FILTER_LONDON = "LONDON"
        const val FILTER_HIGHWAYS = "HIGHWAYS"
        const val FILTER_WALES = "WALES"
        const val FILTER_NI = "NI"
        const val FILTER_ESSEX = "ESSEX"
        const val FILTER_TRAFFICVISION = "TRAFFICVISION"
        const val FILTER_WEBCAMS = "WEBCAMS"
        const val FILTER_INSECAM = "INSECAM"
        const val FILTER_ISS = "ISS"
        val CAMERA_FILTER_KEYS = linkedSetOf(
            FILTER_OSM,
            FILTER_COUNCIL,
            FILTER_LONDON,
            FILTER_HIGHWAYS,
            FILTER_WALES,
            FILTER_NI,
            FILTER_ESSEX,
            FILTER_TRAFFICVISION,
            FILTER_WEBCAMS,
            FILTER_INSECAM,
            FILTER_ISS,
        )
        const val DEFAULT_CAMERA_TITLE = "CCTV Camera"
        const val UNKNOWN_OPERATOR = "Unknown Operator"
        const val UNKNOWN_TYPE = "Unknown Type"
        const val OVERPASS_BASE_URL = "https://overpass-api.de/"

        /** Live ISS tracking, the same open feed TrafficVision.Live's ISS view is built on. */
        const val ISS_BASE_URL = "https://api.wheretheiss.at/"

        /**
         * NASA's own live stream of the station, the feed the TrafficVision ISS entry embeds. The
         * standard-resolution stream is `M3HKLzjvKPc` should this one ever be taken down.
         */
        const val ISS_VIDEO_ID = "awQzjn72bI0"
        const val ISS_WATCH_URL = "https://www.youtube.com/watch?v=$ISS_VIDEO_ID"

        const val MARKER_SIZE_DP = 16
        const val MARKER_STROKE_DP = 2
        const val VIEWPORT_DEBOUNCE_MS = 1500L

        const val ISS_REFRESH_MS = 5_000L
        const val ISS_REGION_REFRESH_MS = 60_000L
        const val ISS_CENTRE_ANIMATION_MS = 1_000L
        const val ISS_ZOOM_LEVEL = 4.0
        const val ISS_MARKER_SIZE_DP = 22

        const val HEDGE_DELAY_MS = 5_000L
        const val MIN_FETCH_INTERVAL_MS = 3_000L

        const val RETRY_BASE_DELAY_MS = 5_000L
        const val MAX_RETRY_DELAY_MS = 60_000L
        const val MAX_RETRY_ATTEMPTS = 6
        const val MAX_QUERY_SPAN_DEGREES = 0.6
        const val BBOX_EPSILON = 1e-6
        val MARKER_COLOR: Int = "#FB8C00".toColorInt()
        val COUNCIL_COLOR: Int = "#1E88E5".toColorInt()
        val TRAFFIC_COLOR: Int = "#D32F2F".toColorInt()
        val HIGHWAY_COLOR: Int = "#7B1FA2".toColorInt()
        val WALES_COLOR: Int = "#00897B".toColorInt()
        val NI_COLOR: Int = "#388E3C".toColorInt()
        val ESSEX_COLOR: Int = "#C2185B".toColorInt()
        val TRAFFICVISION_COLOR: Int = "#AEEA00".toColorInt()
        val WEBCAM_COLOR: Int = "#00BCD4".toColorInt()
        val INSECAM_COLOR: Int = "#FF5722".toColorInt()
        val ISS_COLOR: Int = "#E040FB".toColorInt()
    }
}
