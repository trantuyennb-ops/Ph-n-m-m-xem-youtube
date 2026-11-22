package com.tuyen.tubeviet.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import com.tuyen.tubeviet.ApkInstaller
import com.tuyen.tubeviet.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // Chọn file APK (trên USB) để cài
    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val dlg = AlertDialog.Builder(this).setMessage("Đang chuẩn bị cài đặt…")
            .setCancelable(false).show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApkInstaller.installFromUri(this@MainActivity, uri) }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Không cài được: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                dlg.dismiss()
            }
        }
    }

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
        findViewById<ImageButton>(R.id.btn_more).setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menu.add(0, 1, 0, "Cài file APK từ USB")
                menu.add(0, 2, 1, "Kiểm tra bản mới")
                menu.add(0, 3, 2, "Phiên bản ${BuildConfig.VERSION_NAME}").isEnabled = false
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> if (ApkInstaller.ensurePermission(this@MainActivity)) {
                            pickApk.launch(arrayOf("*/*"))
                        }
                        2 -> checkUpdate()
                    }
                    true
                }
                show()
            }
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

    private fun checkUpdate() {
        val dlg = AlertDialog.Builder(this).setMessage("Đang kiểm tra bản mới…")
            .setCancelable(false).show()
        lifecycleScope.launch {
            val latest = try {
                withContext(Dispatchers.IO) { ApkInstaller.latestBuild() }
            } catch (e: Exception) {
                null
            }
            dlg.dismiss()
            when {
                latest == null -> Toast.makeText(
                    this@MainActivity,
                    "Không kiểm tra được. Bạn có thể tải file APK về USB rồi chọn \"Cài file APK từ USB\".",
                    Toast.LENGTH_LONG
                ).show()
                latest <= BuildConfig.VERSION_CODE -> Toast.makeText(
                    this@MainActivity, "Bạn đang dùng bản mới nhất", Toast.LENGTH_SHORT
                ).show()
                else -> AlertDialog.Builder(this@MainActivity)
                    .setMessage("Có bản mới (build $latest). Tải và cài ngay?")
                    .setPositiveButton("Cập nhật") { _, _ -> downloadUpdate() }
                    .setNegativeButton("Để sau", null)
                    .show()
            }
        }
    }

    private fun downloadUpdate() {
        if (!ApkInstaller.ensurePermission(this)) return
        val dlg = AlertDialog.Builder(this).setMessage("Đang tải bản mới… 0%")
            .setCancelable(false).show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApkInstaller.downloadAndInstall(this@MainActivity) { p ->
                        runOnUiThread { dlg.setMessage("Đang tải bản mới… $p%") }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Không tải được: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                dlg.dismiss()
            }
        }
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
