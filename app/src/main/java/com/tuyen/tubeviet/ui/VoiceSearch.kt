package com.tuyen.tubeviet.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Tìm kiếm bằng giọng nói (tiếng Việt).
 * Hiện cửa sổ "Đang nghe…" với chữ hiện ngay khi nói, và nút "Tìm ngay" để tìm luôn
 * mà không phải đợi máy tự nhận ra là đã nói xong.
 * Nếu máy không cho dùng trực tiếp bộ nhận giọng nói thì dùng màn hình nhận giọng nói của hệ thống.
 */
class VoiceSearch(private val activity: AppCompatActivity, private val onText: (String) -> Unit) {

    private var recognizer: SpeechRecognizer? = null
    private var dialog: AlertDialog? = null
    private var heard = ""
    private var done = false

    // Cách dự phòng: màn hình nhận giọng nói của hệ thống
    private val systemLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) {
                val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) onText(text)
            }
        }

    private val micPermission: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) listen() else startSystem()
        }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            startSystem()
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói nội dung cần tìm…")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private fun startSystem() {
        try {
            systemLauncher.launch(recognizerIntent())
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "Máy chưa có bộ nhận giọng nói. Hãy cài ứng dụng Google từ CH Play rồi thử lại.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun listen() {
        heard = ""
        done = false
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(activity)
        } catch (_: Exception) {
            null
        }
        if (sr == null) {
            startSystem()
            return
        }
        recognizer = sr

        val text = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            minHeight = (90 * resources.displayMetrics.density).toInt()
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setText("Hãy nói…")
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("🎤 Đang nghe…")
            .setView(text)
            .setPositiveButton("Tìm ngay", null)
            .setNegativeButton("Hủy") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .create()
        dialog = dlg
        dlg.show()
        // Bấm "Tìm ngay": tìm luôn với chữ đã nghe được, không đợi
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (heard.isNotBlank()) {
                finish(heard)
            } else {
                // Chưa có chữ nào: dừng nghe để lấy kết quả ngay
                text.text = "Đang nhận dạng…"
                recognizer?.stopListening()
            }
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                text.text = "Hãy nói…"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                if (!p.isNullOrEmpty()) {
                    heard = p
                    text.text = p
                }
            }

            override fun onResults(results: Bundle?) {
                val r = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                val result = if (!r.isNullOrEmpty()) r else heard
                if (result.isNotBlank()) finish(result) else {
                    text.text = "Không nghe rõ, bấm Hủy rồi thử lại."
                }
            }

            override fun onError(error: Int) {
                if (done) return
                if (heard.isNotBlank()) {
                    finish(heard)
                    return
                }
                text.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Không nghe rõ, bấm Hủy rồi thử lại."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Lỗi mạng khi nhận giọng nói."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Chưa cho phép dùng micro."
                    else -> "Không nhận được giọng nói (mã $error)."
                }
            }
        })

        val intent = recognizerIntent()
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        try {
            sr.startListening(intent)
        } catch (_: Exception) {
            cleanup()
            startSystem()
        }
    }

    private fun finish(q: String) {
        if (done) return
        done = true
        cleanup()
        onText(q)
    }

    private fun cancel() {
        done = true
        cleanup()
    }

    private fun cleanup() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
    }
}
