package com.tuyen.tubeviet

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.tuyen.tubeviet.newpipe.DownloaderImpl
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Tự cài file APK từ bên trong app (đọc file từ USB hoặc tải từ GitHub rồi cài).
 * Cách này tránh lỗi "phân tích cú pháp gói" khi trình quản lý file của màn hình ô tô
 * không cho trình cài đặt đọc file trên USB.
 */
object ApkInstaller {
    private const val RELEASE_API =
        "https://api.github.com/repos/trantuyennb-ops/Ph-n-m-m-xem-youtube/releases/tags/latest"
    private const val APK_URL =
        "https://github.com/trantuyennb-ops/Ph-n-m-m-xem-youtube/releases/download/latest/TubeViet.apk"

    /** Máy đã cho phép TubeViet cài ứng dụng chưa; nếu chưa thì mở cài đặt để bật. */
    fun ensurePermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (activity.packageManager.canRequestPackageInstalls()) return true
        AlertDialog.Builder(activity)
            .setTitle("Cho phép cài ứng dụng")
            .setMessage(
                "Hãy bật \"Cho phép từ nguồn này\" cho TubeViet ở màn hình tiếp theo, " +
                    "rồi quay lại và chọn lại file."
            )
            .setPositiveButton("Mở cài đặt") { _, _ ->
                try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(activity, "Máy không mở được mục này", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
        return false
    }

    /** Cài file APK người dùng chọn (ví dụ trên USB). Chạy ở luồng nền. */
    fun installFromUri(ctx: Context, uri: Uri) {
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Không đọc được file")
        input.use { install(ctx, it) }
    }

    /** Lấy số bản build mới nhất trên GitHub. Chạy ở luồng nền. */
    fun latestBuild(): Int? {
        val req = okhttp3.Request.Builder().url(RELEASE_API)
            .header("Accept", "application/vnd.github+json").build()
        DownloaderImpl.getInstance().client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val name = JSONObject(r.body.string()).optString("name")
            return Regex("build (\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /** Tải bản mới nhất từ GitHub rồi cài. [progress] nhận phần trăm 0..100. Chạy ở luồng nền. */
    fun downloadAndInstall(ctx: Context, progress: (Int) -> Unit) {
        val req = okhttp3.Request.Builder().url(APK_URL).build()
        val file = File(ctx.cacheDir, "update.apk")
        DownloaderImpl.getInstance().client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Lỗi tải: HTTP ${r.code}")
            val body = r.body
            val total = body.contentLength()
            body.byteStream().use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        }
        file.inputStream().use { install(ctx, it) }
    }

    private fun install(ctx: Context, input: InputStream) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("app.apk", 0, -1).use { out ->
                input.copyTo(out, 64 * 1024)
                session.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags
            )
            session.commit(pending.intentSender)
        }
    }
}

/** Nhận kết quả cài đặt: hiện màn hình xác nhận của hệ thống, hoặc báo lỗi. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (_: Exception) {
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "Cài đặt thành công", Toast.LENGTH_LONG).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(context, "Cài đặt không thành công. $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
