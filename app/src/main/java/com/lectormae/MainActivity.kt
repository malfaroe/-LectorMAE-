package com.lectormae

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lectormae.data.AppDatabase
import com.lectormae.databinding.ActivityMainBinding
import com.lectormae.ui.library.LibraryFragment
import com.lectormae.ui.reader.ReaderActivity
import com.lectormae.util.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, LibraryFragment())
                .commit()
        }

        // Abierto desde gestor de archivos / otra app
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            handleViewIntent(intent)
        }
    }

    // Llamado cuando la app ya está abierta (launchMode singleTop)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            handleViewIntent(intent)
        }
    }

    private fun handleViewIntent(intent: Intent) {
        val uri = intent.data ?: return

        // Verificar que sea EPUB por extensión como fallback al MIME type
        val mime = intent.type ?: ""
        val uriStr = uri.toString().lowercase()
        val isEpub = mime.contains("epub") || uriStr.endsWith(".epub")
        if (!isEpub && !mime.contains("octet-stream") && mime.isNotEmpty()) {
            Toast.makeText(this, "Formato no soportado", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val book = withContext(Dispatchers.IO) {
                FileScanner.fromUri(applicationContext, uri)
            }
            if (book == null) {
                Toast.makeText(this@MainActivity, "No se pudo importar el archivo", Toast.LENGTH_LONG).show()
                return@launch
            }

            // Guardar en biblioteca (IGNORE si ya existe)
            withContext(Dispatchers.IO) {
                AppDatabase.get(applicationContext).bookDao().insert(book)
            }

            // Abrir lector directamente
            startActivity(Intent(this@MainActivity, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_FILE_PATH, book.filePath)
                putExtra(ReaderActivity.EXTRA_TITLE, book.title)
                putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
            })
        }
    }
}
