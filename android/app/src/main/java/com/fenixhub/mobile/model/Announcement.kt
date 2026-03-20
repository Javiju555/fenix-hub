package com.fenixhub.mobile.model

data class Announcement(
    val groupId: String,
    val contentId: String,
    val deviceName: String,
    val preview: String,
    val contentType: HubContentType,
    val sizeBytes: Long,
    val fileName: String? = null,
    val mimeType: String? = null,
    val sendMode: SendMode,
    val createdAt: Long,
    val port: Int,
)
