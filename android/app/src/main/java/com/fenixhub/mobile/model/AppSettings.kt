package com.fenixhub.mobile.model

data class AppSettings(
    val configured: Boolean = false,
    val deviceName: String = "",
    val groupKeyHex: String = "",
    val groupId: String = "",
) {
    fun groupKeyBytes(): ByteArray = com.fenixhub.mobile.util.CryptoUtils.hexToBytes(groupKeyHex)
    fun macKeyBytes(): ByteArray = com.fenixhub.mobile.util.CryptoUtils.deriveMacKey(groupKeyBytes())
    fun encKeyBytes(): ByteArray = com.fenixhub.mobile.util.CryptoUtils.deriveEncKey(groupKeyBytes())
}
