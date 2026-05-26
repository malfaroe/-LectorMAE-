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
import com.lectormae.data.AppDatabase
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
        b.btnPrev.setOnClickListener   { b.webView.evaluateJavascript("lmPrev()", null) }
        b.btnNext.setOnClickListener   { b.webView.evaluateJavascript("lmNext()", null) }

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reader, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_toc   -> { showToc(); true }
        android.R.id.home -> { finish(); true }
        else              -> super.onOptionsItemSelected(item)
    }

    private fun saveProgress() {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(this@ReaderActivity).bookDao()
                .updateProgress(bookId, currentChapter, currentPage, System.currentTimeMillis())
        }
    }

    private suspend fun loadPosition(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val book = AppDatabase.get(this@ReaderActivity).bookDao().getById(bookId)
        val ch   = (book?.lastChapter ?: 0).coerceIn(0, maxOf(0, chapters.size - 1))
        Pair(ch, book?.lastPage ?: 0)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        b.webView.settings.apply {
            javaScriptEnabled   = true
            builtInZoomControls = false
            setSupportZoom(false)
            allowFileAccess     = true
            textZoom            = 100
        }
        b.webView.overScrollMode               = View.OVER_SCROLL_NEVER
        b.webView.isHorizontalScrollBarEnabled = false
        b.webView.isVerticalScrollBarEnabled   = false
        b.webView.setBackgroundColor(0xFF121212.toInt())
        b.webView.addJavascriptInterface(JsBridge(), "Android")
        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
    }

    private fun setupTapNavigation() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = b.webView.width
                when {
                    e.x < w * 0.35f -> b.webView.evaluateJavascript("lmPrev()", null)
                    e.x > w * 0.65f -> b.webView.evaluateJavascript("lmNext()", null)
                }
                return true
            }
        })
        b.webView.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
    }

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

    private fun buildHtml(original: String, size: Int, initialPage: Int, anchor: String?): String {
        val anchorJs = if (anchor != null) "'$anchor'" else "null"

        val style = """
            <style>
              html,body{margin:0!important;padding:0!important;background:#121212!important;overflow:hidden!important;}
              body{color:#E0E0E0!important;font-size:${size}px!important;font-family:Georgia,serif!important;line-height:1.75!important;padding:0 16px!important;box-sizing:border-box!important;}
              img{max-width:100%!important;height:auto!important;}
              a{color:#C8965A!important;}
              h1,h2,h3{color:#FFFFFF!important;}
              table{max-width:100%!important;}
            </style>""".trimIndent()

        // Readium approach: CSS columns en body, documento.documentElement.scrollLeft
        // para navegar. En Chromium/WebView funciona aunque overflow:hidden esté activo.
        val script = """
            <script>
            (function(){
              var _p=0,_t=1,_vw=0,_scroller=null;
              function lmGoPage(n){
                _p=Math.max(0,Math.min(n,_t-1));
                _scroller.scrollLeft=_p*_vw;
                Android.onPageInfo(_p,_t);
              }
              function lmNext(){if(_p<_t-1)lmGoPage(_p+1);else Android.onNextChapter();}
              function lmPrev(){if(_p>0)lmGoPage(_p-1);else Android.onPrevChapter();}
              function lmGoToAnchor(id){
                var el=document.getElementById(id);if(!el)return;
                var x=el.getBoundingClientRect().left+_scroller.scrollLeft;
                lmGoPage(Math.max(0,Math.min(Math.round(x/_vw),_t-1)));
              }
              function lmInit(ip,anchor){
                _vw=Math.floor(window.innerWidth);
                var vh=Math.floor(window.innerHeight);
                _scroller=document.scrollingElement||document.documentElement;
                document.body.style.height=vh+'px';
                document.body.style.columnWidth=_vw+'px';
                document.body.style.columnGap='0';
                document.body.style.columnFill='auto';
                document.body.style.overflow='hidden';
                document.documentElement.style.overflow='hidden';
                var lastW=0,tries=0;
                function check(){
                  var sw=document.documentElement.scrollWidth;
                  if(sw>0&&(sw===lastW||tries>=10)){
                    _t=Math.max(1,Math.round(sw/_vw));
                    var start=ip<0?_t-1:Math.min(Math.max(ip,0),_t-1);
                    if(anchor){var el=document.getElementById(anchor);if(el){var x=el.getBoundingClientRect().left;start=Math.max(0,Math.min(Math.round(x/_vw),_t-1));}}
                    lmGoPage(start);
                  }else{lastW=sw;tries++;setTimeout(check,100);}
                }
                setTimeout(check,100);
              }
              window.lmNext=lmNext;window.lmPrev=lmPrev;window.lmGoPage=lmGoPage;window.lmGoToAnchor=lmGoToAnchor;
              window.addEventListener('load',function(){setTimeout(function(){lmInit($initialPage,$anchorJs);},50);});
            })();
            </script>""".trimIndent()

        val strippedHtml = original.replace(
            Regex("<meta[^>]+name=[\"']viewport[\"'][^>]*/?>", RegexOption.IGNORE_CASE), ""
        )
        val viewport = """<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">"""
        val inject = viewport + style + script
        return if (strippedHtml.contains("</head>", ignoreCase = true))
            strippedHtml.replace("</head>", "$inject</head>", ignoreCase = true)
        else "$inject$strippedHtml"
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onPageInfo(page: Int, total: Int) = runOnUiThread {
            currentPage = page
            saveProgress()
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
