package com.foxconnect.core.engine

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.foxconnect.core.model.ConnectionState

class FoxVpnTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val state = TunnelRuntime.snapshot.value.state
        if (state !is ConnectionState.Disconnected) {
            AndroidTunnelController(this).disconnect()
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.updateTile()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (launchIntent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    21,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launchIntent)
            }
        }
    }

    private fun updateTile() {
        val state = TunnelRuntime.snapshot.value.state
        qsTile?.apply {
            this.state = when (state) {
                is ConnectionState.Connected,
                is ConnectionState.Connecting,
                is ConnectionState.Switching -> Tile.STATE_ACTIVE
                is ConnectionState.Failed -> Tile.STATE_UNAVAILABLE
                ConnectionState.Disconnected -> Tile.STATE_INACTIVE
            }
            label = getString(R.string.vpn_tile_label)
            updateTile()
        }
    }
}
