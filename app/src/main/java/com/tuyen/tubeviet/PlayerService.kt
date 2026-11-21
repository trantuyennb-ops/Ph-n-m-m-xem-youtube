package com.tuyen.tubeviet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.tuyen.tubeviet.ui.WatchActivity
import kotlin.math.abs

/**
 * Chạy video khi rời khỏi app (bấm Home / mở ứng dụng khác):
 * - POPUP: cửa sổ video nổi trên các ứng dụng khác, kéo đi được, chạm 2 lần để phóng to/thu nhỏ.
 * - BACKGROUND: chỉ phát tiếng (nghe nhạc), điều khiển qua thanh thông báo.
 */
class PlayerService : Service() {

    private var popupRoot: View? = null
    private var popupView: StyledPlayerView? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var bigPopup = false
    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (running) updateNotification()
            popupRoot?.findViewById<ImageButton>(R.id.popup_play)
                ?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_POPUP -> {
                startFg()
                showPopup()
            }
            ACTION_BACKGROUND -> {
                startFg()
                removePopup()
            }
            ACTION_TOGGLE -> PlayerHolder.peek()?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_CLOSE -> {
                PlayerHolder.peek()?.pause()
                shutdown()
            }
            ACTION_HANDBACK -> shutdown()
        }
        return START_NOT_STICKY
    }

    // ---------------- Thông báo + dịch vụ chạy nền ----------------

    private fun startFg() {
        val p = PlayerHolder.peek() ?: run { stopSelf(); return }
        if (!running) {
            p.addListener(listener)
            running = true
        }
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Đang phát video", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun pending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, PlayerService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 10,
        Intent(this, WatchActivity::class.java)
            .putExtra(WatchActivity.EXTRA_URL, PlayerHolder.url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val playing = PlayerHolder.peek()?.isPlaying == true
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(PlayerHolder.title.ifEmpty { "TubeViet" })
            .setContentText(if (playing) "Đang phát" else "Đã dừng")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Dừng" else "Phát",
                pending(ACTION_TOGGLE, 1)
            )
            .addAction(R.drawable.ic_close, "Tắt", pending(ACTION_CLOSE, 2))
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    // ---------------- Cửa sổ nổi ----------------

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showPopup() {
        if (popupRoot != null) return
        val p = PlayerHolder.peek() ?: return
        val ctx = ContextThemeWrapper(this, R.style.AppTheme)
        val root = android.view.LayoutInflater.from(ctx).inflate(R.layout.popup_player, null)
        val pv = root.findViewById<StyledPlayerView>(R.id.popup_video)
        pv.useController = false
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.player = p
        popupView = pv

        val w = popupWidth(false)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            w, w * 9 / 16, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val dm = resources.displayMetrics
        lp.x = dm.widthPixels - w - (16 * dm.density).toInt()
        lp.y = dm.heightPixels - w * 9 / 16 - (96 * dm.density).toInt()
        popupParams = lp

        val controls = root.findViewById<View>(R.id.popup_controls)
        val playBtn = root.findViewById<ImageButton>(R.id.popup_play)
        playBtn.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.setOnClickListener { if (p.isPlaying) p.pause() else p.play() }
        root.findViewById<View>(R.id.popup_close).setOnClickListener {
            p.pause()
            shutdown()
        }
        root.findViewById<View>(R.id.popup_expand).setOnClickListener {
            try {
                openAppIntent().send()
            } catch (_: Exception) {
            }
        }
        val hide = Runnable { controls.visibility = View.GONE }

        // Kéo để di chuyển, chạm 1 lần hiện nút, chạm 2 lần phóng to/thu nhỏ
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var lastTap = 0L
        root.findViewById<View>(R.id.popup_touch).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || abs(dx) > 12 || abs(dy) > 12) {
                        moved = true
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 300) {
                        togglePopupSize()
                        lastTap = 0
                    } else {
                        lastTap = now
                        controls.visibility = View.VISIBLE
                        controls.removeCallbacks(hide)
                        controls.postDelayed(hide, 3000)
                    }
                }
            }
            true
        }

        try {
            wm.addView(root, lp)
            popupRoot = root
            controls.visibility = View.VISIBLE
            controls.postDelayed(hide, 3000)
        } catch (e: Exception) {
            // Không có quyền hiển thị trên ứng dụng khác -> vẫn phát tiếng ở nền
            pv.player = null
            popupView = null
        }
    }

    private fun popupWidth(big: Boolean): Int {
        val dm = resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        return if (big) (shortSide * 0.95f).toInt() else (shortSide * 0.55f).toInt()
    }

    private fun togglePopupSize() {
        val root = popupRoot ?: return
        val lp = popupParams ?: return
        bigPopup = !bigPopup
        lp.width = popupWidth(bigPopup)
        lp.height = lp.width * 9 / 16
        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    private fun removePopup() {
        popupView?.player = null
        popupView = null
        popupRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        popupRoot = null
    }

    private fun shutdown() {
        removePopup()
        PlayerHolder.peek()?.removeListener(listener)
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        // Màn hình xem video đã đóng -> giải phóng trình phát
        if (!PlayerHolder.activityAlive) PlayerHolder.release()
    }

    override fun onDestroy() {
        removePopup()
        PlayerHolder.peek()?.removeListener(listener)
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42
        const val ACTION_POPUP = "popup"
        const val ACTION_BACKGROUND = "background"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_CLOSE = "close"
        const val ACTION_HANDBACK = "handback"

        /** Đang chạy cửa sổ nổi / phát nền. */
        @Volatile
        var running = false
            private set

        fun canPopup(ctx: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

        fun start(ctx: Context, action: String) {
            val i = Intent(ctx, PlayerService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
            }
        }

        /** Quay lại app: tắt cửa sổ nổi / phát nền, trả video về màn hình chính. */
        fun handBack(ctx: Context) {
            if (!running) return
            try {
                ctx.startService(Intent(ctx, PlayerService::class.java).setAction(ACTION_HANDBACK))
            } catch (_: Exception) {
            }
        }
    }
}
