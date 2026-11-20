package com.tuyen.tubeviet

import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.dash.manifest.DashManifest
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.tuyen.tubeviet.newpipe.DownloaderImpl
import com.tuyen.tubeviet.newpipe.YoutubeHttpDataSource
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream

/**
 * Tạo nguồn phát cho ExoPlayer từ thông tin video YouTube.
 * Cách làm dựa theo NewPipe (GPL-3.0).
 */
object PlayerSource {
    private const val TAG = "PlayerSource"

    private val generic = DefaultHttpDataSource.Factory()
        .setUserAgent(DownloaderImpl.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)

    private val ytDash = YoutubeHttpDataSource.Factory()
        .setRangeParameterEnabled(true)
        .setRnParameterEnabled(true)

    private val ytProgressive = YoutubeHttpDataSource.Factory()
        .setRangeParameterEnabled(false)
        .setRnParameterEnabled(true)

    private val ytHls = YoutubeHttpDataSource.Factory()
        .setRangeParameterEnabled(false)
        .setRnParameterEnabled(false)

    fun isLive(info: StreamInfo) =
        info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM

    /** Danh sách chất lượng: mỗi độ phân giải một luồng, ưu tiên MP4 (máy cũ giải mã tốt hơn). */
    fun qualities(info: StreamInfo): List<VideoStream> {
        val all = (info.videoStreams + info.videoOnlyStreams).filter {
            it.deliveryMethod != DeliveryMethod.TORRENT && it.height > 0 &&
                (it.isUrl || it.itagItem != null)
        }
        fun rank(v: VideoStream): Int {
            var r = 0
            if (v.format == MediaFormat.MPEG_4) r += 10
            if (v.format == MediaFormat.WEBM) r += 5
            if (!v.isVideoOnly()) r += 1
            return r
        }
        return all.groupBy { it.getResolution() }
            .map { (_, list) -> list.maxByOrNull { rank(it) }!! }
            .sortedWith(compareByDescending<VideoStream> { it.height }.thenByDescending { it.fps })
    }

    fun defaultQuality(list: List<VideoStream>, preferredHeight: Int): VideoStream? {
        if (list.isEmpty()) return null
        return list.firstOrNull { it.height <= preferredHeight } ?: list.last()
    }

    fun bestAudio(info: StreamInfo): AudioStream? {
        val list = info.audioStreams.filter { it.deliveryMethod != DeliveryMethod.TORRENT }
        if (list.isEmpty()) return null
        val original = list.filter {
            it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
        }.ifEmpty { list }
        val m4a = original.filter { it.format == MediaFormat.M4A }.ifEmpty { original }
        return m4a.maxByOrNull { it.averageBitrate }
    }

    fun build(info: StreamInfo, video: VideoStream?, audio: AudioStream?): MediaSource? {
        if (isLive(info)) {
            buildLive(info)?.let { return it }
        }
        val sources = mutableListOf<MediaSource>()
        if (video != null) {
            sources += youtubeSource(video, info) ?: return null
        }
        if (audio != null && (video == null || video.isVideoOnly())) {
            youtubeSource(audio, info)?.let { sources += it }
        }
        return when (sources.size) {
            0 -> null
            1 -> sources[0]
            else -> MergingMediaSource(true, *sources.toTypedArray())
        }
    }

    private fun buildLive(info: StreamInfo): MediaSource? {
        val live = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10_000).build()
        return try {
            when {
                info.hlsUrl.isNotEmpty() -> HlsMediaSource.Factory(generic)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(
                        MediaItem.Builder().setUri(info.hlsUrl).setLiveConfiguration(live).build()
                    )
                info.dashMpdUrl.isNotEmpty() -> DashMediaSource.Factory(
                    DefaultDashChunkSource.Factory(generic), generic
                ).createMediaSource(
                    MediaItem.Builder().setUri(info.dashMpdUrl).setLiveConfiguration(live).build()
                )
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "live source", e)
            null
        }
    }

    private fun parseManifest(text: String, stream: Stream): DashManifest =
        DashManifestParser().parse(
            Uri.parse(stream.manifestUrl ?: ""),
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        )

    private fun dashFrom(manifest: DashManifest, stream: Stream): MediaSource =
        DashMediaSource.Factory(DefaultDashChunkSource.Factory(ytDash), ytDash)
            .createMediaSource(manifest, MediaItem.Builder().setUri(stream.content).build())

    private fun progressive(stream: Stream): MediaSource =
        ProgressiveMediaSource.Factory(ytProgressive)
            .createMediaSource(MediaItem.fromUri(stream.content))

    private fun youtubeSource(stream: Stream, info: StreamInfo): MediaSource? {
        try {
            if (info.streamType == StreamType.POST_LIVE_STREAM && !stream.isUrl) {
                val itag = stream.itagItem ?: return null
                val m = YoutubePostLiveStreamDvrDashManifestCreator.fromPostLiveStreamDvrStreamingUrl(
                    stream.content, itag, itag.targetDurationSec, info.duration
                )
                return dashFrom(parseManifest(m, stream), stream)
            }
            return when (stream.deliveryMethod) {
                DeliveryMethod.PROGRESSIVE_HTTP -> {
                    val separate = (stream is VideoStream && stream.isVideoOnly()) ||
                        stream is AudioStream
                    val itag = stream.itagItem
                    if (separate && itag != null) {
                        try {
                            val m = YoutubeProgressiveDashManifestCreator
                                .fromProgressiveStreamingUrl(stream.content, itag, info.duration)
                            dashFrom(parseManifest(m, stream), stream)
                        } catch (e: Exception) {
                            Log.w(TAG, "progressive manifest failed, fallback", e)
                            progressive(stream)
                        }
                    } else {
                        progressive(stream)
                    }
                }
                DeliveryMethod.DASH -> {
                    if (stream.isUrl) {
                        DashMediaSource.Factory(DefaultDashChunkSource.Factory(ytDash), ytDash)
                            .createMediaSource(MediaItem.fromUri(stream.content))
                    } else {
                        val itag = stream.itagItem ?: return null
                        val m = YoutubeOtfDashManifestCreator
                            .fromOtfStreamingUrl(stream.content, itag, info.duration)
                        dashFrom(parseManifest(m, stream), stream)
                    }
                }
                DeliveryMethod.HLS -> HlsMediaSource.Factory(ytHls)
                    .createMediaSource(MediaItem.fromUri(stream.content))
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "youtubeSource", e)
            return null
        }
    }
}
