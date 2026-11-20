package com.tuyen.tubeviet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.tuyen.tubeviet.Item
import com.tuyen.tubeviet.R
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/** Vòng xoay tải / thông báo lỗi / nút thử lại. */
class StateView(root: Activity, private val onRetry: () -> Unit) {
    private val progress: ProgressBar = root.findViewById(R.id.progress)
    private val errorBox: View = root.findViewById(R.id.error_box)
    private val errorText: TextView = root.findViewById(R.id.error_text)
    private val retry: Button = root.findViewById(R.id.retry)

    init {
        retry.setOnClickListener { onRetry() }
    }

    fun loading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        if (show) errorBox.visibility = View.GONE
    }

    fun error(e: Throwable?) {
        progress.visibility = View.GONE
        if (e == null) {
            errorBox.visibility = View.GONE
            return
        }
        errorBox.visibility = View.VISIBLE
        retry.visibility = View.VISIBLE
        errorText.text = errorMessage(e)
    }

    fun empty(msg: String) {
        progress.visibility = View.GONE
        errorBox.visibility = View.VISIBLE
        retry.visibility = View.GONE
        errorText.text = msg
    }

    fun hide() {
        progress.visibility = View.GONE
        errorBox.visibility = View.GONE
    }
}

fun errorMessage(e: Throwable): String {
    var t: Throwable? = e
    while (t != null) {
        when (t) {
            is ReCaptchaException -> return "YouTube đang tạm chặn do truy cập nhiều. Vui lòng thử lại sau ít phút."
            is AgeRestrictedContentException -> return "Video giới hạn độ tuổi, cần đăng nhập để xem."
            is GeographicRestrictionException -> return "Video không xem được ở khu vực của bạn."
            is PrivateContentException -> return "Đây là video riêng tư."
            is PaidContentException -> return "Đây là nội dung trả phí."
            is ContentNotAvailableException -> return "Video không còn khả dụng." +
                (t.message?.let { "\n($it)" } ?: "")
            is IOException -> return "Không kết nối được mạng. Kiểm tra Wi-Fi/4G rồi thử lại."
        }
        t = t.cause
    }
    return "Đã có lỗi xảy ra: ${e.javaClass.simpleName}" + (e.message?.let { "\n$it" } ?: "")
}

object Nav {
    fun watch(ctx: Context, url: String) {
        ctx.startActivity(
            Intent(ctx, WatchActivity::class.java).putExtra(WatchActivity.EXTRA_URL, url)
        )
    }

    fun channel(ctx: Context, url: String) {
        ctx.startActivity(
            Intent(ctx, ChannelActivity::class.java).putExtra(ChannelActivity.EXTRA_URL, url)
        )
    }

    fun open(ctx: Context, item: Item) {
        if (item.isChannel) channel(ctx, item.url) else watch(ctx, item.url)
    }
}
