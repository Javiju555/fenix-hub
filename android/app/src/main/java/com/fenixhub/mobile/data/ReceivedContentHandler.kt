package com.fenixhub.mobile.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.fenixhub.mobile.model.HubContentType
import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.PulledContent

data class ReceiveResult(
    val item: LocalContent,
    val message: String,
)

class ReceivedContentHandler(
    private val context: Context,
    private val tempStore: TempClipboardStore,
) {
    fun handle(peer: PeerContent, pulledContent: PulledContent): ReceiveResult {
        val item = createLocalContent(peer, pulledContent)

        return ReceiveResult(
            item = item,
            message = copyToSystemClipboard(item),
        )
    }

    fun createLocalContent(peer: PeerContent, pulledContent: PulledContent): LocalContent {
        return when (peer.announcement.contentType) {
            HubContentType.TEXT -> {
                val text = pulledContent.bytes.toString(Charsets.UTF_8)
                tempStore.createTextContent(text)
            }

            HubContentType.IMAGE -> {
                val mimeType = pulledContent.mimeType ?: peer.announcement.mimeType ?: previewMimeType(peer.announcement.preview) ?: "image/jpeg"
                tempStore.createBinaryContent(
                    bytes = pulledContent.bytes,
                    contentType = HubContentType.IMAGE,
                    mimeType = mimeType,
                    fileName = pulledContent.fileName ?: peer.announcement.fileName,
                )
            }

            HubContentType.FILE -> {
                tempStore.createBinaryContent(
                    bytes = pulledContent.bytes,
                    contentType = HubContentType.FILE,
                    mimeType = pulledContent.mimeType ?: peer.announcement.mimeType ?: "application/octet-stream",
                    fileName = pulledContent.fileName ?: peer.announcement.fileName,
                )
            }
        }
    }

    fun copyToSystemClipboard(item: LocalContent): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        return when (item.contentType) {
            HubContentType.TEXT -> {
                val text = tempStore.readBytes(item).toString(Charsets.UTF_8)
                clipboard.setPrimaryClip(ClipData.newPlainText("FenixHub", text))
                Log.i(TAG, "Copied TEXT to system clipboard (${text.length} chars)")
                "Texto copiado al portapapeles"
            }

            HubContentType.IMAGE -> {
                val uri = tempStore.contentUriFor(item)
                clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, item.fileName ?: "FenixHub", uri))
                Log.i(TAG, "Copied IMAGE URI to system clipboard: $uri")
                "Imagen temporal lista para pegar"
            }

            HubContentType.FILE -> {
                val uri = tempStore.contentUriFor(item)
                clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, item.fileName ?: "FenixHub", uri))
                Log.i(TAG, "Copied FILE URI to system clipboard: $uri")
                "Archivo temporal listo para pegar"
            }
        }
    }

    private fun previewMimeType(preview: String): String? {
        if (!preview.startsWith("data:image")) return null
        return preview.substringAfter("data:").substringBefore(';')
    }

    private companion object {
        const val TAG = "FenixHubClipboard"
    }
}
