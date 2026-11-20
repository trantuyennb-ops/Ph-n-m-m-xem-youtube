package com.tuyen.tubeviet.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.tuyen.tubeviet.ChannelTabFeed
import com.tuyen.tubeviet.R
import com.tuyen.tubeviet.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

class ChannelActivity : AppCompatActivity() {

    private lateinit var adapter: ItemAdapter
    private lateinit var controller: FeedController
    private lateinit var state: StateView
    private lateinit var header: View
    private lateinit var url: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)
        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        val list = findViewById<RecyclerView>(R.id.list)
        adapter = ItemAdapter(onClick = { Nav.open(this, it) })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        header = LayoutInflater.from(this).inflate(R.layout.header_channel, list, false)

        state = StateView(this) { load() }
        controller = FeedController(lifecycleScope, list, adapter) { loading, error, empty ->
            when {
                error != null -> state.error(error)
                empty && !loading -> state.empty("Kênh chưa có video nào.")
                else -> state.hide()
            }
        }
        load()
    }

    private fun load() {
        state.loading(true)
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { ChannelInfo.getInfo(Yt.service, url) }
                state.hide()
                bindHeader(info)
                adapter.header = header
                val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
                    ?: info.tabs.firstOrNull()
                if (tab != null) controller.load(ChannelTabFeed(tab))
                else state.empty("Kênh chưa có video nào.")
            } catch (e: Exception) {
                state.error(e)
            }
        }
    }

    private fun bindHeader(info: ChannelInfo) {
        findViewById<TextView>(R.id.top_title).text = info.name
        header.findViewById<TextView>(R.id.name).text = info.name
        header.findViewById<TextView>(R.id.subs).text = Yt.subs(info.subscriberCount)
        header.findViewById<ImageView>(R.id.avatar).load(Yt.bestImage(info.avatars, 176))
        val desc = header.findViewById<TextView>(R.id.desc)
        desc.text = info.description ?: ""
        desc.visibility = if (info.description.isNullOrBlank()) View.GONE else View.VISIBLE
        desc.setOnClickListener { desc.maxLines = if (desc.maxLines == 2) 100 else 2 }
        val bannerUrl = Yt.bestImage(info.banners, 1280)
        val banner = header.findViewById<ImageView>(R.id.banner)
        if (bannerUrl != null) {
            banner.visibility = View.VISIBLE
            banner.load(bannerUrl)
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
