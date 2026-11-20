package com.tuyen.tubeviet.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.tuyen.tubeviet.History
import com.tuyen.tubeviet.Item
import com.tuyen.tubeviet.PlayerSource
import com.tuyen.tubeviet.R
import com.tuyen.tubeviet.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

class WatchActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var playerBox: View
    private lateinit var list: RecyclerView
    private lateinit var header: View
    private lateinit var adapter: ItemAdapter
    private lateinit var loadingView: View
    private lateinit var errorBox: View
    private lateinit var errorText: TextView
    private lateinit var overlay: View

    private var info: StreamInfo? = null
    private var qualities: List<VideoStream> = emptyList()
    private var quality: VideoStream? = null
    private var audio: AudioStream? = null
    private var currentUrl: String? = null
    private val backStack = ArrayDeque<String>()
    private var loadJob: Job? = null
    private var fullscreen = false
    private var autoRetries = 0
    private var speed = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch)

        playerView = findViewById(R.id.player)
        playerBox = findViewById(R.id.player_box)
        list = findViewById(R.id.list)
        loadingView = findViewById(R.id.player_loading)
        errorBox = findViewById(R.id.player_error)
        errorText = findViewById(R.id.player_error_text)
        overlay = findViewById(R.id.overlay_buttons)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        playerView.player = player
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setFullscreenButtonClickListener { toggleFullscreen() }
        playerView.setControllerVisibilityListener(
            StyledPlayerView.ControllerVisibilityListener { v -> overlay.visibility = v }
        )
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerView.keepScreenOn = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) autoRetries = 0
            }

            override fun onPlayerError(error: PlaybackException) = handlePlayerError(error)
        })

        adapter = ItemAdapter(
            onClick = { if (!it.isChannel) load(it.url, true) else Nav.channel(this, it.url) },
            onChannelClick = { Nav.channel(this, it.uploaderUrl) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        header = LayoutInflater.from(this).inflate(R.layout.header_watch, list, false)

        findViewById<View>(R.id.btn_settings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btn_pip).setOnClickListener { enterPip() }
        header.findViewById<View>(R.id.btn_pip2).setOnClickListener { enterPip() }
        header.findViewById<View>(R.id.btn_share).setOnClickListener { share() }
        findViewById<View>(R.id.player_retry).setOnClickListener {
            currentUrl?.let { load(it, false, keepPosition = true) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreen -> setFullscreen(false)
                    backStack.isNotEmpty() -> load(backStack.removeLast(), false)
                    else -> finish()
                }
            }
        })

        updatePlayerSize()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_URL)
            ?: intent?.dataString
            ?: Yt.extractUrl(intent?.getStringExtra(Intent.EXTRA_TEXT))
        if (url == null) {
            if (currentUrl == null) finish()
            return
        }
        if (!Yt.isStreamUrl(url)) {
            Toast.makeText(this, "Link không phải video YouTube", Toast.LENGTH_SHORT).show()
            if (currentUrl == null) finish()
            return
        }
        load(url, currentUrl != null)
    }

    // ---------------- Tải video ----------------

    private fun load(url: String, pushCurrent: Boolean, keepPosition: Boolean = false) {
        val resumeAt = if (keepPosition) player.currentPosition else -1L
        saveCurrentPosition()
        if (pushCurrent && currentUrl != null && currentUrl != url) backStack.addLast(currentUrl!!)
        currentUrl = url
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        errorBox.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        adapter.header = null
        adapter.set(emptyList())
        list.scrollToPosition(0)

        loadJob = lifecycleScope.launch {
            try {
                val i = withContext(Dispatchers.IO) { StreamInfo.getInfo(Yt.service, url) }
                info = i
                bindHeader(i)
                adapter.header = header
                adapter.set(i.relatedItems.mapNotNull { Item.from(it) })
                History.add(
                    Item(
                        url = url, title = i.name ?: "", uploader = i.uploaderName ?: "",
                        uploaderUrl = i.uploaderUrl ?: "",
                        thumb = Yt.bestImage(i.thumbnails, 640),
                        avatar = Yt.bestImage(i.uploaderAvatars, 96),
                        duration = i.duration, views = i.viewCount,
                    )
                )
                val start = when {
                    resumeAt >= 0 -> resumeAt
                    PlayerSource.isLive(i) -> 0L
                    else -> History.position(url).let {
                        if (it > 5_000 && it < i.duration * 1000 - 10_000) it else 0L
                    }
                }
                autoRetries = 0
                qualities = PlayerSource.qualities(i)
                quality = PlayerSource.defaultQuality(qualities, preferredHeight())
                audio = PlayerSource.bestAudio(i)
                play(start)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loadingView.visibility = View.GONE
                showError(errorMessage(e))
            }
        }
    }

    private fun play(positionMs: Long) {
        val i = info ?: return
        val source = PlayerSource.build(i, quality, audio)
        loadingView.visibility = View.GONE
        if (source == null) {
            showError("Không tìm thấy luồng phát cho video này.")
            return
        }
        errorBox.visibility = View.GONE
        player.setMediaSource(source)
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.playbackParameters = PlaybackParameters(speed)
        player.playWhenReady = true
    }

    private fun handlePlayerError(error: PlaybackException) {
        val pos = player.currentPosition
        // Thử tự động hạ chất lượng nếu luồng hiện tại lỗi
        val idx = qualities.indexOf(quality)
        if (autoRetries < 3 && idx >= 0 && idx < qualities.size - 1) {
            autoRetries++
            quality = qualities[idx + 1]
            play(pos)
            return
        }
        // Link có thể đã hết hạn -> tải lại thông tin video
        if (autoRetries < 4) {
            autoRetries = 4
            currentUrl?.let { load(it, false, keepPosition = true) }
            return
        }
        showError("Không phát được video.\n(${error.errorCodeName})")
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorBox.visibility = View.VISIBLE
    }

    private fun bindHeader(i: StreamInfo) {
        val title = header.findViewById<TextView>(R.id.title)
        val desc = header.findViewById<TextView>(R.id.desc)
        title.text = i.name
        title.maxLines = 2
        val live = PlayerSource.isLive(i)
        header.findViewById<TextView>(R.id.meta).text = listOf(
            Yt.views(i.viewCount, live),
            if (live) "Đang phát trực tiếp" else Yt.dateText(i.uploadDate, i.textualUploadDate)
        ).filter { it.isNotEmpty() }.joinToString(" • ")
        header.findViewById<TextView>(R.id.channel).text = i.uploaderName
        header.findViewById<TextView>(R.id.subs).text =
            if (i.uploaderSubscriberCount >= 0) Yt.subs(i.uploaderSubscriberCount) else ""
        header.findViewById<ImageView>(R.id.avatar).load(Yt.bestImage(i.uploaderAvatars, 96))
        header.findViewById<TextView>(R.id.btn_like).text = Yt.likes(i.likeCount)
        header.findViewById<View>(R.id.channel_row).setOnClickListener {
            if (!i.uploaderUrl.isNullOrEmpty()) Nav.channel(this, i.uploaderUrl)
        }

        val d = i.description
        val content = d?.content.orEmpty()
        desc.text = when (d?.type) {
            Description.Type.HTML -> HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY)
            else -> content
        }
        desc.visibility = if (content.isBlank()) View.GONE else View.VISIBLE
        desc.maxLines = 3
        desc.movementMethod = null
        val toggle = View.OnClickListener {
            val expand = desc.maxLines == 3
            desc.maxLines = if (expand) Int.MAX_VALUE else 3
            title.maxLines = if (expand) Int.MAX_VALUE else 2
            desc.movementMethod = if (expand) LinkMovementMethod.getInstance() else null
        }
        desc.setOnClickListener(toggle)
        title.setOnClickListener(toggle)
    }

    // ---------------- Cài đặt: chất lượng, tốc độ ----------------

    private fun preferredHeight() =
        getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("quality", 720)

    private fun showSettings() {
        val q = quality?.getResolution() ?: "Tự động"
        val items = arrayOf("Chất lượng: $q", "Tốc độ phát: ${fmtSpeed(speed)}")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (which == 0) showQualities() else showSpeeds()
            }
            .show()
    }

    private fun showQualities() {
        if (qualities.isEmpty()) {
            Toast.makeText(this, "Video này chỉ có một chất lượng", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = qualities.map { it.getResolution() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chất lượng video")
            .setSingleChoiceItems(labels, qualities.indexOf(quality)) { dlg, which ->
                dlg.dismiss()
                val chosen = qualities[which]
                if (chosen != quality) {
                    quality = chosen
                    getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                        .putInt("quality", chosen.height).apply()
                    autoRetries = 0
                    play(player.currentPosition)
                }
            }
            .show()
    }

    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private fun fmtSpeed(s: Float) = if (s == 1f) "Chuẩn" else "${s}x".replace(".0x", "x")

    private fun showSpeeds() {
        val labels = speeds.map { fmtSpeed(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tốc độ phát")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == speed }) { dlg, which ->
                dlg.dismiss()
                speed = speeds[which]
                player.playbackParameters = PlaybackParameters(speed)
            }
            .show()
    }

    private fun share() {
        val url = currentUrl ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, url),
                "Chia sẻ video"
            )
        )
    }

    // ---------------- Toàn màn hình & thu nhỏ ----------------

    private fun toggleFullscreen() {
        if (fullscreen) {
            setFullscreen(false)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            setFullscreen(true)
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            list.visibility = View.GONE
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            list.visibility = View.VISIBLE
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        updatePlayerSize()
    }

    private fun updatePlayerSize() {
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        if (fullscreen || isInPictureInPictureMode) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = 1f
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
        }
        playerBox.layoutParams = lp
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInPictureInPictureMode) return
        // Xoay ngang điện thoại -> tự vào toàn màn hình như YouTube
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && !fullscreen) {
            setFullscreen(true)
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && fullscreen) {
            setFullscreen(false)
        } else {
            updatePlayerSize()
        }
    }

    private fun enterPip() {
        if (info == null) return
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Máy không hỗ trợ chế độ thu nhỏ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        playerView.useController = !isInPip
        overlay.visibility = View.GONE
        list.visibility = if (isInPip || fullscreen) View.GONE else View.VISIBLE
        updatePlayerSize()
    }

    // ---------------- Vòng đời ----------------

    private fun saveCurrentPosition() {
        val url = currentUrl ?: return
        val i = info ?: return
        if (PlayerSource.isLive(i)) return
        History.savePosition(url, player.currentPosition)
    }

    override fun onStop() {
        super.onStop()
        saveCurrentPosition()
        // Đóng cửa sổ thu nhỏ hoặc thoát app -> dừng phát
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        player.release()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
