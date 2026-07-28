package com.qwen.tts.android.text

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * PDF (.pdf) -> plain text, via pdfbox-android.
 *
 * PDFBoxResourceLoader.init() must run once before any PDDocument use;
 * it's safe to call on every extraction (cheap no-op after first init),
 * but ideally call it once in Application.onCreate() instead.
 */
object PdfTextExtractor {

    fun extract(context: Context, input: InputStream): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(input).use { document ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
            }
            return stripper.getText(document)
                .replace(Regex("\\r\\n?"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }
    }
}
