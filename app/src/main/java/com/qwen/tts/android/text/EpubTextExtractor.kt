package com.qwen.tts.android.text

import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * EPUB (.epub) -> plain text.
 *
 * EPUB is a zip archive. We:
 *  1. Read META-INF/container.xml to find the path of the .opf package file.
 *  2. Parse the .opf manifest (id -> href) and spine (reading order of ids).
 *  3. For each spine item, load its XHTML and strip tags to get readable text,
 *     in the correct reading order.
 *
 * Requires: org.jsoup:jsoup (for robust HTML stripping) and xmlpull (bundled with Android).
 */
object EpubTextExtractor {

    fun extract(input: InputStream): String {
        val entries = readZipEntries(input)

        val containerXml = entries["META-INF/container.xml"]
            ?: error("Invalid EPUB: missing META-INF/container.xml")
        val opfPath = findOpfPath(String(containerXml, Charsets.UTF_8))

        val opfBytes = entries[opfPath] ?: error("Invalid EPUB: missing OPF at $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val (manifest, spineIds) = parseOpf(String(opfBytes, Charsets.UTF_8))

        val sb = StringBuilder()
        for (id in spineIds) {
            val href = manifest[id] ?: continue
            val fullPath = normalizePath(if (opfDir.isEmpty()) href else "$opfDir/$href")
            val html = entries[fullPath] ?: continue
            val text = Jsoup.parse(String(html, Charsets.UTF_8)).let { doc ->
                doc.select("script, style").remove()
                doc.body()?.wholeText()?.trim() ?: ""
            }
            if (text.isNotBlank()) {
                sb.append(text)
                sb.append("\n\n")
            }
        }
        return sb.toString().trim()
    }

    private fun readZipEntries(input: InputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val buffer = ByteArrayOutputStream()
                    zip.copyTo(buffer)
                    map[entry.name] = buffer.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return map
    }

    private fun findOpfPath(containerXml: String): String {
        val parser = newParser(containerXml)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
                    ?: error("container.xml missing full-path")
            }
            eventType = parser.next()
        }
        error("container.xml missing <rootfile>")
    }

    /** Returns (manifest id->href map, ordered list of spine idrefs). */
    private fun parseOpf(opfXml: String): Pair<Map<String, String>, List<String>> {
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        val parser = newParser(opfXml)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                        if (id != null && href != null &&
                            (mediaType.contains("html") || mediaType.contains("xml"))
                        ) {
                            manifest[id] = href
                        }
                    }
                    "itemref" -> {
                        parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                    }
                }
            }
            eventType = parser.next()
        }
        return manifest to spine
    }

    private fun newParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        return parser
    }

    private fun normalizePath(path: String): String {
        // Resolve "../" segments that can appear in OPF-relative hrefs.
        val parts = path.split('/')
        val stack = mutableListOf<String>()
        for (p in parts) {
            when (p) {
                "..", "" -> if (p == "..") stack.removeLastOrNull() else Unit
                "." -> Unit
                else -> stack.add(p)
            }
        }
        return stack.joinToString("/")
    }
}
