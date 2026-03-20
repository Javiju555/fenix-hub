package com.fenixhub.mobile.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.fenixhub.mobile.model.HubContentType
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.util.PreviewUtils
import org.json.JSONObject
import java.io.File
import java.util.UUID

class TempClipboardStore(private val context: Context) {
    private val rootDir = File(context.cacheDir, "fenixhub/items")

    fun clearAll() {
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
        rootDir.mkdirs()
    }

    fun createTextContent(text: String): LocalContent {
        val contentId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val payloadFile = writePayload(contentId, "clipboard-$createdAt.txt", bytes)
        val item = LocalContent(
            contentId = contentId,
            preview = PreviewUtils.textPreview(text),
            contentType = HubContentType.TEXT,
            sizeBytes = bytes.size.toLong(),
            createdAt = createdAt,
            cachePath = payloadFile.absolutePath,
            mimeType = "text/plain; charset=utf-8",
            fileName = null,
        )
        writeMeta(item)
        return item
    }

    fun createFromUri(uri: Uri): LocalContent? {
        val resolver = context.contentResolver
        val fileName = queryDisplayName(resolver, uri) ?: "fenixhub-${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri) ?: inferMimeType(uri, resolver) ?: "application/octet-stream"
        val contentType = if (mimeType.startsWith("image/")) HubContentType.IMAGE else HubContentType.FILE
        val contentId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return createBinaryContent(
            contentId = contentId,
            createdAt = createdAt,
            contentType = contentType,
            bytes = bytes,
            mimeType = mimeType,
            fileName = fileName,
        )
    }

    fun createBinaryContent(
        bytes: ByteArray,
        contentType: HubContentType,
        mimeType: String,
        fileName: String? = null,
        previewOverride: String? = null,
    ): LocalContent {
        return createBinaryContent(
            contentId = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            contentType = contentType,
            bytes = bytes,
            mimeType = mimeType,
            fileName = fileName,
            previewOverride = previewOverride,
        )
    }

    fun readBytes(item: LocalContent): ByteArray = File(item.cachePath).readBytes()

    fun contentUriFor(item: LocalContent): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(item.cachePath),
        )
    }

    private fun createBinaryContent(
        contentId: String,
        createdAt: Long,
        contentType: HubContentType,
        bytes: ByteArray,
        mimeType: String,
        fileName: String? = null,
        previewOverride: String? = null,
    ): LocalContent {
        val resolvedFileName = fileName ?: defaultFileName(contentType, mimeType, contentId)
        val payloadFile = writePayload(contentId, resolvedFileName, bytes)
        val preview = previewOverride ?: when (contentType) {
            HubContentType.TEXT -> bytes.toString(Charsets.UTF_8).take(80)
            HubContentType.IMAGE -> PreviewUtils.imagePreviewDataUrl(bytes)
            HubContentType.FILE -> resolvedFileName
        }
        val item = LocalContent(
            contentId = contentId,
            preview = preview,
            contentType = contentType,
            sizeBytes = bytes.size.toLong(),
            createdAt = createdAt,
            cachePath = payloadFile.absolutePath,
            mimeType = mimeType,
            fileName = if (contentType == HubContentType.TEXT) null else resolvedFileName,
        )
        writeMeta(item)
        return item
    }

    private fun writePayload(contentId: String, fileName: String, bytes: ByteArray): File {
        val dir = File(rootDir, contentId).apply { mkdirs() }
        val payload = File(dir, sanitizeFileName(fileName))
        payload.writeBytes(bytes)
        return payload
    }

    private fun writeMeta(item: LocalContent) {
        val meta = JSONObject()
            .put("content_id", item.contentId)
            .put("content_type", item.contentType.wireValue)
            .put("preview", item.preview)
            .put("size_bytes", item.sizeBytes)
            .put("created_at", item.createdAt)
            .put("mime_type", item.mimeType)
            .put("file_name", item.fileName ?: JSONObject.NULL)
        File(rootDir, "${item.contentId}/meta.json").writeText(meta.toString())
    }

    private fun defaultFileName(contentType: HubContentType, mimeType: String, contentId: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: when (contentType) {
                HubContentType.TEXT -> "txt"
                HubContentType.IMAGE -> "jpg"
                HubContentType.FILE -> "bin"
            }
        return "fenixhub-$contentId.$extension"
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val cursor: Cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index == -1) return null
            return it.getString(index)
        }
    }

    private fun inferMimeType(uri: Uri, resolver: ContentResolver): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            ?.lowercase()
            .orEmpty()
        if (extension.isNotBlank()) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        return resolver.getType(uri)
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.map { ch ->
            if (ch in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')) '_' else ch
        }.joinToString("").ifBlank { "fenixhub-item" }
    }
}
