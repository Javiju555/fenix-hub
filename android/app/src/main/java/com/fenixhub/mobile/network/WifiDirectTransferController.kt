package com.fenixhub.mobile.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.fenixhub.mobile.model.WifiDirectGroupInfo
import com.fenixhub.mobile.model.WifiDirectTransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Controlador de transferencia real sobre WiFi Direct.
 *
 * Flujo Airdrop-like:
 *   1. BLE descubre un peer del mismo grupo → se intercambian deviceAddress
 *   2. El SENDER crea un grupo WiFi Direct (GO) e inicia su HTTP server en la interfaz p2p
 *   3. El RECEIVER se conecta al grupo como client
 *   4. Una vez conectados, el RECEIVER hace HTTP pull desde la IP del GO
 *   5. La transferencia usa el mismo protocolo FNX2/AES-GCM que LAN
 *
 * El GO (Group Owner) es siempre el que envía contenido. El client es quien recibe.
 * Esto simplifica el modelo: no necesitamos coordinación bidireccional.
 */
class WifiDirectTransferController(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifiP2pManager = appContext.getSystemService(WifiP2pManager::class.java)
    private val channel = wifiP2pManager?.initialize(appContext, Looper.getMainLooper(), null)

    private val _transferState = MutableStateFlow<WifiDirectTransferState>(WifiDirectTransferState.Idle)
    val transferState: StateFlow<WifiDirectTransferState> = _transferState.asStateFlow()

    private val _groupInfo = MutableStateFlow<WifiDirectGroupInfo?>(null)
    val groupInfo: StateFlow<WifiDirectGroupInfo?> = _groupInfo.asStateFlow()

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var isGroupOwner = false

    @Volatile
    private var p2pInterface: String? = null

    // ── RECEIVER: Obtener información del grupo existente ──────────────────────

    /**
     * Solicita info del grupo actual. Útil cuando el RECEIVER ya se conectó
     * y necesita obtener la IP del GO.
     */
    fun requestGroupInfo(callback: (WifiDirectGroupInfo?) -> Unit) {
        val manager = wifiP2pManager ?: return callback(null)
        val ch = channel ?: return callback(null)

        manager.requestGroupInfo(ch) { group ->
            val info = group.toGroupInfo()
            _groupInfo.value = info
            if (info != null) {
                Log.d(TAG, "Group info: GO=${info.groupOwnerAddress}, isGO=${info.isGroupOwner}, clients=${info.clients.size}")
            }
            callback(info)
        }
    }

    /**
     * SENDER: Crea un grupo WiFi Direct. Este dispositivo será el Group Owner.
     * El HTTP server debe arrancar DESPUÉS de que el grupo se cree,
     * escuchando en la interfaz p2p (normalmente p2p-wlan0).
     */
    fun createGroup(onGroupCreated: (WifiDirectGroupInfo) -> Unit) {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        registerReceiverIfNeeded()
        _transferState.value = WifiDirectTransferState.CreatingGroup
        isGroupOwner = true

        manager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "WiFi Direct group created, requesting info...")
                // Dar tiempo a que el SO configure la interfaz p2p
                manager.requestGroupInfo(ch) { group ->
                    val info = group.toGroupInfo()
                    if (info != null) {
                        _groupInfo.value = info
                        p2pInterface = findP2pInterface()
                        Log.i(TAG, "Group GO ready: ${info.groupOwnerAddress} on ${p2pInterface ?: "unknown iface"}")
                        onGroupCreated(info)
                    } else {
                        Log.w(TAG, "Group created but info is null, retrying...")
                        // Retry después de un breve delay
                        android.os.Handler(Looper.getMainLooper()).postDelayed({
                            manager.requestGroupInfo(ch) { retry ->
                                val retryInfo = retry.toGroupInfo()
                                _groupInfo.value = retryInfo
                                p2pInterface = findP2pInterface()
                                if (retryInfo != null) {
                                    Log.i(TAG, "Group GO ready (retry): ${retryInfo.groupOwnerAddress}")
                                    onGroupCreated(retryInfo)
                                } else {
                                    _transferState.value = WifiDirectTransferState.Error("No se pudo obtener info del grupo")
                                }
                            }
                        }, 1500)
                    }
                }
            }

            override fun onFailure(reason: Int) {
                _transferState.value = WifiDirectTransferState.Error("create_group_failed_$reason")
                Log.w(TAG, "createGroup failed: $reason")
            }
        })
    }

    /**
     * RECEIVER: Conecta a un grupo WiFi Direct existente.
     * [goDeviceAddress] es la dirección MAC del dispositivo GO (obtenida por BLE).
     * [goDeviceAddress] puede ser null si se conecta por SSID/password (hotspot mode).
     */
    @Suppress("DEPRECATION")
    fun connectToGroup(
        goDeviceAddress: String? = null,
        passphrase: String? = null,
        networkName: String? = null,
        onConnected: (WifiDirectGroupInfo) -> Unit,
    ) {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        registerReceiverIfNeeded()
        isGroupOwner = false

        if (goDeviceAddress != null) {
            val config = WifiP2pConfig().apply {
                deviceAddress = goDeviceAddress
                // WPS setup for push-button connection
                wps.setup = android.net.wifi.WpsInfo.PBC
            }
            _transferState.value = WifiDirectTransferState.Connecting(goDeviceAddress)

            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "connect() succeeded, waiting for connection info...")
                    waitForConnectionInfo(manager, ch, onConnected)
                }

                override fun onFailure(reason: Int) {
                    _transferState.value = WifiDirectTransferState.Error("connect_failed_$reason")
                    Log.w(TAG, "connect failed: $reason")
                }
            })
        } else {
            // Modo fallback: conectar a un grupo conocido por SSID (usando WPS PIN o clave)
            // Esto se usa cuando el GO está creando un hotspot y el client ya conoce la red
            _transferState.value = WifiDirectTransferState.Connecting("unknown")
            waitForConnectionInfo(manager, ch, onConnected)
        }
    }

    private fun waitForConnectionInfo(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onConnected: (WifiDirectGroupInfo) -> Unit,
    ) {
        manager.requestConnectionInfo(ch) { info ->
            if (info.groupFormed) {
                val goAddr = info.groupOwnerAddress?.hostAddress ?: "unknown"
                val info2 = WifiDirectGroupInfo(
                    groupOwnerAddress = goAddr,
                    isGroupOwner = info.isGroupOwner,
                    clients = emptyList(),
                )
                _groupInfo.value = info2
                p2pInterface = findP2pInterface()
                Log.i(TAG, "Connected to group: GO=$goAddr, isGO=${info.isGroupOwner}")
                onConnected(info2)
            } else {
                // La conexión aún no está lista, reintentar
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    waitForConnectionInfo(manager, ch, onConnected)
                }, 1000)
            }
        }
    }

    /**
     * Marca la transferencia como completada y desconecta el grupo.
     */
    fun transferComplete(contentId: String) {
        _transferState.value = WifiDirectTransferState.Complete(contentId)
        Log.i(TAG, "Transfer complete: $contentId")
    }

    /**
     * Limpia todo: desconecta grupo, remueve receiver.
     */
    fun cleanup() {
        val manager = wifiP2pManager
        val ch = channel

        if (manager != null && ch != null) {
            runCatching { manager.removeGroup(ch, null) }
        }

        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(wifiDirectReceiver) }
            receiverRegistered = false
        }

        _transferState.value = WifiDirectTransferState.Idle
        _groupInfo.value = null
        isGroupOwner = false
        p2pInterface = null
    }

    /**
     * Obtiene la IP local de la interfaz p2p. El HTTP server debe escuchar en esta IP.
     * Devuelve null si no hay interfaz p2p activa.
     */
    fun getP2pLocalAddress(): String? {
        return findP2pInterfaceAddress()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun findP2pInterface(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.startsWith("p2p") && iface.isUp && !iface.isLoopback) {
                    return@runCatching iface.name
                }
            }
            null
        }.getOrNull()
    }

    private fun findP2pInterfaceAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.startsWith("p2p") && iface.isUp && !iface.isLoopback) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address) {
                            return@runCatching addr.hostAddress ?: continue
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiDirectReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(wifiDirectReceiver, filter)
        }
        receiverRegistered = true
    }

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val manager = wifiP2pManager ?: return
                    val ch = channel ?: return
                    manager.requestConnectionInfo(ch) { info ->
                        if (info.groupFormed) {
                            val goAddr = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                            val current = _groupInfo.value
                            if (current == null || current.groupOwnerAddress != goAddr) {
                                _groupInfo.value = WifiDirectGroupInfo(
                                    groupOwnerAddress = goAddr,
                                    isGroupOwner = info.isGroupOwner,
                                    clients = current?.clients ?: emptyList(),
                                )
                                p2pInterface = findP2pInterface()
                                Log.d(TAG, "Connection changed: GO=$goAddr, isGO=${info.isGroupOwner}")
                            }
                        } else {
                            if (_groupInfo.value != null) {
                                Log.d(TAG, "Disconnected from group")
                                _groupInfo.value = null
                                p2pInterface = null
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val device = intent?.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            android.net.wifi.p2p.WifiP2pDevice::class.java,
                        )
                        device?.let { Log.d(TAG, "This device: ${it.deviceName} (${it.deviceAddress})") }
                    }
                }
            }
        }
    }

    private fun WifiP2pGroup?.toGroupInfo(): WifiDirectGroupInfo? {
        val group = this ?: return null
        val ownerMac = group.owner?.deviceAddress ?: return null
        val p2pIp = findP2pInterfaceAddress() ?: ownerMac
        return WifiDirectGroupInfo(
            groupOwnerAddress = if (group.isGroupOwner) p2pIp else ownerMac,
            isGroupOwner = group.isGroupOwner,
            clients = group.clientList.map { it.deviceAddress },
        )
    }

    private companion object {
        const val TAG = "WifiDirectTransfer"
    }
}
