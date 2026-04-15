package com.fenixhub.mobile.model

import org.json.JSONObject

/**
 * Mensaje de handoff que se intercambia por BLE para coordinar
 * la transferencia por WiFi Direct.
 *
 * Flujo: BLE descubre peer → se intercambia WifiDirectHandoff →
 * WiFi Direct conecta → HTTP pull sobre p2p
 *
 * El handoff se transmite en el service data BLE adicional al
 * payload de identity (groupTag + deviceName).
 */
data class WifiDirectHandoff(
    /** Dirección MAC del dispositivo WiFi Direct (para connect). */
    val p2pDeviceAddress: String,
    /** IP del GO en la red p2p (si ya está creado el grupo). */
    val p2pIp: String?,
    /** Puerto del servidor HTTP del GO. */
    val httpPort: Int,
    /** ID del contenido a transferir. */
    val contentId: String,
    /** Tamaño del contenido en bytes. */
    val contentSizeBytes: Long,
    /** Tipo de contenido. */
    val contentType: HubContentType,
    /** Nombre del archivo (si aplica). */
    val fileName: String?,
    /** Rol del dispositivo que envía este handoff. */
    val role: Role,
) {
    enum class Role {
        /** Este dispositivo es el GO (envía contenido). */
        SENDER,
        /** Este dispositivo es el client (recibe contenido). */
        RECEIVER,
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("p2p_addr", p2pDeviceAddress)
            p2pIp?.let { put("p2p_ip", it) }
            put("port", httpPort)
            put("cid", contentId)
            put("size", contentSizeBytes)
            put("type", contentType.name)
            fileName?.let { put("fname", it) }
            put("role", role.name)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): WifiDirectHandoff? {
            return runCatching {
                val obj = JSONObject(json)
                WifiDirectHandoff(
                    p2pDeviceAddress = obj.getString("p2p_addr"),
                    p2pIp = obj.optString("p2p_ip").takeIf { it.isNotBlank() },
                    httpPort = obj.getInt("port"),
                    contentId = obj.getString("cid"),
                    contentSizeBytes = obj.getLong("size"),
                    contentType = try {
                        HubContentType.valueOf(obj.getString("type"))
                    } catch (_: Exception) {
                        HubContentType.FILE
                    },
                    fileName = obj.optString("fname").takeIf { it.isNotBlank() },
                    role = try {
                        Role.valueOf(obj.getString("role"))
                    } catch (_: Exception) {
                        Role.SENDER
                    },
                )
            }.getOrNull()
        }

        /**
         * Tamaño máximo del payload JSON para que quepa en un advertisement BLE.
         * BLE advertisement data = 31 bytes, con overhead ~10 bytes para flags/length.
         * El handoff completo es demasiado grande para un solo advertisement,
         * así que se fragmenta o se usa el scan response.
         */
        const val MAX_BLE_PAYLOAD_BYTES = 20
    }
}
