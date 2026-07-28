package com.qwen.tts.android.text

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * FictionBook 2 (.fb2 or .fb2.zip) -> plain text.
 *
 * FB2 is a single XML document. We read the main <body> (skipping the
 * <binary> blocks with embedded base64 images/covers, and skipping any
 * <body name="notes"> / <body name="comments"> sections which are footnotes,
 * not the main narrative).
 */
object Fb2TextExtractor {

    fun extract(input: InputStream, isZipped: Boolean): String {
        val xmlBytes = if (isZipped) unzipFirstFb2(input) else input.readBytes()
        val xml = String(xmlBytes, Charsets.UTF_8)

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        val sb = StringBuilder()
        var eventType = parser.eventType

        var insideBinary = false
        var skipBody = false
        var bodyDepth = 0

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "binary" -> insideBinary = true
                        "body" -> {
                            bodyDepth++
                            val bodyName = parser.getAttributeValue(null, "name")
                            // Main body has no "name" attribute; notes/comments do.
                            skipBody = bodyName != null
                        }
                        "p", "subtitle", "title", "empty-line", "v" -> {
                            if (!insideBinary && !skipBody && sb.isNotEmpty()) {
                                sb.append("\n")
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (!insideBinary && !skipBody) {
                        val text = parser.text?.trim()
                        if (!text.isNullOrEmpty()) {
                            sb.append(text)
                            sb.append(' ')
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "binary" -> insideBinary = false
                        "body" -> {
                            bodyDepth--
                            if (bodyDepth == 0) skipBody = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return sb.toString()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun unzipFirstFb2(input: InputStream): ByteArray {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true)) {
                    val buffer = ByteArrayOutputStream()
                    zip.copyTo(buffer)
                    return buffer.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        error("fb2.zip did not contain an .fb2 entry")
    }
}
