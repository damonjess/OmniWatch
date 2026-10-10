package com.example.omniwatch

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SearchView
import android.widget.Spinner
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
    private var displayedWebcams: List<CameraEntity> = emptyList()
    private var currentQuery: String = ""
    private var currentProvider: String = "All Providers"

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
        
        webcams = PublicWebcamDataLoader.loadFromAssets(this)
        
        val providers = listOf("All Providers") + webcams.map { 
            it.operator.takeIf { op -> op.isNotBlank() } ?: "Unknown" 
        }.distinct().sorted()
        
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PublicWebcamsActivity, android.R.layout.simple_spinner_dropdown_item, providers)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentProvider = providers[position]
                    updateList()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        listView = ListView(this)
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        root.addView(spinner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(4, 12, 4, 12) })
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, mutableListOf())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in displayedWebcams.indices) {
                val webcam = displayedWebcams[position]
                setResult(Activity.RESULT_OK, intent.apply {
                    putExtra(EXTRA_LAT, webcam.lat)
                    putExtra(EXTRA_LON, webcam.lon)
                    putExtra(EXTRA_ID, webcam.id)
                })
                finish()
            }
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                currentQuery = newText.orEmpty()
                updateList()
                return true
            }
        })
        
        updateList()
    }

    private fun updateList() {
        displayedWebcams = webcams.filter { 
            val matchesProvider = currentProvider == "All Providers" || it.operator.equals(currentProvider, ignoreCase = true)
            val matchesQuery = currentQuery.isBlank() || 
                it.title.contains(currentQuery, ignoreCase = true) || 
                it.operator.contains(currentQuery, ignoreCase = true)
            matchesProvider && matchesQuery
        }
        
        val labels = displayedWebcams.map { webcam ->
            val type = webcam.tagsJson?.let { CameraTagsForDirectory.type(it) }.orEmpty()
            "${webcam.title}  •  ${webcam.operator}\n${webcam.lat}, ${webcam.lon}  •  $type"
        }
        
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
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
