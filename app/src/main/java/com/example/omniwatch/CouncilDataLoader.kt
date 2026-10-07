package com.example.omniwatch

import android.content.Context
import android.util.Log
import com.example.omniwatch.data.db.CameraEntity
import com.example.omniwatch.data.db.CameraTags
import com.google.gson.Gson

data class GeoJsonFeatureCollection(
    // Nullable throughout: a hand-trimmed or partly malformed export should degrade to
    // "fewer cameras", never to a crash.
    val features: List<GeoJsonFeature>? = null,
)

data class GeoJsonFeature(
    val geometry: GeoJsonGeometry? = null,
    val properties: Map<String, Any?>? = null,
)

data class GeoJsonGeometry(
    val type: String? = null,
    // Deliberately untyped: real exports also carry line and polygon geometries, and typed as
    // List<Double> a single one of those would fail the whole document instead of one feature.
    val coordinates: List<Any?>? = null,
)

/**
 * Reads a council CCTV dataset bundled in `assets/` and converts it into [CameraEntity] rows.
 *
 * Council open-data exports are not standardised: the same facts arrive under `id`/`camera_id`/
 * `fid`, under `location`/`name`/`site`, and so on. Rather than hardcode one schema, the loader
 * looks each fact up through a list of the field names councils actually publish, keeps every
 * property it finds, and falls back to sensible defaults for the rest.
 */
object CouncilDataLoader {

    /** Marks rows that came from a bundled council dataset rather than the live Overpass feed. */
    const val SOURCE_COUNCIL = "COUNCIL"

    private const val TAG = "CouncilDataLoader"
    private const val DEFAULT_ASSET = "council_cctv.geojson"
    private const val DEFAULT_TITLE = "Council CCTV Camera"
    private const val DEFAULT_OPERATOR = "Council"
    private const val DEFAULT_TYPE = "Surveillance"

    /** Candidate field names, most specific first. */
    private val ID_KEYS = listOf(
        "id", "ID", "camera_id", "cameraId", "asset_id", "reference", "ref",
        "site_id", "fid", "objectid", "OBJECTID",
    )
    private val TITLE_KEYS = listOf(
        "name", "Name", "title", "location", "Location", "site_name", "site", "Site",
        "address", "description",
    )
    private val OPERATOR_KEYS = listOf(
        "operator", "Operator", "organisation", "organization", "authority", "owner",
        "council", "operator_name",
    )
    private val TYPE_KEYS = listOf(
        "type", "Type", "camera_type", "cameraType", "equipment_type", "category",
        "Category",
    )

    /**
     * Reads [fileName] from the assets folder and maps its point features to cameras.
     * Returns an empty list if the asset is missing or unreadable, so the live Overpass
     * cameras still render.
     */
    fun loadCouncilCctvFromAssets(
        context: Context,
        fileName: String = DEFAULT_ASSET,
    ): List<CameraEntity> {
        return try {
            context.assets.open(fileName).use { inputStream ->
                val cameras = parseJson(inputStream.reader().readText())
                Log.i(TAG, "Loaded ${cameras.size} council cameras from $fileName")
                cameras
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read council dataset $fileName", e)
            emptyList()
        }
    }

    /**
     * Maps a council GeoJSON FeatureCollection to cameras. Kept apart from the asset read so the
     * field-name matching and coordinate validation can be unit tested on the JVM.
     */
    fun parseJson(geoJson: String): List<CameraEntity> {
        val collection = Gson().fromJson(geoJson, GeoJsonFeatureCollection::class.java)
        return collection?.features.orEmpty().mapNotNull { it.toCameraEntity() }
    }

    /** Reads a GeoJSON point as latitude to longitude, or null for any other geometry. */
    private fun GeoJsonGeometry.toPoint(): Pair<Double, Double>? {
        if (!type.equals("Point", ignoreCase = true)) return null

        val values = coordinates ?: return null
        if (values.size < 2) return null

        // GeoJSON stores coordinates as [longitude, latitude]; other geometry types nest them.
        val longitude = (values[0] as? Number)?.toDouble() ?: return null
        val latitude = (values[1] as? Number)?.toDouble() ?: return null
        return latitude to longitude
    }

    private fun GeoJsonFeature.toCameraEntity(): CameraEntity? {
        val (latitude, longitude) = geometry?.toPoint() ?: return null
        // Reject anything that cannot be a real coordinate rather than placing a stray marker.
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

        val attributes = properties.orEmpty()
            .mapValues { (_, value) -> value.toAttributeText() }
            .filterValues { it.isNotBlank() }

        return CameraEntity(
            id = attributes.firstMatching(ID_KEYS) ?: "council_${latitude}_$longitude",
            lat = latitude,
            lon = longitude,
            title = attributes.firstMatching(TITLE_KEYS) ?: DEFAULT_TITLE,
            operator = attributes.firstMatching(OPERATOR_KEYS) ?: DEFAULT_OPERATOR,
            type = attributes.firstMatching(TYPE_KEYS) ?: DEFAULT_TYPE,
            source = SOURCE_COUNCIL,
            // Every field the council published is kept so the detail sheet can show all of it.
            tagsJson = CameraTags.encode(attributes),
        )
    }

    private fun Map<String, String>.firstMatching(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it.isNotBlank() } }

    /** Numbers arrive as doubles from Gson; render whole ones without a trailing ".0". */
    private fun Any?.toAttributeText(): String = when (this) {
        null -> ""
        is Double -> if (this == toLong().toDouble()) toLong().toString() else toString()
        else -> toString()
    }
}
