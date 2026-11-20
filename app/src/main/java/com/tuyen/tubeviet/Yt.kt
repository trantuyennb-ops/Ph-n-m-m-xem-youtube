package com.tuyen.tubeviet

import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/** Một mục hiển thị trong danh sách (video hoặc kênh). */
data class Item(
    val url: String,
    val title: String,
    val isChannel: Boolean = false,
    val uploader: String = "",
    val uploaderUrl: String = "",
    val thumb: String? = null,
    val avatar: String? = null,
    val duration: Long = -1,
    val views: Long = -1,
    val date: String = "",
    val isLive: Boolean = false,
    val subs: Long = -1,
    val streamCount: Long = -1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url).put("title", title).put("uploader", uploader)
        .put("uploaderUrl", uploaderUrl).put("thumb", thumb ?: "").put("avatar", avatar ?: "")
        .put("duration", duration).put("views", views)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            url = o.optString("url"),
            title = o.optString("title"),
            uploader = o.optString("uploader"),
            uploaderUrl = o.optString("uploaderUrl"),
            thumb = o.optString("thumb").ifEmpty { null },
            avatar = o.optString("avatar").ifEmpty { null },
            duration = o.optLong("duration", -1),
            views = o.optLong("views", -1),
        )

        fun from(info: InfoItem): Item? = when (info) {
            is StreamInfoItem -> Item(
                url = info.url,
                title = info.name ?: "",
                uploader = info.uploaderName ?: "",
                uploaderUrl = info.uploaderUrl ?: "",
                thumb = Yt.bestImage(info.thumbnails, 640),
                avatar = Yt.bestImage(info.uploaderAvatars, 96),
                duration = info.duration,
                views = info.viewCount,
                date = Yt.dateText(info.uploadDate, info.textualUploadDate),
                isLive = info.streamType == StreamType.LIVE_STREAM ||
                    info.streamType == StreamType.AUDIO_LIVE_STREAM,
            )
            is ChannelInfoItem -> Item(
                url = info.url,
                title = info.name ?: "",
                isChannel = true,
                thumb = Yt.bestImage(info.thumbnails, 176),
                subs = info.subscriberCount,
                streamCount = info.streamCount,
            )
            else -> null
        }
    }
}

/** Nguồn dữ liệu có phân trang. */
interface Feed {
    fun first(): Pair<List<Item>, Page?>
    fun more(page: Page): Pair<List<Item>, Page?>
}

private fun List<InfoItem>.toItems() = mapNotNull { Item.from(it) }

class SearchFeed(private val query: String, private val filter: String? = null) : Feed {
    private var handler: SearchQueryHandler? = null
    override fun first(): Pair<List<Item>, Page?> {
        val h = Yt.service.searchQHFactory.fromQuery(
            query, if (filter == null) emptyList() else listOf(filter), ""
        )
        handler = h
        val info = SearchInfo.getInfo(Yt.service, h)
        return info.relatedItems.toItems() to info.nextPage
    }

    override fun more(page: Page): Pair<List<Item>, Page?> {
        val p = SearchInfo.getMoreItems(Yt.service, handler!!, page)
        return p.items.toItems() to p.nextPage
    }
}

class KioskFeed(private val kioskId: String) : Feed {
    private var url: String? = null
    override fun first(): Pair<List<Item>, Page?> {
        val ex = Yt.service.kioskList.getExtractorById(kioskId, null)
        ex.fetchPage()
        val info = KioskInfo.getInfo(ex)
        url = info.url
        return info.relatedItems.mapNotNull { Item.from(it) } to info.nextPage
    }

    override fun more(page: Page): Pair<List<Item>, Page?> {
        val p = KioskInfo.getMoreItems(Yt.service, url!!, page)
        return p.items.mapNotNull { Item.from(it) } to p.nextPage
    }
}

class ChannelTabFeed(private val tab: ListLinkHandler) : Feed {
    override fun first(): Pair<List<Item>, Page?> {
        val info = ChannelTabInfo.getInfo(Yt.service, tab)
        return info.relatedItems.toItems() to info.nextPage
    }

    override fun more(page: Page): Pair<List<Item>, Page?> {
        val p = ChannelTabInfo.getMoreItems(Yt.service, tab, page)
        return p.items.toItems() to p.nextPage
    }
}

object Yt {
    val service: StreamingService get() = ServiceList.YouTube

    fun bestImage(images: List<Image>?, target: Int): String? {
        if (images.isNullOrEmpty()) return null
        return images.filter { !it.url.isNullOrEmpty() }
            .minByOrNull {
                val w = if (it.width > 0) it.width else if (it.height > 0) it.height * 16 / 9 else target
                abs(w - target)
            }?.url
    }

    private val vi = Locale("vi", "VN")

    private fun compact(n: Long): String {
        fun f(x: Double): String {
            val s = String.format(vi, if (x < 10) "%.1f" else "%.0f", x)
            return s.removeSuffix(",0")
        }
        return when {
            n < 1_000 -> n.toString()
            n < 1_000_000 -> f(n / 1_000.0) + " N"
            n < 1_000_000_000 -> f(n / 1_000_000.0) + " Tr"
            else -> f(n / 1_000_000_000.0) + " T"
        }
    }

    fun views(n: Long, live: Boolean = false): String =
        if (n < 0) "" else compact(n) + if (live) " người đang xem" else " lượt xem"

    fun subs(n: Long): String = if (n < 0) "" else compact(n) + " người đăng ký"

    fun likes(n: Long): String = if (n < 0) "Thích" else compact(n)

    fun duration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun dateText(date: DateWrapper?, textual: String?): String {
        val instant = try {
            date?.instant
        } catch (_: Throwable) {
            null
        }
        if (instant == null) return textual ?: ""
        val d = Duration.between(instant, Instant.now())
        val sec = d.seconds
        return when {
            sec < 60 -> "Vừa xong"
            sec < 3600 -> "${sec / 60} phút trước"
            sec < 86400 -> "${sec / 3600} giờ trước"
            sec < 7 * 86400 -> "${sec / 86400} ngày trước"
            sec < 30 * 86400 -> "${sec / (7 * 86400)} tuần trước"
            sec < 365 * 86400 -> "${sec / (30 * 86400)} tháng trước"
            else -> "${sec / (365 * 86400)} năm trước"
        }
    }

    /** Lấy link YouTube từ nội dung được chia sẻ. */
    fun extractUrl(text: String?): String? {
        if (text == null) return null
        val m = Regex("https?://\\S+").find(text) ?: return null
        return m.value
    }

    fun isStreamUrl(url: String): Boolean = try {
        service.streamLHFactory.acceptUrl(url)
    } catch (_: Throwable) {
        false
    }
}
