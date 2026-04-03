package com.fenixhub.mobile.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages an Android Local-Only WiFi Hotspot (sin internet, solo LAN privada).
 *
 * Usa [WifiManager.startLocalOnlyHotspot] para crear una red WiFi privada a la que
 * el PC puede conectarse. Una vez conectados ambos dispositivos, FenixHub funciona
 * exactamente igual que en una red WiFi normal — descubrimiento mDNS + HTTP.
 *
 * El SSID y contraseña son generados por el sistema Android. Nosotros los leemos
 * y los mostramos al usuario para que el PC pueda conectarse.
 *
 * Permisos requeridos en AndroidManifest.xml:
 *   android.permission.CHANGE_WIFI_STATE          (siempre obligatorio)
 *   android.permission.ACCESS_FINE_LOCATION       (API 31–32, conceder en runtime)
 *   android.permission.NEARBY_WIFI_DEVICES        (API 33+, sin necesidad de localización)
 *
 * Nota: si el WiFi normal está conectado, Android puede desconectarlo temporalmente
 * al activar el hotspot en algunos dispositivos. No hay datos móviles implicados.
 */
class HotspotManager(context: Context) {

    /** Credenciales del hotspot que el usuario debe introducir en el PC. */
    data class Credentials(val ssid: String, val password: String)

    sealed class State {
        /** Hotspot apagado. */
        object Idle : State()
        /** Solicitado al sistema, esperando respuesta. */
        object Starting : State()
        /** Activo. Contiene SSID y contraseña para conectarse. */
        data class Active(val credentials: Credentials) : State()
        /** Error al activar (motivo en [reason]). */
        data class Failed(val reason: String) : State()
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Inicia el hotspot. Ignora llamadas duplicadas si ya está activo/arrancando. */
    fun start() {
        if (_state.value is State.Active || _state.value is State.Starting) return
        _state.value = State.Starting
        val wifi = appContext.getSystemService(WifiManager::class.java)
        try {
            wifi.startLocalOnlyHotspot(hotspotCallback, mainHandler)
        } catch (e: SecurityException) {
            _state.value = State.Failed("Permiso denegado: ${e.message}")
        }
    }

    /** Detiene el hotspot y libera el recurso del sistema. */
    fun stop() {
        reservation?.close()
        reservation = null
        if (_state.value !is State.Idle) _state.value = State.Idle
    }

    val isActive: Boolean get() = _state.value is State.Active

    // ── Callback interno ──────────────────────────────────────────────────────

    private val hotspotCallback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
            reservation = r
            val creds = extractCredentials(r)
            _state.value = if (creds != null) State.Active(creds)
                           else State.Failed("No se pudieron obtener las credenciales del hotspot")
        }

        override fun onStopped() {
            reservation = null
            _state.value = State.Idle
        }

        override fun onFailed(reason: Int) {
            reservation = null
            _state.value = State.Failed(describeFailure(reason))
        }
    }

    // ── Extracción de credenciales (API compat) ────────────────────────────────

    @Suppress("DEPRECATION")
    private fun extractCredentials(r: WifiManager.LocalOnlyHotspotReservation): Credentials? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33 (Android 13+): SoftApConfiguration
            val config = r.softApConfiguration
            val ssid = config.ssid ?: return null
            Credentials(ssid = ssid, password = config.passphrase ?: "")
        } else {
            // API 31–32 (Android 12): WifiConfiguration (deprecated pero funcional)
            val config = r.wifiConfiguration ?: return null
            val ssid = config.SSID?.trim('"') ?: return null
            Credentials(ssid = ssid, password = config.preSharedKey?.trim('"') ?: "")
        }
    }

    private fun describeFailure(code: Int): String = when (code) {
        ERROR_NO_CHANNEL          -> "Sin canal WiFi disponible"
        ERROR_GENERIC             -> "Error genérico al activar hotspot"
        ERROR_INCOMPATIBLE_MODE   -> "Modo incompatible — desactiva el WiFi normal e inténtalo de nuevo"
        ERROR_TETHERING_DISALLOWED -> "El operador o el sistema no permite hotspot"
        else                      -> "Error desconocido (código $code)"
    }

    companion object {
        const val ERROR_NO_CHANNEL           = 1
        const val ERROR_GENERIC              = 2
        const val ERROR_INCOMPATIBLE_MODE    = 3
        const val ERROR_TETHERING_DISALLOWED = 4
    }
}
