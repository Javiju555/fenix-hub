package com.fenixhub.mobile.model

/**
 * Progreso de una transferencia activa.
 */
data class TransferProgress(
    val contentId: String,
    val contentName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val estimatedMsRemaining: Long,
    val direction: Direction,
) {
    enum class Direction {
        SENDING,
        RECEIVING,
    }

    val progressPercent: Float
        get() = if (totalBytes <= 0) -1f
                else (bytesTransferred.toFloat() / totalBytes.toFloat()) * 100f

    val isComplete: Boolean
        get() = totalBytes > 0 && bytesTransferred >= totalBytes
}
