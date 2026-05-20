package com.lectormae.ui.reader

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {

    data class Chapter(val title: String, val file: File)
    data class ParsedEpub(val title: String, val author: String, val chapters: List<Chapter>)

    fun parse(context: Context, filePath: String, cacheDir: File): ParsedEpub? {
        val destDir = File(cacheDir, "epub_" + filePath.hashCode().toString(16).takeLast(8))

        // Re-extract only if directory is empty or missing
        if (!destDir.exists() || destDir.listFiles().isNullOrEmpty()) {
            destDir.deleteRecursively()
            destDir.mkdirs()
            val stream: InputStream? = if (filePath.startsWith("content://"))
                context.contentResolver.openInputStream(Uri.parse(filePath))
            else
                File(filePath).inputStream()
            stream?.use { extractZip(it, destDir) } ?: return null
        }

        return parseEpubDir(destDir)
    }

    private fun extractZip(input: InputStream, destDir: File) {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = File(destDir, entry.name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun parseEpubDir(dir: File): ParsedEpub? {
        val container = File(dir, "META-INF/container.xml")
        if (!container.exists()) return null
        val opfPath = parseContainerXml(container) ?: return null
        val opfFile = File(dir, opfPath)
        if (!opfFile.exists()) return null
        return parseOpf(opfFile, opfFile.parentFile ?: dir)
    }

    private fun parseContainerXml(file: File): String? = runCatching {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        doc.getElementsByTagName("rootfile").item(0)
            ?.attributes?.getNamedItem("full-path")?.nodeValue
    }.getOrNull()

    private fun parseOpf(opfFile: File, opfDir: File): ParsedEpub? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(opfFile)

        fun tagText(tag: String) = doc.getElementsByTagName(tag).item(0)?.textContent
        val title  = tagText("dc:title")  ?: tagText("title")  ?: "Sin título"
        val author = tagText("dc:creator") ?: tagText("creator") ?: "—"

        // Manifest: id -> href (only HTML content)
        val manifest = mutableMapOf<String, String>()
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val attrs = items.item(i).attributes
            val id    = attrs.getNamedItem("id")?.nodeValue   ?: continue
            val href  = attrs.getNamedItem("href")?.nodeValue  ?: continue
            val media = attrs.getNamedItem("media-type")?.nodeValue ?: ""
            if (media.contains("html") || href.endsWith(".html", true)
                || href.endsWith(".xhtml", true) || href.endsWith(".htm", true)) {
                manifest[id] = href
            }
        }

        // Spine → ordered chapter list
        val chapters = mutableListOf<Chapter>()
        val itemrefs = doc.getElementsByTagName("itemref")
        for (i in 0 until itemrefs.length) {
            val idref = itemrefs.item(i).attributes.getNamedItem("idref")?.nodeValue ?: continue
            val href  = manifest[idref] ?: continue
            val file  = File(opfDir, href)
            if (file.exists()) chapters.add(Chapter("Capítulo ${chapters.size + 1}", file))
        }

        ParsedEpub(title, author, chapters)
    }.getOrNull()
}
