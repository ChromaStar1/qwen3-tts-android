package com.qwen.tts.android.text

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Entry point for converting an arbitrary document (picked via ACTION_OPEN_DOCUMENT
 * or a file picker) into plain text ready to be fed into the TTS pipeline.
 *
 * Usage from a Compose screen / ViewModel:
 *
 *   val text = DocumentTextExtractor.extractText(context, uri)
 *   studioViewModel.setInputText(text)
 */
object DocumentTextExtractor {

    /**
     * Extracts plain text from [uri]. Dispatches by file extension first
     * (most reliable for content:// uris), falling back to MIME type.
     *
     * Throws [UnsupportedDocumentException] if the format isn't recognized,
     * or [DocumentParseException] if parsing fails.
     */
    fun extractText(context: Context, uri: Uri): String {
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when {
                    ext == "epub" || mime.contains("epub") ->
                        EpubTextExtractor.extract(input)

                    ext == "fb2" || mime.contains("fictionbook") ->
                        Fb2TextExtractor.extract(input, isZipped = false)

                    ext == "zip" && name.contains(".fb2", ignoreCase = true) ->
                        Fb2TextExtractor.extract(input, isZipped = true)

                    ext == "pdf" || mime == "application/pdf" ->
                        PdfTextExtractor.extract(context, input)

                    ext == "txt" || mime.startsWith("text/plain") ->
                        input.bufferedReader(Charsets.UTF_8).readText()

                    else -> throw UnsupportedDocumentException(
                        "Unsupported file: name=$name mime=$mime"
                    )
                }
            } ?: throw DocumentParseException("Could not open input stream for $uri")
        } catch (e: UnsupportedDocumentException) {
            throw e
        } catch (e: Exception) {
            throw DocumentParseException("Failed to parse $name: ${e.message}", e)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    /** MIME types to pass to ACTION_OPEN_DOCUMENT / GetContent for the file picker. */
    val SUPPORTED_MIME_TYPES = arrayOf(
        "application/pdf",
        "application/epub+zip",
        "application/x-fictionbook+xml",
        "application/zip",
        "text/plain",
        "*/*" // fallback: some file providers report generic octet-stream for fb2/epub
    )
}

class UnsupportedDocumentException(message: String) : Exception(message)
class DocumentParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
