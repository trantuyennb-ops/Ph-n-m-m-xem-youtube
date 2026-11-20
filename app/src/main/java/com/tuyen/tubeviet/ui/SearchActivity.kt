package com.tuyen.tubeviet.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tuyen.tubeviet.R
import com.tuyen.tubeviet.SearchFeed
import com.tuyen.tubeviet.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class SearchActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var suggestions: RecyclerView
    private lateinit var controller: FeedController
    private lateinit var state: StateView
    private val sugAdapter = SuggestionAdapter()
    private var sugJob: Job? = null
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.input)
        suggestions = findViewById(R.id.suggestions)
        val list = findViewById<RecyclerView>(R.id.list)
        val clear = findViewById<View>(R.id.btn_clear)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        clear.setOnClickListener {
            input.setText("")
            input.requestFocus()
            showKeyboard(true)
        }

        val adapter = ItemAdapter(
            onClick = { Nav.open(this, it) },
            onChannelClick = { Nav.channel(this, it.uploaderUrl) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        state = StateView(this) { if (lastQuery.isNotEmpty()) search(lastQuery) }
        controller = FeedController(lifecycleScope, list, adapter) { loading, error, empty ->
            when {
                loading -> state.loading(true)
                error != null -> state.error(error)
                empty -> state.empty("Không tìm thấy kết quả nào.")
                else -> state.hide()
            }
        }

        suggestions.layoutManager = LinearLayoutManager(this)
        suggestions.adapter = sugAdapter

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                if (input.hasFocus()) loadSuggestions(q)
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) loadSuggestions(input.text.toString())
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                search(input.text.toString())
                true
            } else false
        }

        input.requestFocus()
        loadSuggestions("")
    }

    override fun onBackPressed() {
        if (suggestions.visibility == View.VISIBLE && lastQuery.isNotEmpty()) {
            suggestions.visibility = View.GONE
            input.clearFocus()
            showKeyboard(false)
        } else {
            super.onBackPressed()
        }
    }

    private fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        // Dán link YouTube vào ô tìm kiếm -> mở thẳng video
        if (q.startsWith("http") && Yt.isStreamUrl(q)) {
            Nav.watch(this, q)
            return
        }
        lastQuery = q
        saveQuery(q)
        input.clearFocus()
        input.setText(q)
        input.setSelection(q.length)
        sugJob?.cancel()
        showKeyboard(false)
        suggestions.visibility = View.GONE
        controller.load(SearchFeed(q))
    }

    private fun loadSuggestions(q: String) {
        sugJob?.cancel()
        suggestions.visibility = View.VISIBLE
        val history = recentQueries().filter { q.isEmpty() || it.startsWith(q, true) }.take(8)
        sugAdapter.set(history.map { it to true })
        if (q.isBlank()) return
        sugJob = lifecycleScope.launch {
            delay(250)
            val remote = try {
                withContext(Dispatchers.IO) { Yt.service.suggestionExtractor.suggestionList(q) }
            } catch (e: Exception) {
                emptyList()
            }
            val merged = history.map { it to true } +
                remote.filter { r -> history.none { it.equals(r, true) } }.map { it to false }
            sugAdapter.set(merged)
        }
    }

    private fun showKeyboard(show: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        else imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun prefs() = getSharedPreferences("search", Context.MODE_PRIVATE)

    private fun recentQueries(): List<String> = try {
        val a = JSONArray(prefs().getString("recent", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveQuery(q: String) {
        val list = listOf(q) + recentQueries().filter { !it.equals(q, true) }
        val a = JSONArray()
        list.take(30).forEach { a.put(it) }
        prefs().edit().putString("recent", a.toString()).apply()
    }

    inner class SuggestionAdapter : RecyclerView.Adapter<SuggestionAdapter.VH>() {
        private var data: List<Pair<String, Boolean>> = emptyList()

        fun set(d: List<Pair<String, Boolean>>) {
            data = d
            notifyDataSetChanged()
        }

        override fun getItemCount() = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (text, fromHistory) = data[position]
            holder.text.text = text
            holder.icon.setImageResource(if (fromHistory) R.drawable.ic_history else R.drawable.ic_search)
            holder.itemView.setOnClickListener { search(text) }
            holder.fill.setOnClickListener {
                input.setText(text)
                input.setSelection(text.length)
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(R.id.text)
            val icon: ImageView = v.findViewById(R.id.icon)
            val fill: ImageView = v.findViewById(R.id.fill)
        }
    }
}
