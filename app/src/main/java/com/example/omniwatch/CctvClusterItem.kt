package com.example.omniwatch

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem

class CctvClusterItem(
    private val position: LatLng,
    val titleStr: String,
    val source: String, // "COUNCIL" or "OVERPASS"
    val operator: String,
    val type: String,
) : ClusterItem {
    override fun getPosition(): LatLng = position
    override fun getTitle(): String = titleStr
    override fun getSnippet(): String = "$operator - $type"
    override fun getZIndex(): Float? = null
}