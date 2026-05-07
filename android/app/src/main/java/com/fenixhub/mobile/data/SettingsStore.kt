package com.fenixhub.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.IdentityProfileInfo
import com.fenixhub.mobile.util.CryptoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore

class SettingsStore(private val context: Context) {
    @Volatile private var storageRecoveryMessage: String? = null
    @Volatile private var prefs: SharedPreferences = createEncryptedPrefs()

    private val mutableSettings = MutableStateFlow(loadSafely())
    val settingsFlow: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    // Transient in-memory override for mesh sessions. Never persisted.
    @Volatile private var meshSessionOverride: AppSettings? = null

    fun current(): AppSettings = meshSessionOverride ?: mutableSettings.value

    fun overrideMeshSession(groupId: String, groupKeyHex: String) {
        val base = mutableSettings.value
        meshSessionOverride = base.copy(groupId = groupId, groupKeyHex = groupKeyHex)
        mutableSettings.value = meshSessionOverride!!
    }

    fun clearMeshSessionOverride() {
        meshSessionOverride = null
        mutableSettings.value = loadSafely()
    }

    fun consumeStorageRecoveryMessage(): String? {
        val message = storageRecoveryMessage
        storageRecoveryMessage = null
        return message
    }

    fun saveIdentity(passphrase: String, deviceName: String, deviceType: String? = null): AppSettings {
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
            deviceType = normalizeDeviceType(deviceType),
        )

        persistConfiguredSettings(settings)
        mutableSettings.value = settings
        return settings
    }

    fun updateIdentity(passphrase: String?, deviceName: String, deviceType: String? = null): AppSettings {
        val currentSettings = mutableSettings.value
        require(currentSettings.configured && currentSettings.groupKeyHex.isNotBlank()) {
            "Configura FenixHub antes de actualizar identidad"
        }

        val normalizedDeviceName = deviceName.trim()
        require(normalizedDeviceName.isNotBlank()) { "El nombre del dispositivo es obligatorio" }

        val normalizedPassphrase = passphrase?.trim().orEmpty()
        if (normalizedPassphrase.isNotEmpty()) {
            CryptoUtils.validatePassphraseStrength(normalizedPassphrase)?.let { message ->
                throw IllegalArgumentException(message)
            }
        }

        val nextKeyHex = if (normalizedPassphrase.isNotEmpty()) {
            CryptoUtils.toHex(CryptoUtils.deriveGroupKey(normalizedPassphrase))
        } else {
            currentSettings.groupKeyHex
        }

        val settings = AppSettings(
            configured = true,
            deviceName = normalizedDeviceName,
            groupKeyHex = nextKeyHex,
            groupId = groupIdFromKeyHex(nextKeyHex),
            deviceType = normalizeDeviceType(deviceType ?: currentSettings.deviceType),
        )

        persistConfiguredSettings(settings)
        mutableSettings.value = settings
        return settings
    }

    fun listProfiles(): List<IdentityProfileInfo> {
        val store = readProfilesStore()
        return store.profiles.keys.sorted().map { name ->
            val profile = store.profiles[name]!!
            IdentityProfileInfo(
                name = name,
                deviceName = profile.deviceName,
                groupId = groupIdFromKeyHex(profile.keyHex),
                deviceType = profile.deviceType,
                active = store.activeProfile == name,
            )
        }
    }

    fun saveCurrentProfile(profileName: String, makeActive: Boolean = false): List<IdentityProfileInfo> {
        val settings = mutableSettings.value
        require(settings.configured && settings.groupKeyHex.isNotBlank()) {
            "Identity not configured"
        }

        val normalizedName = profileName.trim()
        require(normalizedName.isNotBlank()) { "Profile name is required" }

        val store = readProfilesStore()
        store.profiles[normalizedName] = PersistedProfile(
            keyHex = settings.groupKeyHex,
            deviceName = settings.deviceName,
            deviceType = settings.deviceType,
        )

        if (makeActive || store.activeProfile.isNullOrBlank()) {
            store.activeProfile = normalizedName
        }

        writeProfilesStore(store)
        return listProfiles()
    }

    fun activateProfile(profileName: String): AppSettings? {
        val normalizedName = profileName.trim()
        if (normalizedName.isBlank()) return null

        val store = readProfilesStore()
        val profile = store.profiles[normalizedName] ?: return null
        val groupId = groupIdFromKeyHex(profile.keyHex)
        require(groupId.isNotBlank()) { "Profile not found" }

        val settings = AppSettings(
            configured = true,
            deviceName = profile.deviceName,
            groupKeyHex = profile.keyHex,
            groupId = groupId,
            deviceType = normalizeDeviceType(profile.deviceType),
        )

        store.activeProfile = normalizedName
        writeProfilesStore(store)
        persistConfiguredSettings(settings, syncActiveProfile = false)
        mutableSettings.value = settings
        return settings
    }

    fun removeProfile(profileName: String): List<IdentityProfileInfo> {
        val normalizedName = profileName.trim()
        if (normalizedName.isBlank()) return listProfiles()

        val store = readProfilesStore()
        store.profiles.remove(normalizedName)
        if (store.activeProfile == normalizedName) {
            store.activeProfile = store.profiles.keys.sorted().firstOrNull()
        }
        writeProfilesStore(store)
        return listProfiles()
    }

    fun clearIdentityOnly(): AppSettings {
        prefs.edit()
            .putBoolean(KEY_CONFIGURED, false)
            .putString(KEY_DEVICE_NAME, "")
            .remove(KEY_GROUP_KEY_HEX)
            .remove(KEY_GROUP_ID)
            .putString(KEY_DEVICE_TYPE, DEFAULT_DEVICE_TYPE)
            .putInt(KEY_KDF_VERSION, CURRENT_KDF_VERSION)
            .apply()

        val settings = AppSettings(
            configured = false,
            deviceName = "",
            groupKeyHex = "",
            groupId = "",
            deviceType = DEFAULT_DEVICE_TYPE,
        )
        mutableSettings.value = settings
        return settings
    }

    fun resetAllData() {
        prefs.edit()
            .clear()
            .putInt(KEY_KDF_VERSION, CURRENT_KDF_VERSION)
            .apply()
        mutableSettings.value = AppSettings()
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

    private fun persistConfiguredSettings(settings: AppSettings, syncActiveProfile: Boolean = true) {
        prefs.edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putString(KEY_DEVICE_NAME, settings.deviceName)
            .putString(KEY_GROUP_KEY_HEX, settings.groupKeyHex)
            .putString(KEY_GROUP_ID, settings.groupId)
            .putString(KEY_DEVICE_TYPE, settings.deviceType)
            .putInt(KEY_KDF_VERSION, CURRENT_KDF_VERSION)
            .apply()

        if (syncActiveProfile) {
            syncActiveProfile(settings)
        }
    }

    private fun syncActiveProfile(settings: AppSettings) {
        val store = readProfilesStore()
        val activeName = store.activeProfile ?: return
        if (!store.profiles.containsKey(activeName)) return
        store.profiles[activeName] = PersistedProfile(
            keyHex = settings.groupKeyHex,
            deviceName = settings.deviceName,
            deviceType = settings.deviceType,
        )
        writeProfilesStore(store)
    }

    private fun readProfilesStore(): PersistedProfilesStore {
        val raw = prefs.getString(KEY_PROFILES_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return PersistedProfilesStore()
        }

        val root = runCatching { JSONObject(raw) }.getOrElse {
            return PersistedProfilesStore()
        }

        val activeProfile = root.optString("active_profile").trim().ifBlank { null }
        val profilesObject = root.optJSONObject("profiles") ?: JSONObject()
        val profiles = linkedMapOf<String, PersistedProfile>()
        val keys = mutableListOf<String>()
        val iterator = profilesObject.keys()
        while (iterator.hasNext()) {
            keys += iterator.next()
        }

        keys.sorted().forEach { name ->
            val profileObject = profilesObject.optJSONObject(name) ?: return@forEach
            val keyHex = profileObject.optString("key_hex").trim()
            val deviceName = profileObject.optString("device_name").trim()
            val deviceType = normalizeDeviceType(profileObject.optString("device_type"))
            if (keyHex.isBlank() || deviceName.isBlank()) return@forEach

            profiles[name] = PersistedProfile(
                keyHex = keyHex,
                deviceName = deviceName,
                deviceType = deviceType,
            )
        }

        val normalizedActive = activeProfile?.takeIf { profiles.containsKey(it) }
        return PersistedProfilesStore(normalizedActive, profiles)
    }

    private fun writeProfilesStore(store: PersistedProfilesStore) {
        val profilesJson = JSONObject()
        store.profiles.keys.sorted().forEach { name ->
            val profile = store.profiles[name] ?: return@forEach
            profilesJson.put(
                name,
                JSONObject()
                    .put("key_hex", profile.keyHex)
                    .put("device_name", profile.deviceName)
                    .put("device_type", profile.deviceType),
            )
        }

        val root = JSONObject()
            .put("active_profile", store.activeProfile ?: JSONObject.NULL)
            .put("profiles", profilesJson)

        prefs.edit()
            .putString(KEY_PROFILES_JSON, root.toString())
            .apply()
    }

    private fun groupIdFromKeyHex(keyHex: String): String {
        if (keyHex.isBlank()) return ""
        return runCatching {
            CryptoUtils.groupIdFromKey(CryptoUtils.hexToBytes(keyHex))
        }.getOrDefault("")
    }

    private fun normalizeDeviceType(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "desktop" -> "desktop"
            "laptop" -> "laptop"
            "phone" -> "phone"
            "tablet" -> "tablet"
            "server" -> "server"
            "android" -> DEFAULT_DEVICE_TYPE
            else -> DEFAULT_DEVICE_TYPE
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return runCatching { openEncryptedPrefs() }
            .getOrElse { error ->
                recoverUnreadablePrefs(error)
                openEncryptedPrefs()
            }
    }

    private fun openEncryptedPrefs(): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun loadSafely(): AppSettings {
        return runCatching { load() }
            .getOrElse { error ->
                recoverUnreadablePrefs(error)
                prefs = createEncryptedPrefs()
                prefs.edit()
                    .clear()
                    .putInt(KEY_KDF_VERSION, CURRENT_KDF_VERSION)
                    .apply()
                AppSettings()
            }
    }

    private fun recoverUnreadablePrefs(error: Throwable) {
        Log.w(TAG, "Encrypted settings are unreadable; resetting local identity storage", error)
        storageRecoveryMessage = "Datos cifrados ilegibles; vuelve a configurar la identidad."
        context.deleteSharedPreferences(PREF_NAME)
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }.onFailure { keyStoreError ->
            Log.w(TAG, "Could not remove Android Keystore master key", keyStoreError)
        }
    }

    private fun load(): AppSettings {
        val configured = prefs.getBoolean(KEY_CONFIGURED, false)
        val keyVersion = prefs.getInt(KEY_KDF_VERSION, LEGACY_KDF_VERSION)
        val savedDeviceName = prefs.getString(KEY_DEVICE_NAME, "").orEmpty()
        val savedDeviceType = normalizeDeviceType(prefs.getString(KEY_DEVICE_TYPE, DEFAULT_DEVICE_TYPE))

        if (configured && keyVersion < CURRENT_KDF_VERSION) {
            prefs.edit()
                .putBoolean(KEY_CONFIGURED, false)
                .remove(KEY_GROUP_KEY_HEX)
                .remove(KEY_GROUP_ID)
                .remove(KEY_PROFILES_JSON)
                .putString(KEY_DEVICE_TYPE, DEFAULT_DEVICE_TYPE)
                .putInt(KEY_KDF_VERSION, CURRENT_KDF_VERSION)
                .apply()

            // Legacy keys were derived with v1 parameters and are incompatible
            // with desktop v2. Force a one-time re-setup with passphrase.
            return AppSettings(
                configured = false,
                deviceName = savedDeviceName,
                groupKeyHex = "",
                groupId = "",
                deviceType = savedDeviceType,
            )
        }

        if (!configured) {
            val store = readProfilesStore()
            val activeName = store.activeProfile
            if (activeName != null) {
                val profile = store.profiles[activeName]
                if (profile != null) {
                    val restoredGroupId = groupIdFromKeyHex(profile.keyHex)
                    if (restoredGroupId.isNotBlank()) {
                        val restored = AppSettings(
                            configured = true,
                            deviceName = profile.deviceName,
                            groupKeyHex = profile.keyHex,
                            groupId = restoredGroupId,
                            deviceType = normalizeDeviceType(profile.deviceType),
                        )
                        persistConfiguredSettings(restored, syncActiveProfile = false)
                        return restored
                    }
                }
            }

            return AppSettings(
                configured = false,
                deviceName = savedDeviceName,
                groupKeyHex = "",
                groupId = "",
                deviceType = savedDeviceType,
            )
        }

        val deviceName = savedDeviceName
        val groupKeyHex = prefs.getString(KEY_GROUP_KEY_HEX, "").orEmpty()
        val savedGroupId = prefs.getString(KEY_GROUP_ID, "").orEmpty()

        val derivedGroupId = if (configured && groupKeyHex.isNotBlank()) {
            groupIdFromKeyHex(groupKeyHex).ifBlank { savedGroupId }
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
            deviceType = savedDeviceType,
        )
    }

    private data class PersistedProfile(
        val keyHex: String,
        val deviceName: String,
        val deviceType: String,
    )

    private data class PersistedProfilesStore(
        var activeProfile: String? = null,
        val profiles: LinkedHashMap<String, PersistedProfile> = linkedMapOf(),
    )

    private companion object {
        const val TAG = "FenixHubSettings"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val PREF_NAME = "fenixhub-secure-prefs"
        const val KEY_CONFIGURED = "configured"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_GROUP_KEY_HEX = "group_key_hex"
        const val KEY_GROUP_ID = "group_id"
        const val KEY_DEVICE_TYPE = "device_type"
        const val KEY_KDF_VERSION = "key_derivation_version"
        const val KEY_IGNORED_PEER_CONTENT_IDS = "ignored_peer_content_ids"
        const val KEY_PROFILES_JSON = "profiles_json"

        const val LEGACY_KDF_VERSION = 1
        const val CURRENT_KDF_VERSION = 2
        const val DEFAULT_DEVICE_TYPE = "phone"
    }
}
