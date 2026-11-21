package com.tuyen.tubeviet.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Tìm kiếm bằng giọng nói (tiếng Việt), dùng bộ nhận giọng nói có sẵn trên máy. */
class VoiceSearch(private val activity: AppCompatActivity, onText: (String) -> Unit) {
    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) {
                val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) onText(text)
            }
        }

    fun start() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói nội dung cần tìm…")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            launcher.launch(i)
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "Máy chưa có bộ nhận giọng nói. Hãy cài ứng dụng Google từ CH Play rồi thử lại.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
