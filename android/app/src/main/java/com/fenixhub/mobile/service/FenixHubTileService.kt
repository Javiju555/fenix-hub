package com.fenixhub.mobile.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class FenixHubTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        startForegroundService(
            Intent(this, FenixHubService::class.java).setAction(FenixHubService.ACTION_SHOW_OVERLAY),
        )
    }
}
