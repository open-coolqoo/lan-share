package com.lanshare

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

data class ReceivedTransferPayload(
    val offer: FileOffer,
    val chunks: List<ByteArray> = emptyList(),
    val sourceFile: File? = null,
)

class AndroidDownloadStore(
    private val contentResolver: ContentResolver,
) {
    fun save(payload: ReceivedTransferPayload): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(payload)
        } else {
            saveViaLegacyDownloads(payload)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(payload: ReceivedTransferPayload): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sanitizeFileName(payload.offer.name))
            put(MediaStore.Downloads.MIME_TYPE, payload.offer.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create download entry.")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                payload.openInputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not open download output stream.")
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Throwable) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveViaLegacyDownloads(payload: ReceivedTransferPayload): Uri {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val file = uniqueDownloadFile(downloadsDir, sanitizeFileName(payload.offer.name))
        FileOutputStream(file).use { output ->
            payload.openInputStream().use { input -> input.copyTo(output) }
        }
        return Uri.fromFile(file)
    }

    private fun ReceivedTransferPayload.openInputStream(): InputStream =
        sourceFile?.let(::FileInputStream) ?: chunks.asSequence().flatMap { it.asIterable().asSequence() }.toList().toByteArray().inputStream()

    private fun uniqueDownloadFile(directory: File, fileName: String): File {
        val dot = fileName.lastIndexOf('.')
        val baseName = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var candidate = File(directory, fileName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName ($index)$extension")
            index += 1
        }
        return candidate
    }
}
