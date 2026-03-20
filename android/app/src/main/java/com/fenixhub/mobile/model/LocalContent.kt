package com.fenixhub.mobile.model

data class LocalContent(
    val contentId: String,
    val preview: String,
    val contentType: HubContentType,
    val sizeBytes: Long,
    val createdAt: Long,
    val cachePath: String,
    val mimeType: String,
    val fileName: String?,
    val isPublished: Boolean = false,
    val sendMode: SendMode = SendMode.Broadcast,
)
