package com.example.omniwatch.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.omniwatch.CctvClusterItem
import com.google.android.gms.maps.model.LatLng

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey
    val id: String,
    val lat: Double,
    val lon: Double,
    val title: String,
    val operator: String,
    val type: String,
    val source: String // "OVERPASS" or "COUNCIL"
) {
    fun toClusterItem(): CctvClusterItem {
        return CctvClusterItem(
            position = LatLng(lat, lon),
            titleStr = title,
            source = source,
            operator = operator,
            type = type
        )
    }
}
