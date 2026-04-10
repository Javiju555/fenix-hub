package com.fenixhub.mobile.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat

data class BleHardwareInfo(
    val supported: Boolean,
    val enabled: Boolean,
    val permissionsReady: Boolean,
    val adapterName: String?,
)

data class WifiDirectHardwareInfo(
    val supported: Boolean,
    val enabled: Boolean,
    val permissionsReady: Boolean,
)

data class TransportHardwareSnapshot(
    val ble: BleHardwareInfo,
    val wifiDirect: WifiDirectHardwareInfo,
)

object TransportHardwareInspector {
    fun snapshot(context: Context): TransportHardwareSnapshot {
        return TransportHardwareSnapshot(
            ble = inspectBle(context),
            wifiDirect = inspectWifiDirect(context),
        )
    }

    private fun inspectBle(context: Context): BleHardwareInfo {
        val pm = context.packageManager
        val hasBleFeature = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        val enabled = adapter?.isEnabled == true
        val permissionsReady = hasBlePermissions(context)

        return BleHardwareInfo(
            supported = hasBleFeature && adapter != null,
            enabled = enabled,
            permissionsReady = permissionsReady,
            adapterName = runCatching { adapter?.name }.getOrNull(),
        )
    }

    private fun inspectWifiDirect(context: Context): WifiDirectHardwareInfo {
        val pm = context.packageManager
        val hasWifiDirectFeature = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val wifiP2pManager = context.getSystemService(WifiP2pManager::class.java)
        val wifiManager = context.getSystemService(WifiManager::class.java)

        val enabled = wifiManager?.isWifiEnabled == true
        val permissionsReady = hasWifiDirectPermissions(context)

        return WifiDirectHardwareInfo(
            supported = hasWifiDirectFeature && wifiP2pManager != null,
            enabled = enabled,
            permissionsReady = permissionsReady,
        )
    }

    fun hasBlePermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        return hasPermission(context, Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun hasBleAdvertisePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        return hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
    }

    fun hasWifiDirectPermissions(context: Context): Boolean {
        val hasFineLocation = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) || hasFineLocation
        }
        return hasFineLocation
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}