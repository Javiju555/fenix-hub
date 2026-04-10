package com.fenixhub.mobile.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.util.CryptoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREF_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val mutableSettings = MutableStateFlow(load())
    val settingsFlow: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    fun current(): AppSettings = mutableSettings.value

    fun saveIdentity(passphrase: String, deviceName: String): AppSettings {
        val normalizedPassphrase = passphrase.trim()
        val normalizedDeviceName = deviceName.trim()
        require(normalizedDeviceName.isNotBlank()) { "El nombre del dispositivo es obligatorio" }
        CryptoUtils.validatePassphraseStrength(normalizedPassphrase)?.let { message ->
            throw IllegalArgumentException(message)
        }

        val groupKey = CryptoUtils.deriveGroupKey(normalizedPassphrase)
        val groupKeyHex = CryptoUtils.toHex(groupKey)
        val settings = AppSettings(
            configured = true,
            deviceName = normalizedDeviceName,
            groupKeyHex = groupKeyHex,
            groupId = CryptoUtils.groupIdFromKey(groupKey),
        )

        prefs.edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putString(KEY_DEVICE_NAME, settings.deviceName)
            .putString(KEY_GROUP_KEY_HEX, settings.groupKeyHex)
            .putString(KEY_GROUP_ID, settings.groupId)
            .putInt(KEY_KDF_VERSION, CURRENT_KDF_VERSION)
            .apply()

        mutableSettings.value = settings
        return settings
    }

    fun ignoredPeerContentIds(): Set<String> {
        return prefs.getStringSet(KEY_IGNORED_PEER_CONTENT_IDS, emptySet()).orEmpty()
    }

    fun isIgnoredPeerContent(contentId: String): Boolean {
        return ignoredPeerContentIds().contains(contentId)
    }

    fun ignorePeerContent(contentId: String) {
        val updated = ignoredPeerContentIds().toMutableSet().apply { add(contentId) }
        prefs.edit()
            .putStringSet(KEY_IGNORED_PEER_CONTENT_IDS, updated)
            .apply()
    }

    fun clearIgnoredPeerContent(contentId: String) {
        val updated = ignoredPeerContentIds().toMutableSet().apply { remove(contentId) }
        prefs.edit()
            .putStringSet(KEY_IGNORED_PEER_CONTENT_IDS, updated)
            .apply()
    }

    private fun load(): AppSettings {
        val configured = prefs.getBoolean(KEY_CONFIGURED, false)
        val keyVersion = prefs.getInt(KEY_KDF_VERSION, LEGACY_KDF_VERSION)

        if (configured && keyVersion < CURRENT_KDF_VERSION) {
            val savedDeviceName = prefs.getString(KEY_DEVICE_NAME, "").orEmpty()
            prefs.edit()
                .putBoolean(KEY_CONFIGURED, false)
                .remove(KEY_GROUP_KEY_HEX)
                .remove(KEY_GROUP_ID)
                .putInt(KEY_KDF_VERSION, CURRENT_KDF_VERSION)
                .apply()

            // Legacy keys were derived with v1 parameters and are incompatible
            // with desktop v2. Force a one-time re-setup with passphrase.
            return AppSettings(
                configured = false,
                deviceName = savedDeviceName,
                groupKeyHex = "",
                groupId = "",
            )
        }

        val deviceName = prefs.getString(KEY_DEVICE_NAME, "").orEmpty()
        val groupKeyHex = prefs.getString(KEY_GROUP_KEY_HEX, "").orEmpty()
        val savedGroupId = prefs.getString(KEY_GROUP_ID, "").orEmpty()

        val derivedGroupId = if (configured && groupKeyHex.isNotBlank()) {
            runCatching {
                CryptoUtils.groupIdFromKey(CryptoUtils.hexToBytes(groupKeyHex))
            }.getOrDefault(savedGroupId)
        } else {
            savedGroupId
        }

        if (configured && derivedGroupId.isNotBlank() && derivedGroupId != savedGroupId) {
            prefs.edit().putString(KEY_GROUP_ID, derivedGroupId).apply()
        }

        return AppSettings(
            configured = configured,
            deviceName = deviceName,
            groupKeyHex = groupKeyHex,
            groupId = derivedGroupId,
        )
    }

    private companion object {
        const val PREF_NAME = "fenixhub-secure-prefs"
        const val KEY_CONFIGURED = "configured"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_GROUP_KEY_HEX = "group_key_hex"
        const val KEY_GROUP_ID = "group_id"
        const val KEY_KDF_VERSION = "key_derivation_version"
        const val KEY_IGNORED_PEER_CONTENT_IDS = "ignored_peer_content_ids"

        const val LEGACY_KDF_VERSION = 1
        const val CURRENT_KDF_VERSION = 2
    }
}
