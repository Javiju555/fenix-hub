package com.fenixhub.mobile

import android.app.Application
import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.ReceivedContentHandler
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.data.TempClipboardStore
import com.fenixhub.mobile.network.BleDirectController
import com.fenixhub.mobile.network.BleIdentityController
import com.fenixhub.mobile.network.EphemeralDirectSession
import com.fenixhub.mobile.network.FenixHttpClient
import com.fenixhub.mobile.network.FenixHttpServer
import com.fenixhub.mobile.network.WifiDirectController
import com.fenixhub.mobile.network.WifiDirectTransferController
import com.fenixhub.mobile.service.HotspotManager
import com.fenixhub.mobile.service.MeshManager
import com.fenixhub.mobile.util.LocalContentFactory

class FenixHubApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val settingsStore = SettingsStore(application)
    val tempClipboardStore = TempClipboardStore(application).also { it.clearAll() }
    val contentRepository = ContentRepository(tempClipboardStore)
    val localContentFactory = LocalContentFactory(tempClipboardStore)
    val receivedContentHandler = ReceivedContentHandler(application, tempClipboardStore)
    val httpClient = FenixHttpClient()
    val bleIdentityController = BleIdentityController(application)
    val wifiDirectController = WifiDirectController(application)
    val wifiDirectTransferController = WifiDirectTransferController(application)
    val bleDirectController = BleDirectController(application)
    val hotspotManager = HotspotManager(application)
    val meshManager = MeshManager(application, bleDirectController, wifiDirectTransferController)
    val ephemeralSession: EphemeralDirectSession by lazy {
        EphemeralDirectSession(
            context = application,
            bleController = bleDirectController,
            transferController = wifiDirectTransferController,
            contentRepository = contentRepository,
            receivedHandler = receivedContentHandler,
            httpClient = httpClient,
            httpServer = FenixHttpServer(settingsStore, contentRepository),
        )
    }
}
