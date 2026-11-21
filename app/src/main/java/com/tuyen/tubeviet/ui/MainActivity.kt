package com.tuyen.tubeviet.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tuyen.tubeviet.Feed
import com.tuyen.tubeviet.History
import com.tuyen.tubeviet.KioskFeed
import com.tuyen.tubeviet.R
import com.tuyen.tubeviet.SearchFeed
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory

class MainActivity : AppCompatActivity() {

    private data class Chip(val label: String, val make: () -> Feed)

    private val chips = listOf(
        Chip("Tất cả") { SearchFeed("video mới nhất hôm nay", YoutubeSearchQueryHandlerFactory.VIDEOS) },
        Chip("Âm nhạc") { KioskFeed("trending_music") },
        Chip("Trò chơi") { KioskFeed("trending_gaming") },
        Chip("Trực tiếp") { KioskFeed("live") },
        Chip("Phim") { KioskFeed("trending_movies_and_shows") },
        Chip("Podcast") { KioskFeed("trending_podcasts_episodes") },
        Chip("Tin tức") { SearchFeed("tin tức mới nhất", YoutubeSearchQueryHandlerFactory.VIDEOS) },
        Chip("Hài hước") { SearchFeed("hài hước", YoutubeSearchQueryHandlerFactory.VIDEOS) },
        Chip("Nấu ăn") { SearchFeed("nấu ăn ngon", YoutubeSearchQueryHandlerFactory.VIDEOS) },
        Chip("Thể thao") { SearchFeed("thể thao highlight", YoutubeSearchQueryHandlerFactory.VIDEOS) },
        Chip("Thiếu nhi") { SearchFeed("phim hoạt hình thiếu nhi", YoutubeSearchQueryHandlerFactory.VIDEOS) },
    )

    private lateinit var adapter: ItemAdapter
    private lateinit var controller: FeedController
    private lateinit var state: StateView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var chipViews: List<TextView>
    private var selectedChip = 0
    private var onHistory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val list = findViewById<RecyclerView>(R.id.list)
        swipe = findViewById(R.id.swipe)
        swipe.setColorSchemeResources(R.color.red)
        swipe.setProgressBackgroundColorSchemeResource(R.color.surface)

        adapter = ItemAdapter(
            onClick = { Nav.open(this, it) },
            onChannelClick = { Nav.channel(this, it.uploaderUrl) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        state = StateView(this) { reload() }
        controller = FeedController(lifecycleScope, list, adapter) { loading, error, empty ->
            if (!loading) swipe.isRefreshing = false
            when {
                loading -> state.loading(!swipe.isRefreshing)
                error != null -> state.error(error)
                empty && onHistory -> state.empty("Chưa có video nào trong lịch sử.")
                empty -> state.empty("Không có video nào.")
                else -> state.hide()
            }
        }
        swipe.setOnRefreshListener { reload() }

        findViewById<ImageButton>(R.id.btn_mic).setOnClickListener {
            startActivity(
                Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_VOICE, true)
            )
        }
        findViewById<ImageButton>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Xóa toàn bộ lịch sử xem?")
                .setPositiveButton("Xóa") { _, _ ->
                    History.clear()
                    controller.show(emptyList())
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        buildChips()

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener {
            onHistory = it.itemId == R.id.nav_history
            findViewById<View>(R.id.chips_scroll).visibility =
                if (onHistory) View.GONE else View.VISIBLE
            findViewById<View>(R.id.btn_clear).visibility =
                if (onHistory) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.app_title).text =
                if (onHistory) "Lịch sử" else getString(R.string.app_name)
            reload()
            true
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        if (onHistory) controller.show(History.all())
    }

    private fun reload() {
        if (onHistory) {
            swipe.isRefreshing = false
            controller.show(History.all())
        } else {
            controller.load(chips[selectedChip].make())
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildChips() {
        val box = findViewById<LinearLayout>(R.id.chips)
        chipViews = chips.mapIndexed { i, c ->
            TextView(this).apply {
                text = c.label
                textSize = 14f
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    if (selectedChip != i) {
                        selectedChip = i
                        refreshChips()
                        reload()
                    }
                }
                box.addView(this)
            }
        }
        refreshChips()
    }

    private fun refreshChips() {
        chipViews.forEachIndexed { i, v ->
            v.isSelected = i == selectedChip
            v.setTextColor(if (i == selectedChip) 0xFF0F0F0F.toInt() else 0xFFF1F1F1.toInt())
        }
    }
}
