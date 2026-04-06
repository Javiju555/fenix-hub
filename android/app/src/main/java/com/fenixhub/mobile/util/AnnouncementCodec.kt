package com.fenixhub.mobile.util

import com.fenixhub.mobile.model.Announcement
import com.fenixhub.mobile.model.HubContentType
import com.fenixhub.mobile.model.SendMode
import org.json.JSONObject

object AnnouncementCodec {
    fun encode(announcement: Announcement): String {
        return JSONObject()
            .put("protocol_version", announcement.protocolVersion)
            .put("group_id", announcement.groupId)
            .put("content_id", announcement.contentId)
            .put("device_name", announcement.deviceName)
            .put("preview", announcement.preview)
            .put("content_type", announcement.contentType.wireValue)
            .put("size_bytes", announcement.sizeBytes)
            .put("file_name", announcement.fileName)
            .put("mime_type", announcement.mimeType)
            .put("send_mode", sendModeJson(announcement.sendMode))
            .put("created_at", announcement.createdAt)
            .put("port", announcement.port)
            .toString()
    }

    fun decode(raw: String): Announcement? {
        return runCatching {
            val root = JSONObject(raw)
            val contentType = HubContentType.fromWireValue(root.getString("content_type"))
                ?: return null

            Announcement(
                protocolVersion = root.optInt("protocol_version", 0),
                groupId = root.getString("group_id"),
                contentId = root.getString("content_id"),
                deviceName = root.getString("device_name"),
                preview = root.getString("preview"),
                contentType = contentType,
                sizeBytes = root.getLong("size_bytes"),
                fileName = root.takeIf { !it.isNull("file_name") }?.optString("file_name")?.takeIf { it.isNotBlank() },
                mimeType = root.takeIf { !it.isNull("mime_type") }?.optString("mime_type")?.takeIf { it.isNotBlank() },
                sendMode = decodeSendMode(root.getJSONObject("send_mode")),
                createdAt = root.getLong("created_at"),
                port = root.getInt("port"),
            )
        }.getOrNull()
    }

    private fun sendModeJson(sendMode: SendMode): JSONObject {
        return when (sendMode) {
            SendMode.Broadcast -> JSONObject().put("Broadcast", JSONObject())
            is SendMode.Direct -> JSONObject().put(
                "Direct",
                JSONObject().put("target_device", sendMode.targetDevice),
            )
        }
    }

    private fun decodeSendMode(root: JSONObject): SendMode {
        return when {
            root.has("Broadcast") -> SendMode.Broadcast
            root.has("Direct") -> {
                val direct = root.getJSONObject("Direct")
                SendMode.Direct(direct.getString("target_device"))
            }

            else -> SendMode.Broadcast
        }
    }
}
