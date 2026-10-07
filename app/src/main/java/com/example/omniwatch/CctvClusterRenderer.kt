package com.example.omniwatch

import android.content.Context
import android.graphics.Color
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.MarkerOptions
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer

class CctvClusterRenderer(
    context: Context,
    map: GoogleMap,
    clusterManager: ClusterManager<CctvClusterItem>
) : DefaultClusterRenderer<CctvClusterItem>(context, map, clusterManager) {

    // Style individual markers before they appear on the map
    override fun onBeforeClusterItemRendered(
        item: CctvClusterItem,
        markerOptions: MarkerOptions
    ) {
        val markerColor = if (item.source == "COUNCIL") {
            BitmapDescriptorFactory.HUE_AZURE // Blue for official council cameras
        } else {
            BitmapDescriptorFactory.HUE_ORANGE // Amber for OpenStreetMap nodes
        }
        
        markerOptions.icon(BitmapDescriptorFactory.defaultMarker(markerColor))
            .title(item.title)
            .snippet(item.snippet)
    }

    // Style the grouped cluster bubble color (e.g., tactical dark blue)
    override fun getColor(clusterSize: Int): Int {
        return Color.parseColor("#1E3A8A") // Replace with your preferred hex color
    }
}
