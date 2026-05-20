package com.lectormae.ui.reader

import android.content.Context
import android.net.Uri
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {

    data class Chapter(val title: String, val file: File)
    data class TocItem(val title: String, val chapterIndex: Int, val anchor: String?, val depth: Int)
    data class ParsedEpub(
        val title: String,
        val author: String,
        val chapters: List<Chapter>,
        val toc: List<TocItem>
    )

    fun parse(context: Context, filePath: String, cacheDir: File): ParsedEpub? {
        val destDir = File(cacheDir, "epub_" + filePath.hashCode().toString(16).takeLast(8))
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
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("rootfile").item(0)
            ?.attributes?.getNamedItem("full-path")?.nodeValue
    }.getOrNull()

    private fun parseOpf(opfFile: File, opfDir: File): ParsedEpub? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(opfFile)

        fun tagText(tag: String) = doc.getElementsByTagName(tag).item(0)?.textContent
        val title  = tagText("dc:title")   ?: tagText("title")   ?: "Sin título"
        val author = tagText("dc:creator") ?: tagText("creator") ?: "—"

        // Manifest: id -> (href, mediaType, properties)
        data class MItem(val href: String, val media: String, val props: String)
        val manifest = mutableMapOf<String, MItem>()
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val a = items.item(i).attributes
            val id    = a.getNamedItem("id")?.nodeValue        ?: continue
            val href  = a.getNamedItem("href")?.nodeValue       ?: continue
            val media = a.getNamedItem("media-type")?.nodeValue ?: ""
            val props = a.getNamedItem("properties")?.nodeValue ?: ""
            manifest[id] = MItem(href, media, props)
        }

        // Spine → chapter list
        val chapters = mutableListOf<Chapter>()
        val itemrefs = doc.getElementsByTagName("itemref")
        for (i in 0 until itemrefs.length) {
            val idref = itemrefs.item(i).attributes.getNamedItem("idref")?.nodeValue ?: continue
            val item  = manifest[idref] ?: continue
            val isHtml = item.media.contains("html") || item.href.run {
                endsWith(".html", true) || endsWith(".xhtml", true) || endsWith(".htm", true)
            }
            if (!isHtml) continue
            val file = File(opfDir, item.href)
            if (file.exists()) chapters.add(Chapter("Capítulo ${chapters.size + 1}", file))
        }

        // TOC: EPUB3 nav (properties="nav") > EPUB2 NCX (spine toc attr) > default
        val navHref = manifest.values.find { it.props.contains("nav") }?.href
        val ncxId   = doc.getElementsByTagName("spine").item(0)?.attributes?.getNamedItem("toc")?.nodeValue
        val ncxHref = ncxId?.let { manifest[it]?.href }

        val toc = (navHref?.let { parseTocNav(File(opfDir, it), opfDir, chapters) }?.takeIf { it.isNotEmpty() })
            ?: (ncxHref?.let { parseTocNcx(File(opfDir, it), opfDir, chapters) }?.takeIf { it.isNotEmpty() })
            ?: defaultToc(chapters)

        ParsedEpub(title, author, chapters, toc)
    }.getOrNull()

    private fun defaultToc(chapters: List<Chapter>) =
        chapters.mapIndexed { i, ch -> TocItem(ch.title, i, null, 0) }

    private fun chapterIndex(href: String, opfDir: File, chapters: List<Chapter>): Int {
        val clean  = href.substringBefore('#')
        val target = runCatching { File(opfDir, clean).canonicalPath }.getOrNull() ?: return -1
        return chapters.indexOfFirst { runCatching { it.file.canonicalPath }.getOrNull() == target }
    }

    // ── EPUB2 NCX ────────────────────────────────────────────────────────────

    private fun parseTocNcx(file: File, opfDir: File, chapters: List<Chapter>): List<TocItem> {
        if (!file.exists()) return emptyList()
        val result = mutableListOf<TocItem>()

        fun kids(n: Node) = (0 until n.childNodes.length).map { n.childNodes.item(it) }

        fun parsePoints(parent: Node, depth: Int) {
            kids(parent).filter { it.nodeName == "navPoint" }.forEach { point ->
                val label = kids(point).find { it.nodeName == "navLabel" }
                    ?.let { kids(it).find { n -> n.nodeName == "text" }?.textContent?.trim() }
                    ?: return@forEach
                val src = kids(point).find { it.nodeName == "content" }
                    ?.attributes?.getNamedItem("src")?.nodeValue ?: return@forEach
                val idx = chapterIndex(src, opfDir, chapters)
                if (idx >= 0) result.add(TocItem(label, idx, src.substringAfter('#').ifEmpty { null }, depth))
                parsePoints(point, depth + 1)
            }
        }

        runCatching {
            val navMap = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("navMap").item(0) ?: return emptyList()
            parsePoints(navMap, 0)
        }
        return result
    }

    // ── EPUB3 nav.xhtml ──────────────────────────────────────────────────────

    private fun parseTocNav(file: File, opfDir: File, chapters: List<Chapter>): List<TocItem> {
        if (!file.exists()) return emptyList()
        val result = mutableListOf<TocItem>()

        fun kids(n: Node) = (0 until n.childNodes.length).map { n.childNodes.item(it) }

        fun parseOl(ol: Node, depth: Int) {
            kids(ol).filter { it.nodeName == "li" }.forEach { li ->
                val a      = kids(li).find { it.nodeName == "a" }
                val title  = a?.textContent?.trim()
                    ?: kids(li).find { it.nodeName == "span" }?.textContent?.trim()
                    ?: return@forEach
                val href = a?.attributes?.getNamedItem("href")?.nodeValue
                if (href != null) {
                    val idx = chapterIndex(href, opfDir, chapters)
                    if (idx >= 0) result.add(TocItem(title, idx, href.substringAfter('#').ifEmpty { null }, depth))
                }
                kids(li).find { it.nodeName == "ol" }?.let { parseOl(it, depth + 1) }
            }
        }

        runCatching {
            val fac = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = fac.newDocumentBuilder().parse(file)
            val navs = doc.getElementsByTagName("nav")
            val toc  = (0 until navs.length).map { navs.item(it) }.find { nav ->
                nav.attributes?.getNamedItem("epub:type")?.nodeValue == "toc" ||
                nav.attributes?.getNamedItemNS("http://www.idpf.org/2007/ops", "type")?.nodeValue == "toc"
            } ?: return emptyList()
            val ol = kids(toc).find { it.nodeName == "ol" } ?: return emptyList()
            parseOl(ol, 0)
        }
        return result
    }
}
