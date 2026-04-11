package com.fenixhub.mobile.model

/**
 * Estados de una transferencia por WiFi Direct.
 * Flujo: Idle → [Discovering/Connecting] → Connected → Transferring → Complete
 */
sealed class WifiDirectTransferState {
    /** Sin transferencia activa. */
    data object Idle : WifiDirectTransferState()

    /** Creando grupo WiFi Direct (soy el que envía). */
    data object CreatingGroup : WifiDirectTransferState()

    /** Grupo creado, esperando a que el receptor se conecte. */
    data object WaitingForReceiver : WifiDirectTransferState()

    /** Conectándome a un grupo existente (soy el que recibe). */
    data class Connecting(val groupOwnerAddress: String) : WifiDirectTransferState()

    /** Conectado. [isGroupOwner] indica si soy el GO. */
    data class Connected(
        val groupOwnerAddress: String,
        val isGroupOwner: Boolean,
        val httpPort: Int,
    ) : WifiDirectTransferState()

    /** Transfiriendo contenido. */
    data class Transferring(
        val contentId: String,
        val bytesTransferred: Long,
        val totalBytes: Long,
    ) : WifiDirectTransferState()

    /** Transferencia completada. */
    data class Complete(val contentId: String) : WifiDirectTransferState()

    /** Error en la transferencia. */
    data class Error(val reason: String) : WifiDirectTransferState()
}

/**
 * Información de un grupo WiFi Direct activo.
 */
data class WifiDirectGroupInfo(
    val groupOwnerAddress: String,
    val isGroupOwner: Boolean,
    val clients: List<String>,
)
