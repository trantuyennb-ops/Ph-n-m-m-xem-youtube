package com.tuyen.tubeviet.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.tuyen.tubeviet.Item
import com.tuyen.tubeviet.R
import com.tuyen.tubeviet.Yt

/**
 * Danh sách video/kênh kiểu YouTube. Có thể có một "header" ở đầu
 * (ví dụ phần thông tin video, thông tin kênh) và vòng xoay tải thêm ở cuối.
 */
class ItemAdapter(
    private val onClick: (Item) -> Unit,
    private val onChannelClick: ((Item) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Item>()
    var header: View? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var loading: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun set(list: List<Item>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(list: List<Item>) {
        val start = items.size + headerCount
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    val size get() = items.size
    private val headerCount get() = if (header != null) 1 else 0

    override fun getItemCount() = headerCount + items.size + if (loading) 1 else 0

    override fun getItemViewType(position: Int): Int {
        if (header != null && position == 0) return T_HEADER
        val i = position - headerCount
        if (i >= items.size) return T_LOADING
        return if (items[i].isChannel) T_CHANNEL else T_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_HEADER -> object : RecyclerView.ViewHolder(
                FrameHolder(parent.context)
            ) {}
            T_LOADING -> object : RecyclerView.ViewHolder(
                inf.inflate(R.layout.item_loading, parent, false)
            ) {}
            T_CHANNEL -> VH(inf.inflate(R.layout.item_channel, parent, false))
            else -> VH(inf.inflate(R.layout.item_video, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            T_HEADER -> (holder.itemView as FrameHolder).setChild(header!!)
            T_VIDEO, T_CHANNEL -> (holder as VH).bind(items[position - headerCount])
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView? = v.findViewById(R.id.thumb)
        private val avatar: ImageView? = v.findViewById(R.id.avatar)
        private val title: TextView = v.findViewById(R.id.title)
        private val meta: TextView = v.findViewById(R.id.meta)
        private val duration: TextView? = v.findViewById(R.id.duration)

        fun bind(it: Item) {
            title.text = it.title
            itemView.setOnClickListener { _ -> onClick(it) }
            if (it.isChannel) {
                avatar?.load(it.thumb)
                meta.text = listOf(Yt.subs(it.subs),
                    if (it.streamCount > 0) "${it.streamCount} video" else "")
                    .filter { s -> s.isNotEmpty() }.joinToString(" • ")
                return
            }
            thumb?.load(it.thumb)
            if (it.avatar != null) {
                avatar?.visibility = View.VISIBLE
                avatar?.load(it.avatar)
            } else {
                avatar?.setImageDrawable(null)
            }
            avatar?.setOnClickListener { _ ->
                if (it.uploaderUrl.isNotEmpty()) onChannelClick?.invoke(it)
            }
            meta.text = listOf(it.uploader, Yt.views(it.views, it.isLive),
                if (it.isLive) "" else it.date)
                .filter { s -> s.isNotEmpty() }.joinToString(" • ")
            duration?.let { d ->
                when {
                    it.isLive -> {
                        d.visibility = View.VISIBLE
                        d.text = "TRỰC TIẾP"
                        d.setBackgroundColor(0xFFCC0000.toInt())
                    }
                    it.duration > 0 -> {
                        d.visibility = View.VISIBLE
                        d.text = Yt.duration(it.duration)
                        d.setBackgroundResource(R.drawable.bg_duration)
                    }
                    else -> d.visibility = View.GONE
                }
            }
        }
    }

    /** Khung chứa header, tự tháo header khỏi cha cũ trước khi gắn. */
    class FrameHolder(ctx: android.content.Context) : android.widget.FrameLayout(ctx) {
        init {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun setChild(v: View) {
            if (v.parent === this) return
            (v.parent as? ViewGroup)?.removeView(v)
            removeAllViews()
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    companion object {
        const val T_HEADER = 0
        const val T_VIDEO = 1
        const val T_CHANNEL = 2
        const val T_LOADING = 3
    }
}
