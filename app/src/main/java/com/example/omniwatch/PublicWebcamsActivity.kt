package com.example.omniwatch

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.omniwatch.data.db.CameraEntity

/** Directory of curated public webcams; selecting one returns its map location. */
class PublicWebcamsActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var webcams: List<CameraEntity>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.public_webcams)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 12)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(20, systemBars.top + 18, 20, 12)
            insets
        }
        val search = SearchView(this).apply {
            queryHint = getString(R.string.search_webcams_hint)
            isIconifiedByDefault = false
        }
        val subtitle = TextView(this).apply {
            text = getString(R.string.webcam_directory_subtitle)
            textSize = 13f
            setPadding(4, 6, 4, 12)
        }
        listView = ListView(this)
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        webcams = PublicWebcamDataLoader.loadFromAssets(this)
        val labels = webcams.map { webcam ->
            val type = webcam.tagsJson?.let { CameraTagsForDirectory.type(it) }.orEmpty()
            "${webcam.title}  •  ${webcam.operator}\n${webcam.lat}, ${webcam.lon}  •  $type"
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, labels)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedLabel = adapter.getItem(position)
            val webcam = webcams[labels.indexOf(selectedLabel).coerceAtLeast(0)]
            setResult(Activity.RESULT_OK, intent.apply {
                putExtra(EXTRA_LAT, webcam.lat)
                putExtra(EXTRA_LON, webcam.lon)
                putExtra(EXTRA_ID, webcam.id)
            })
            finish()
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter.filter(newText.orEmpty())
                return true
            }
        })
    }

    companion object {
        const val EXTRA_LAT = "webcam_lat"
        const val EXTRA_LON = "webcam_lon"
        const val EXTRA_ID = "webcam_id"
    }
}

private object CameraTagsForDirectory {
    fun type(json: String): String = runCatching {
        val marker = "\"streamType\":\""
        json.substringAfter(marker).substringBefore('"')
    }.getOrDefault("")
}
