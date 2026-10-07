package com.example.omniwatch

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.omniwatch.data.db.AppDatabase
import com.example.omniwatch.data.db.CameraEntity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.maps.android.clustering.ClusterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var clusterManager: ClusterManager<CctvClusterItem>
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        database = AppDatabase.getDatabase(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Move camera to default location (Scunthorpe)
        val scunthorpe = LatLng(53.58, -0.65)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(scunthorpe, 12f))

        // Initialize Marker Clustering
        clusterManager = ClusterManager(this, mMap)

        // 1. Attach the custom color renderer
        val renderer = CctvClusterRenderer(this, mMap, clusterManager)
        clusterManager.renderer = renderer

        // 2. Set up the click listener for individual cameras
        clusterManager.setOnClusterItemClickListener { item ->
            showCameraBottomSheet(item)
            true // Return true to indicate the click event is handled
        }

        mMap.setOnCameraIdleListener(clusterManager)
        mMap.setOnMarkerClickListener(clusterManager)

        // Load cached camera data from Room DB for immediate offline display and fetch fresh data
        loadCachedDataAndFetchLive()
    }

    private fun showCameraBottomSheet(item: CctvClusterItem) {
        val bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog.setContentView(R.layout.bottom_sheet_camera_detail)

        // Find the views inside the bottom sheet layout
        val tvSource = bottomSheetDialog.findViewById<TextView>(R.id.tvCameraSource)
        val tvTitle = bottomSheetDialog.findViewById<TextView>(R.id.tvCameraTitle)
        val tvOperator = bottomSheetDialog.findViewById<TextView>(R.id.tvOperator)
        val tvType = bottomSheetDialog.findViewById<TextView>(R.id.tvType)
        val tvCoordinates = bottomSheetDialog.findViewById<TextView>(R.id.tvCoordinates)

        // Populate the views with the clicked camera's data
        tvSource?.text = item.source.uppercase()
        tvTitle?.text = item.titleStr
        tvOperator?.text = "Operator: ${item.operator}"
        tvType?.text = "Type: ${item.type}"
        tvCoordinates?.text = "Coordinates: ${item.position.latitude}, ${item.position.longitude}"

        bottomSheetDialog.show()
    }

    private fun loadCachedDataAndFetchLive() {
        lifecycleScope.launch {
            // Load local cached items first so app works offline
            val cachedEntities = withContext(Dispatchers.IO) {
                database.cameraDao().getAllCameras()
            }

            if (cachedEntities.isNotEmpty()) {
                clusterManager.clearItems()
                clusterManager.addItems(cachedEntities.map { it.toClusterItem() })
                clusterManager.cluster()
            }

            // Fetch live Overpass API data and merge with council local dataset
            fetchAndCacheCctvData()
        }
    }

    private suspend fun fetchAndCacheCctvData() {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://overpass-api.de")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(OverpassApi::class.java)
        val bboxQuery = "[out:json];node[\"man_made\"=\"surveillance\"](53.53,-0.75,53.63,-0.55);out;"

        try {
            val response = withContext(Dispatchers.IO) {
                api.getCctvCameras(bboxQuery)
            }

            // Map Overpass API nodes to CameraEntity
            val overpassEntities = response.elements.map { node ->
                val type = node.tags?.get("surveillance:type") ?: "Unknown Type"
                val operator = node.tags?.get("operator") ?: "Unknown Operator"
                CameraEntity(
                    id = "osm_${node.id}",
                    lat = node.lat,
                    lon = node.lon,
                    title = "CCTV Camera",
                    operator = operator,
                    type = type,
                    source = "OVERPASS"
                )
            }

            // Load Council Open Data GeoJSON from assets
            val councilEntities = CouncilDataLoader.loadCouncilCctvFromAssets(this@MapsActivity)

            val allEntities = overpassEntities + councilEntities

            // Save to Room DB for offline persistence
            withContext(Dispatchers.IO) {
                database.cameraDao().insertCameras(allEntities)
            }

            // Update cluster manager on main thread
            clusterManager.clearItems()
            clusterManager.addItems(allEntities.map { it.toClusterItem() })
            clusterManager.cluster()

        } catch (e: Exception) {
            e.printStackTrace()
            // If offline, Room DB cache loaded previously remains displayed
        }
    }
}
