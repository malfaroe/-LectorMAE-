package com.lectormae.ui.reader

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lectormae.databinding.ActivityReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderActivity : AppCompatActivity() {

    private lateinit var b: ActivityReaderBinding
    private var parsedEpub: EpubParser.ParsedEpub? = null
    private var chapters       = listOf<EpubParser.Chapter>()
    private var currentChapter = 0
    private var currentPage    = 0
    private var fontSize       = 18
    private var bookId         = ""

    private val prefs by lazy { getSharedPreferences("reader_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)

        fontSize = prefs.getInt("font_size", 18)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.menu_reader)
        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_toc) { showToc(); true } else false
        }

        val filePath  = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        bookId        = intent.getStringExtra(EXTRA_BOOK_ID) ?: filePath.hashCode().toString()
        val bookTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Lector"
        supportActionBar?.title = bookTitle

        setupWebView()
        setupTapNavigation()

        b.btnFontDec.setOnClickListener { changeFontSize(-2) }
        b.btnFontInc.setOnClickListener { changeFontSize(+2) }
        b.btnPrev.setOnClickListener   { navigatePrev() }
        b.btnNext.setOnClickListener   { navigateNext() }

        b.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                EpubParser.parse(this@ReaderActivity, filePath, cacheDir)
            }
            b.progressBar.visibility = View.GONE
            if (parsed == null || parsed.chapters.isEmpty()) {
                Toast.makeText(this@ReaderActivity, "No se pudo leer el libro", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            parsedEpub = parsed
            chapters   = parsed.chapters
            val (ch, pg) = loadPosition()
            loadChapter(ch, initialPage = pg)
        }
    }

    // ── Position persistence ─────────────────────────────────────────────────

    private fun savePosition() =
        prefs.edit().putString("pos_$bookId", "$currentChapter:$currentPage").apply()

    private fun loadPosition(): Pair<Int, Int> = runCatching {
        val s = prefs.getString("pos_$bookId", null) ?: return Pair(0, 0)
        val p = s.split(":")
        Pair(p[0].toInt().coerceIn(0, chapters.size - 1), p[1].toInt())
    }.getOrDefault(Pair(0, 0))

    // ── WebView setup ────────────────────────────────────────────────────────

    @Suppress("SetJavaScriptEnabled", "DEPRECATION")
    private fun setupWebView() {
        b.webView.settings.apply {
            javaScriptEnabled                = true
            builtInZoomControls              = false
            setSupportZoom(false)
            allowFileAccess                  = true
            allowFileAccessFromFileURLs      = true
            allowUniversalAccessFromFileURLs = true
        }
        b.webView.setBackgroundColor(0xFF121212.toInt())
        b.webView.addJavascriptInterface(JsBridge(), "Android")
        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }
    }

    private fun setupTapNavigation() {
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = b.webView.width
                when {
                    e.x < w * 0.35f -> navigatePrev()
                    e.x > w * 0.65f -> navigateNext()
                }
                return true
            }
        })
        // Consume all touch events to prevent native WebView scrolling
        b.webView.setOnTouchListener { _, event -> gesture.onTouchEvent(event); true }
    }

    private fun navigateNext() = b.webView.evaluateJavascript("lmNext()", null)
    private fun navigatePrev() = b.webView.evaluateJavascript("lmPrev()", null)

    // ── Chapter loading ──────────────────────────────────────────────────────

    /**
     * @param initialPage  0 = first page, -1 = last page, N = exact page
     * @param anchor       optional fragment id to scroll to
     */
    private fun loadChapter(index: Int, initialPage: Int = 0, anchor: String? = null) {
        if (index !in chapters.indices) return
        currentChapter = index
        currentPage    = 0
        val file = chapters[index].file
        b.webView.loadDataWithBaseURL(
            "file://${file.parent}/",
            buildHtml(file.readText(), fontSize, initialPage, anchor),
            "text/html", "UTF-8", null
        )
    }

    private fun changeFontSize(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(12, 30)
        prefs.edit().putInt("font_size", fontSize).apply()
        loadChapter(currentChapter, initialPage = currentPage)
    }

    // ── TOC ──────────────────────────────────────────────────────────────────

    private fun showToc() {
        val toc = parsedEpub?.toc?.takeIf { it.isNotEmpty() } ?: run {
            Toast.makeText(this, "Sin índice disponible", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = toc.map { "  ".repeat(it.depth) + it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Índice")
            .setItems(labels) { _, i ->
                val item = toc[i]
                if (item.chapterIndex == currentChapter && item.anchor != null) {
                    b.webView.evaluateJavascript("lmGoToAnchor('${item.anchor}')", null)
                } else {
                    loadChapter(item.chapterIndex, anchor = item.anchor)
                }
            }
            .show()
    }

    // ── HTML injection ───────────────────────────────────────────────────────

    private fun buildHtml(original: String, size: Int, initialPage: Int, anchor: String?): String {
        val anchorJs = if (anchor != null) "'$anchor'" else "null"
        val style = """
            <style id="_lm">
              html, body { margin:0 !important; padding:0 !important; background:#121212 !important; }
              body {
                color:#E0E0E0 !important;
                font-size:${size}px !important;
                font-family:Georgia, serif !important;
                line-height:1.75 !important;
                padding:12px 16px !important;
                box-sizing:border-box !important;
                overflow-x:hidden !important;
              }
              a          { color:#C8965A !important; }
              img        { max-width:100% !important; height:auto !important; }
              h1,h2,h3   { color:#FFFFFF !important; }
            </style>""".trimIndent()

        // Vertical scroll pagination — reliable on all Android WebViews.
        // CSS columns were avoided because overflow:hidden makes scrollWidth = clientWidth,
        // causing total pages to always be calculated as 1.
        val script = """
            <script id="_lm_js">
              var _p=0, _t=1, _h=0;
              function lmInit() {
                _h = window.innerHeight;
                var totalH = document.documentElement.scrollHeight;
                _t = Math.max(1, Math.ceil(totalH / _h));
                var anchor = $anchorJs;
                if (anchor) {
                  var el = document.getElementById(anchor);
                  if (el) { _p = Math.floor((el.getBoundingClientRect().top + window.scrollY) / _h); }
                  else    { _p = $initialPage < 0 ? _t-1 : $initialPage; }
                } else {
                  _p = $initialPage < 0 ? _t-1 : $initialPage;
                }
                _p = Math.max(0, Math.min(_p, _t-1));
                window.scrollTo(0, _p * _h);
                Android.onPageInfo(_p, _t);
              }
              function lmNext() {
                if (_p < _t-1) { _p++; window.scrollTo(0, _p*_h); Android.onPageInfo(_p,_t); }
                else           { Android.onNextChapter(); }
              }
              function lmPrev() {
                if (_p > 0)    { _p--; window.scrollTo(0, _p*_h); Android.onPageInfo(_p,_t); }
                else           { Android.onPrevChapter(); }
              }
              function lmGoToAnchor(id) {
                var el = document.getElementById(id);
                if (!el) return;
                _p = Math.floor((el.getBoundingClientRect().top + window.scrollY) / _h);
                window.scrollTo(0, _p*_h);
                Android.onPageInfo(_p,_t);
              }
              window.addEventListener('load', function() { setTimeout(lmInit, 250); });
            </script>""".trimIndent()

        val inject = style + script
        return if (original.contains("</head>", ignoreCase = true))
            original.replace("</head>", "$inject</head>", ignoreCase = true)
        else
            "$inject$original"
    }

    // ── JS bridge ────────────────────────────────────────────────────────────

    inner class JsBridge {
        @JavascriptInterface
        fun onPageInfo(page: Int, total: Int) = runOnUiThread {
            currentPage = page
            savePosition()
            b.tvChapterCount.text = "Cap ${currentChapter+1}/${chapters.size}  ·  Pág ${page+1}/$total"
            b.btnPrev.isEnabled = page > 0 || currentChapter > 0
            b.btnNext.isEnabled = page < total-1 || currentChapter < chapters.size-1
        }

        @JavascriptInterface
        fun onNextChapter() = runOnUiThread {
            if (currentChapter < chapters.size - 1) loadChapter(currentChapter + 1)
            else Toast.makeText(this@ReaderActivity, "Fin del libro", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun onPrevChapter() = runOnUiThread {
            if (currentChapter > 0) loadChapter(currentChapter - 1, initialPage = -1)
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE     = "extra_title"
        const val EXTRA_BOOK_ID   = "extra_book_id"
    }
}
