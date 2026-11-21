package com.tuyen.tubeviet

import android.content.Context
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes

/**
 * Giữ một trình phát dùng chung cho cả màn hình xem video và cửa sổ nổi / phát nền,
 * để khi bấm Home video vẫn chạy tiếp, không bị tải lại.
 */
object PlayerHolder {
    private var player: ExoPlayer? = null

    /** Màn hình xem video còn tồn tại hay không. */
    var activityAlive = false

    /** Tiêu đề + link video đang phát (để hiện trên thông báo / cửa sổ nổi). */
    var title: String = ""
    var url: String? = null

    fun get(ctx: Context): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(ctx.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player = p
        return p
    }

    fun peek(): ExoPlayer? = player

    fun release() {
        player?.release()
        player = null
    }
}
