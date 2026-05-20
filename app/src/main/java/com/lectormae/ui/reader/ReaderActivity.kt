package com.lectormae.ui.reader

import android.os.Bundle
import android.view.View
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
    private var chapters = listOf<EpubParser.Chapter>()
    private var currentChapter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        val filePath  = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        val bookTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Lector"
        supportActionBar?.title = bookTitle

        setupWebView()

        b.btnPrev.setOnClickListener { loadChapter(currentChapter - 1) }
        b.btnNext.setOnClickListener { loadChapter(currentChapter + 1) }

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
            javaScriptEnabled        = false
            builtInZoomControls      = true
            displayZoomControls      = false
            setSupportZoom(true)
            allowFileAccess                = true
            allowFileAccessFromFileURLs    = true
            allowUniversalAccessFromFileURLs = true
        }
        b.webView.setBackgroundColor(0xFF121212.toInt())
        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }
    }

    private fun loadChapter(index: Int) {
        if (index < 0 || index >= chapters.size) return
        currentChapter = index
        val file = chapters[index].file
        // Use loadDataWithBaseURL so relative CSS/images resolve correctly
        val html = file.readText()
        b.webView.loadDataWithBaseURL(
            "file://${file.parent}/",
            injectDarkStyle(html),
            "text/html",
            "UTF-8",
            null
        )
        b.tvChapterCount.text = "${index + 1} / ${chapters.size}"
        b.btnPrev.isEnabled = index > 0
        b.btnNext.isEnabled = index < chapters.size - 1
    }

    private fun injectDarkStyle(html: String): String {
        val style = """
            <style>
              body { background:#121212 !important; color:#E0E0E0 !important;
                     font-family: Georgia, serif; font-size: 18px;
                     line-height: 1.7; padding: 16px; margin: 0; }
              a    { color:#C8965A !important; }
              img  { max-width:100% !important; height:auto !important; }
            </style>
        """.trimIndent()
        return if (html.contains("</head>", ignoreCase = true))
            html.replace("</head>", "$style</head>", ignoreCase = true)
        else
            "$style$html"
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE     = "extra_title"
        private const val KEY_CHAPTER = "chapter"
    }
}
