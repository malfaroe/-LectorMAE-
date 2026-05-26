package com.lectormae.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lectormae.data.Book
import com.lectormae.ui.reader.EpubParser
import java.io.File
import java.security.MessageDigest

object FileScanner {

    /** Importa un EPUB desde un content URI (selector de archivos SAF). */
    fun fromUri(context: Context, uri: Uri): Book? {
        val resolver = context.contentResolver

        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst()
            c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: return null

        if (!name.endsWith(".epub", ignoreCase = true)) return null

        val destDir = File(context.filesDir, "books").also { it.mkdirs() }
        val dest    = File(destDir, name)
        resolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
        if (!dest.exists()) return null

        val id = md5(dest.absolutePath)

        val parsed = EpubParser.parse(context, dest.absolutePath, context.cacheDir)
        val title  = parsed?.title?.takeIf { it.isNotBlank() && it != "Sin título" }
            ?: name.substringBeforeLast('.')
        val author = parsed?.author?.takeIf { it.isNotBlank() && it != "—" } ?: "—"

        val coverPath = parsed?.coverFile?.let { src ->
            val ext  = src.extension.ifEmpty { "jpg" }
            val dest2 = File(context.filesDir, "covers/$id.$ext").also { it.parentFile?.mkdirs() }
            runCatching { src.copyTo(dest2, overwrite = true) }
            if (dest2.exists()) dest2.absolutePath else null
        }

        return Book(
            id        = id,
            title     = title,
            author    = author,
            filePath  = dest.absolutePath,
            format    = "EPUB",
            coverPath = coverPath,
            fileSize  = dest.length()
        )
    }

    fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
