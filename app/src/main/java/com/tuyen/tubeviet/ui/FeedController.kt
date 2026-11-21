package com.tuyen.tubeviet.ui

import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tuyen.tubeviet.Feed
import com.tuyen.tubeviet.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

/** Tải danh sách + tự tải thêm khi cuộn xuống cuối. */
class FeedController(
    private val scope: LifecycleCoroutineScope,
    private val recycler: RecyclerView,
    private val adapter: ItemAdapter,
    private val onState: (loading: Boolean, error: Throwable?, empty: Boolean) -> Unit,
) {
    private var feed: Feed? = null
    private var next: Page? = null
    private var job: Job? = null
    private var loadingMore = false

    init {
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (rv.adapter !== adapter) return
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) {
                    loadMore()
                }
            }
        })
    }

    fun load(f: Feed, keepHeader: Boolean = true) {
        feed = f
        next = null
        job?.cancel()
        loadingMore = false
        adapter.set(emptyList())
        adapter.loading = adapter.header != null && keepHeader
        onState(true, null, false)
        job = scope.launch {
            try {
                val (items, page) = withContext(Dispatchers.IO) { f.first() }
                if (feed !== f) return@launch
                next = page
                adapter.loading = false
                adapter.set(items)
                onState(false, null, items.isEmpty())
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                adapter.loading = false
                onState(false, e, true)
            }
        }
    }

    fun show(items: List<Item>) {
        feed = null
        next = null
        job?.cancel()
        adapter.loading = false
        adapter.set(items)
        onState(false, null, items.isEmpty())
    }

    private fun loadMore() {
        val f = feed ?: return
        val p = next ?: return
        if (loadingMore || !Page.isValid(p)) return
        loadingMore = true
        adapter.loading = true
        scope.launch {
            try {
                val (items, page) = withContext(Dispatchers.IO) { f.more(p) }
                if (feed !== f) return@launch
                next = page
                adapter.loading = false
                adapter.add(items)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                adapter.loading = false
            } finally {
                loadingMore = false
            }
        }
    }
}
