package com.tuyen.tubeviet.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Trình phát dự phòng: dùng trình phát nhúng chính thức của YouTube trong WebView.
 * Chỉ dùng khi bộ đọc chính không lấy được video (ví dụ một số video thiếu nhi).
 * Chặn các yêu cầu tải quảng cáo của trình phát nhúng.
 */
class EmbedPlayer(private val web: WebView) {

    var active = false
        private set

    init {
        setup()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (AD_PATTERNS.any { url.contains(it) }) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                // Không cho bấm logo/tiêu đề để mở trang YouTube bên trong khung phát
                return request.isForMainFrame
            }
        }
    }

    fun play(videoId: String, startSec: Long = 0) {
        active = true
        web.visibility = View.VISIBLE
        web.onResume()
        val src = "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0" +
            "&modestbranding=1&fs=0&iv_load_policy=3&start=$startSec"
        val html = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}
            iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>
            </head><body>
            <iframe src="$src" referrerpolicy="strict-origin"
              allow="autoplay; encrypted-media; picture-in-picture"></iframe>
            </body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
    }

    fun stop() {
        if (!active) return
        active = false
        web.loadUrl("about:blank")
        web.visibility = View.GONE
    }

    fun pause() {
        if (active) web.onPause()
    }

    fun resume() {
        if (active) web.onResume()
    }

    fun destroy() {
        web.destroy()
    }

    companion object {
        private const val BASE_URL = "https://tubeviet.app/"
        private val AD_PATTERNS = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "imasdk.googleapis.com",
            "/pagead/",
            "/api/stats/ads",
            "/ptracking",
            "ad.youtube.com",
            "/get_midroll_",
        )
    }
}
