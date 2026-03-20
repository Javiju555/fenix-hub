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
        val groupKey = CryptoUtils.deriveGroupKey(passphrase)
        val groupKeyHex = CryptoUtils.toHex(groupKey)
        val settings = AppSettings(
            configured = true,
            deviceName = deviceName.trim(),
            groupKeyHex = groupKeyHex,
            groupId = CryptoUtils.groupIdFromKey(groupKey),
        )

        prefs.edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putString(KEY_DEVICE_NAME, settings.deviceName)
            .putString(KEY_GROUP_KEY_HEX, settings.groupKeyHex)
            .putString(KEY_GROUP_ID, settings.groupId)
            .apply()

        mutableSettings.value = settings
        return settings
    }

    private fun load(): AppSettings {
        val configured = prefs.getBoolean(KEY_CONFIGURED, false)
        return AppSettings(
            configured = configured,
            deviceName = prefs.getString(KEY_DEVICE_NAME, "").orEmpty(),
            groupKeyHex = prefs.getString(KEY_GROUP_KEY_HEX, "").orEmpty(),
            groupId = prefs.getString(KEY_GROUP_ID, "").orEmpty(),
        )
    }

    private companion object {
        const val PREF_NAME = "fenixhub-secure-prefs"
        const val KEY_CONFIGURED = "configured"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_GROUP_KEY_HEX = "group_key_hex"
        const val KEY_GROUP_ID = "group_id"
    }
}
