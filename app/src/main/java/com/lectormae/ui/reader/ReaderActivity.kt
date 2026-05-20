package com.lectormae.ui.reader

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
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
import com.lectormae.R
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
                Toast.makeText(this@ReaderActivity, "No se pudo abrir el libro", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            parsedEpub = parsed
            chapters   = parsed.chapters
            val (ch, pg) = loadPosition()
            loadChapter(ch, initialPage = pg)
        }
    }

    // ── Toolbar menu ─────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reader, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_toc    -> { showToc(); true }
        android.R.id.home  -> { finish(); true }
        else               -> super.onOptionsItemSelected(item)
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
        // Consume all touch events to block native WebView scroll
        b.webView.setOnTouchListener { _, event -> gesture.onTouchEvent(event); true }
    }

    private fun navigateNext() = b.webView.evaluateJavascript("lmNext()", null)
    private fun navigatePrev() = b.webView.evaluateJavascript("lmPrev()", null)

    // ── Chapter loading ──────────────────────────────────────────────────────

    /** initialPage: 0=first, -1=last, N=exact; anchor: optional fragment id */
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
        AlertDialog.Builder(this)
            .setTitle("Índice")
            .setItems(toc.map { "  ".repeat(it.depth) + it.title }.toTypedArray()) { _, i ->
                val item = toc[i]
                if (item.chapterIndex == currentChapter && item.anchor != null)
                    b.webView.evaluateJavascript("lmGoToAnchor('${item.anchor}')", null)
                else
                    loadChapter(item.chapterIndex, anchor = item.anchor)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ── HTML / CSS / JS injection ────────────────────────────────────────────

    private fun buildHtml(original: String, size: Int, initialPage: Int, anchor: String?): String {
        val anchorJs = if (anchor != null) "'$anchor'" else "null"

        val style = """
            <style id="_lm">
              html, body {
                margin:0 !important; padding:0 !important;
                width:100% !important; height:100% !important;
                overflow:hidden !important; background:#121212 !important;
              }
              body { color:#E0E0E0 !important; font-size:${size}px !important;
                     font-family:Georgia,serif !important; line-height:1.75 !important; }
              /* Pagination wrapper — columns extend horizontally.
                 column-width + column-gap = 100vw so each page aligns to screen width. */
              #_lm_p {
                height:100vh; box-sizing:border-box; padding:12px 16px;
                -webkit-column-width:calc(100vw - 32px); column-width:calc(100vw - 32px);
                -webkit-column-gap:32px; column-gap:32px; column-fill:auto;
              }
              a        { color:#C8965A !important; }
              img      { max-width:calc(100vw - 32px) !important; height:auto !important; }
              h1,h2,h3 { color:#FFFFFF !important; }
            </style>""".trimIndent()

        // Page count uses an end-marker's getBoundingClientRect().left — avoids
        // the scrollWidth=clientWidth bug that appears when parent has overflow:hidden.
        val script = """
            <script id="_lm_js">
              var _p=0,_t=1;
              function _pg(){ return document.getElementById('_lm_p'); }
              function lmWrap(){
                var p=document.createElement('div'); p.id='_lm_p';
                while(document.body.firstChild) p.appendChild(document.body.firstChild);
                var m=document.createElement('span'); m.id='_lm_end'; p.appendChild(m);
                document.body.appendChild(p);
              }
              function lmMeasure(){
                _pg().style.transform='none';
                var r=document.getElementById('_lm_end').getBoundingClientRect();
                _t=Math.max(1, Math.floor(r.left/window.innerWidth)+1);
              }
              function lmGoPage(n){
                _p=Math.max(0,Math.min(n,_t-1));
                _pg().style.transform='translateX(-'+(_p*window.innerWidth)+'px)';
                Android.onPageInfo(_p,_t);
              }
              function lmInit(){
                lmWrap(); lmMeasure();
                var anchor=$anchorJs, ip=$initialPage;
                var start=ip<0?_t-1:ip;
                if(anchor){
                  var el=document.getElementById(anchor);
                  if(el){ _pg().style.transform='none'; start=Math.floor(el.getBoundingClientRect().left/window.innerWidth); }
                }
                lmGoPage(start);
              }
              function lmNext(){ if(_p<_t-1) lmGoPage(_p+1); else Android.onNextChapter(); }
              function lmPrev(){ if(_p>0) lmGoPage(_p-1); else Android.onPrevChapter(); }
              function lmGoToAnchor(id){
                var el=document.getElementById(id); if(!el) return;
                _pg().style.transform='none';
                lmGoPage(Math.floor(el.getBoundingClientRect().left/window.innerWidth));
              }
              window.addEventListener('load', function(){ setTimeout(lmInit, 250); });
            </script>""".trimIndent()

        val inject = style + script
        return if (original.contains("</head>", ignoreCase = true))
            original.replace("</head>", "$inject</head>", ignoreCase = true)
        else "$inject$original"
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
            if (currentChapter < chapters.size-1) loadChapter(currentChapter+1)
            else Toast.makeText(this@ReaderActivity, "Fin del libro", Toast.LENGTH_SHORT).show()
        }
        @JavascriptInterface
        fun onPrevChapter() = runOnUiThread {
            if (currentChapter > 0) loadChapter(currentChapter-1, initialPage = -1)
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE     = "extra_title"
        const val EXTRA_BOOK_ID   = "extra_book_id"
    }
}
