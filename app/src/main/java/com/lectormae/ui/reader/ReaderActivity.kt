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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lectormae.databinding.ActivityReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderActivity : AppCompatActivity() {

    private lateinit var b: ActivityReaderBinding
    private var chapters  = listOf<EpubParser.Chapter>()
    private var currentChapter = 0
    private var fontSize   = 18

    private val prefs by lazy { getSharedPreferences("reader_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)

        fontSize = prefs.getInt("font_size", 18)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        val filePath  = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
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
            chapters = parsed.chapters
            loadChapter(savedInstanceState?.getInt(KEY_CHAPTER) ?: 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CHAPTER, currentChapter)
    }

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
        b.webView.setOnTouchListener { _, event -> gesture.onTouchEvent(event); false }
    }

    private fun navigateNext() = b.webView.evaluateJavascript("lmNext()", null)
    private fun navigatePrev() = b.webView.evaluateJavascript("lmPrev()", null)

    private fun loadChapter(index: Int, goToLast: Boolean = false) {
        if (index < 0 || index >= chapters.size) return
        currentChapter = index
        val file = chapters[index].file
        val html = buildHtml(file.readText(), fontSize, goToLast)
        b.webView.loadDataWithBaseURL("file://${file.parent}/", html, "text/html", "UTF-8", null)
    }

    private fun changeFontSize(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(12, 30)
        prefs.edit().putInt("font_size", fontSize).apply()
        loadChapter(currentChapter)
    }

    private fun buildHtml(original: String, size: Int, goToLast: Boolean): String {
        val style = """
            <style id="_lm_style">
              html {
                -webkit-column-width: 100vw; column-width: 100vw;
                -webkit-column-gap: 0px;     column-gap: 0px;
                height: 100%; overflow: hidden;
              }
              body {
                background: #121212 !important;
                color: #E0E0E0 !important;
                font-size: ${size}px !important;
                font-family: Georgia, serif !important;
                line-height: 1.75 !important;
                margin: 0 !important;
                padding: 12px 16px !important;
                box-sizing: border-box !important;
                overflow: hidden !important;
                height: 100% !important;
              }
              a            { color: #C8965A !important; }
              img          { max-width: 100% !important; height: auto !important; }
              h1, h2, h3   { color: #FFFFFF !important; }
            </style>""".trimIndent()

        val script = """
            <script id="_lm_script">
              var _p = 0, _t = 1;
              function lmInit() {
                _t = Math.max(1, Math.ceil(document.documentElement.scrollWidth / window.innerWidth));
                _p = ${if (goToLast) "_t - 1" else "0"};
                document.documentElement.scrollLeft = _p * window.innerWidth;
                Android.onPageInfo(_p, _t);
              }
              function lmNext() {
                if (_p < _t - 1) {
                  _p++; document.documentElement.scrollLeft = _p * window.innerWidth;
                  Android.onPageInfo(_p, _t);
                } else { Android.onNextChapter(); }
              }
              function lmPrev() {
                if (_p > 0) {
                  _p--; document.documentElement.scrollLeft = _p * window.innerWidth;
                  Android.onPageInfo(_p, _t);
                } else { Android.onPrevChapter(); }
              }
              window.addEventListener('load', function() { setTimeout(lmInit, 150); });
            </script>""".trimIndent()

        val injection = style + script
        return if (original.contains("</head>", ignoreCase = true))
            original.replace("</head>", "$injection</head>", ignoreCase = true)
        else
            "$injection$original"
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onPageInfo(page: Int, total: Int) = runOnUiThread {
            b.tvChapterCount.text = "Cap ${currentChapter + 1}/${chapters.size}  ·  Pág ${page + 1}/$total"
            b.btnPrev.isEnabled = page > 0 || currentChapter > 0
            b.btnNext.isEnabled = page < total - 1 || currentChapter < chapters.size - 1
        }

        @JavascriptInterface
        fun onNextChapter() = runOnUiThread {
            if (currentChapter < chapters.size - 1) loadChapter(currentChapter + 1)
            else Toast.makeText(this@ReaderActivity, "Fin del libro", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun onPrevChapter() = runOnUiThread {
            if (currentChapter > 0) loadChapter(currentChapter - 1, goToLast = true)
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE     = "extra_title"
        private const val KEY_CHAPTER = "chapter"
    }
}
