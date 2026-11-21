package com.tuyen.tubeviet.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
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
    private lateinit var embed: EmbedPlayer
    private var forcedLandscape = false
    private var headerReady = false
    private lateinit var leftCol: View
    private lateinit var infoScroll: View
    private lateinit var infoHolder: ViewGroup
    private lateinit var btnFullscreen: ImageButton
    private val hasPip by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private var autoRetries = 0
    private var speed = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch)

        playerView = findViewById(R.id.player)
        playerBox = findViewById(R.id.player_box)
        list = findViewById(R.id.list)
        loadingView = findViewById(R.id.player_loading)
        embed = EmbedPlayer(findViewById(R.id.web_player))
        errorBox = findViewById(R.id.player_error)
        errorText = findViewById(R.id.player_error_text)
        overlay = findViewById(R.id.overlay_buttons)
        leftCol = findViewById(R.id.left_col)
        infoScroll = findViewById(R.id.info_scroll)
        infoHolder = findViewById(R.id.info_holder)
        btnFullscreen = findViewById(R.id.btn_fullscreen)
        btnFullscreen.setOnClickListener { toggleFullscreen() }

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
        if (!hasPip) {
            // Máy không hỗ trợ chế độ thu nhỏ (Android dưới 8 hoặc nhiều màn hình ô tô)
            findViewById<View>(R.id.btn_pip).visibility = View.GONE
            header.findViewById<View>(R.id.btn_pip2).visibility = View.GONE
        }
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
        embed.stop()
        playerView.visibility = View.VISIBLE
        info = null
        errorBox.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        headerReady = false
        placeHeader()
        adapter.set(emptyList())
        list.scrollToPosition(0)

        loadJob = lifecycleScope.launch {
            try {
                val i = withContext(Dispatchers.IO) { StreamInfo.getInfo(Yt.service, url) }
                info = i
                bindHeader(i)
                headerReady = true
                placeHeader()
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
                if (canFallback(e)) playEmbed(url, resumeAt) else showError(errorMessage(e))
            }
        }
    }

    private fun play(positionMs: Long) {
        val i = info ?: return
        val source = PlayerSource.build(i, quality, audio)
        loadingView.visibility = View.GONE
        if (source == null) {
            currentUrl?.let { playEmbed(it, positionMs) }
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
        // Bộ đọc chính không phát được -> chuyển sang trình phát dự phòng
        currentUrl?.let { playEmbed(it, pos) }
    }

    /** Lỗi do YouTube từ chối bộ đọc chính (không phải video riêng tư/trả phí/bị chặn vùng). */
    private fun canFallback(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is PrivateContentException, is PaidContentException,
                is AgeRestrictedContentException, is GeographicRestrictionException -> return false
                is ContentNotAvailableException -> return true
            }
            t = t.cause
        }
        return false
    }

    private fun playEmbed(url: String, positionMs: Long) {
        val id = try {
            Yt.service.streamLHFactory.getId(url)
        } catch (_: Exception) {
            null
        }
        if (id == null) {
            showError("Không phát được video này.")
            return
        }
        player.stop()
        playerView.visibility = View.INVISIBLE
        errorBox.visibility = View.GONE
        loadingView.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        embed.play(id, if (positionMs > 0) positionMs / 1000 else 0)
        Toast.makeText(this, "Đang phát bằng trình phát dự phòng", Toast.LENGTH_SHORT).show()
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
        if (embed.active) {
            Toast.makeText(
                this, "Trình phát dự phòng tự chọn chất lượng theo mạng", Toast.LENGTH_SHORT
            ).show()
            return
        }
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
            if (forcedLandscape) {
                forcedLandscape = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } else {
            // Chỉ xoay ngang khi đang cầm dọc (điện thoại). Màn hình vốn nằm ngang thì giữ nguyên.
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                forcedLandscape = true
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
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
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
        btnFullscreen.setImageResource(
            if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        btnFullscreen.contentDescription = if (on) "Thu nhỏ" else "Phóng to"
        updatePlayerSize()
    }

    private fun isWide() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Sắp xếp màn hình xem video:
     * - Toàn màn hình / thu nhỏ: chỉ có trình phát.
     * - Màn hình ngang (tablet, màn hình ô tô): trình phát + thông tin bên trái, video liên quan bên phải.
     * - Màn hình dọc (điện thoại): trình phát ở trên, thông tin + video liên quan ở dưới.
     */
    private fun updatePlayerSize() {
        val root = findViewById<LinearLayout>(R.id.root)
        val leftLp = leftCol.layoutParams as LinearLayout.LayoutParams
        val boxLp = playerBox.layoutParams as LinearLayout.LayoutParams
        val listLp = list.layoutParams as LinearLayout.LayoutParams
        val big = fullscreen || inPip()
        val wide = !big && isWide()
        val screenW = resources.displayMetrics.widthPixels
        root.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        when {
            big -> {
                leftLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                leftLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                leftLp.weight = 1f
                boxLp.height = 0
                boxLp.weight = 1f
                infoScroll.visibility = View.GONE
                list.visibility = View.GONE
            }
            wide -> {
                leftLp.width = 0
                leftLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                leftLp.weight = 62f
                boxLp.height = (screenW * 0.62f * 9 / 16).toInt()
                boxLp.weight = 0f
                listLp.width = 0
                listLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                listLp.weight = 38f
                infoScroll.visibility = View.VISIBLE
                list.visibility = View.VISIBLE
            }
            else -> {
                leftLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                leftLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                leftLp.weight = 0f
                boxLp.height = screenW * 9 / 16
                boxLp.weight = 0f
                listLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                listLp.height = 0
                listLp.weight = 1f
                infoScroll.visibility = View.GONE
                list.visibility = View.VISIBLE
            }
        }
        leftCol.layoutParams = leftLp
        playerBox.layoutParams = boxLp
        list.layoutParams = listLp
        placeHeader()
    }

    /** Đặt phần thông tin video vào đúng chỗ theo kiểu màn hình. */
    private fun placeHeader() {
        if (!headerReady) {
            adapter.header = null
            infoHolder.removeAllViews()
            return
        }
        if (isWide() && !fullscreen) {
            if (header.parent !== infoHolder) {
                adapter.header = null
                (header.parent as? ViewGroup)?.removeView(header)
                infoHolder.addView(header)
                infoScroll.scrollTo(0, 0)
            }
        } else if (adapter.header == null) {
            infoHolder.removeAllViews()
            adapter.header = header
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (inPip()) return
        // Xoay ngang điện thoại -> tự vào toàn màn hình như YouTube
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && !fullscreen) {
            setFullscreen(true)
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && fullscreen) {
            setFullscreen(false)
        } else {
            updatePlayerSize()
        }
    }

    private fun inPip() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    private fun enterPip() {
        if (info == null || !hasPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        embed.pause()
    }

    override fun onStart() {
        super.onStart()
        embed.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        player.release()
        embed.destroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
