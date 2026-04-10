package com.fenixhub.mobile

import android.app.Application
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.ReceivedContentHandler
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.data.TempClipboardStore
import com.fenixhub.mobile.network.BleIdentityController
import com.fenixhub.mobile.network.FenixHttpClient
import com.fenixhub.mobile.network.WifiDirectController
import com.fenixhub.mobile.service.HotspotManager
import com.fenixhub.mobile.util.LocalContentFactory

class FenixHubApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val settingsStore = SettingsStore(application)
    val contentRepository = ContentRepository()
    val tempClipboardStore = TempClipboardStore(application).also { it.clearAll() }
    val localContentFactory = LocalContentFactory(tempClipboardStore)
    val receivedContentHandler = ReceivedContentHandler(application, tempClipboardStore)
    val httpClient = FenixHttpClient()
    val bleIdentityController = BleIdentityController(application)
    val wifiDirectController = WifiDirectController(application)
    /** Hotspot local (sin internet) para conectar dispositivos sin red WiFi externa. */
    val hotspotManager = HotspotManager(application)
}
