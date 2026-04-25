package com.fenixhub.mobile.model

import java.io.File

data class PulledContent(
    val bytes: ByteArray,
    val mimeType: String?,
    val fileName: String?,
    val file: File? = null,
    val sizeBytes: Long = bytes.size.toLong(),
)
