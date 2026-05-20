package com.lectormae.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.lectormae.data.Book
import java.io.File
import java.security.MessageDigest

object FileScanner {

    private const val MIME_EPUB = "application/epub+zip"
    private const val MIME_PDF  = "application/pdf"

    /** Scans device storage via MediaStore. Stores content:// URI as filePath. */
    fun scan(context: Context): List<Book> {
        val books   = mutableListOf<Book>()
        val seen    = mutableSetOf<String>()
        val resolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} IN (?,?)) OR " +
                        "(${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                        " ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?)"
        val args = arrayOf(MIME_EPUB, MIME_PDF, "%.epub", "%.pdf")

        resolver.query(
            MediaStore.Files.getContentUri("external"),
            projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val size = cursor.getLong(sizeIdx)
                val mediaId = cursor.getLong(idIdx)

                val fmt = when {
                    name.endsWith(".epub", ignoreCase = true) -> "EPUB"
                    name.endsWith(".pdf",  ignoreCase = true) -> "PDF"
                    else -> continue
                }

                // Store the MediaStore content URI — works without FileProvider
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"), mediaId
                ).toString()

                val id = md5(contentUri)
                if (seen.add(id)) {
                    books.add(Book(
                        id        = id,
                        title     = name.substringBeforeLast('.'),
                        author    = "—",
                        filePath  = contentUri,
                        format    = fmt,
                        coverPath = null,
                        fileSize  = size
                    ))
                }
            }
        }
        return books
    }

    /** Imports a file from a content URI (file picker). Copies to app internal storage. */
    fun fromUri(context: Context, uri: Uri): Book? {
        val resolver = context.contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst()
            c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: return null

        val fmt = when {
            name.endsWith(".epub", ignoreCase = true) -> "EPUB"
            name.endsWith(".pdf",  ignoreCase = true) -> "PDF"
            else -> return null
        }

        val destDir = File(context.filesDir, "books").also { it.mkdirs() }
        val dest    = File(destDir, name)
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (!dest.exists()) return null

        return Book(
            id        = md5(dest.absolutePath),
            title     = name.substringBeforeLast('.'),
            author    = "—",
            filePath  = dest.absolutePath,   // internal path → FileProvider
            format    = fmt,
            coverPath = null,
            fileSize  = dest.length()
        )
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
