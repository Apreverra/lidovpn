package com.lido.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.google.gson.Gson

class VpnTileService : TileService() {
    private val gson = Gson()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LidoVpnService.ACTION_STATE) {
                updateTile()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        val filter = IntentFilter(LidoVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {}
    }

    override fun onClick() {
        super.onClick()
        val isRunning = isServiceRunning()
        if (isRunning) {
            val intent = Intent(this, LidoVpnService::class.java).apply {
                action = LidoVpnService.ACTION_STOP
            }
            startService(intent)
        } else {
            val prefs = getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            val selectedServerJson = prefs.getString("selected_server", null)
            
            if (selectedServerJson != null) {
                // Check if VPN permission is already granted
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent == null) {
                    // Permission already granted, start service directly
                    try {
                        val server = gson.fromJson(selectedServerJson, VpnServer::class.java)
                        val intent = Intent(this, LidoVpnService::class.java).apply {
                            action = LidoVpnService.ACTION_START
                            putExtra("SERVER_NAME", server.name)
                            putExtra("SERVER_HOST", server.host)
                            putExtra("SERVER_PORT", server.port)
                            putExtra("SERVER_UUID", server.uuid)
                            putExtra("SERVER_TYPE", server.type)
                            
                            val paramsBundle = Bundle()
                            server.params.forEach { (k, v) -> paramsBundle.putString(k, v) }
                            putExtra("SERVER_PARAMS", paramsBundle)

                            putExtra("DNS", prefs.getString("dns_server", "1.1.1.1"))
                            putExtra("SNIFFING", prefs.getBoolean("sniffing", true))
                            putExtra("MUX", prefs.getBoolean("mux", false))
                            putExtra("FRAGMENT", prefs.getBoolean("fragment", false))
                            putExtra("MTU", prefs.getInt("mtu", 1500))
                            putExtra("ROUTING_MODE", prefs.getString("routing_mode", "GLOBAL"))
                            
                            putExtra("APP_FILTER_ENABLED", prefs.getBoolean("app_filter_enabled", false))
                            putExtra("APP_FILTER_BYPASS", prefs.getBoolean("app_filter_bypass", false))
                            val appsSet = prefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
                            putExtra("SELECTED_APPS", appsSet.toTypedArray())
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        return
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // Fallback: Open app if no server or permission needed
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (selectedServerJson != null) {
                    putExtra("AUTO_START", true)
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = isServiceRunning()
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        
        // Label from string resource
        tile.label = getString(R.string.app_name)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) "Connected" else "Disconnected"
        }
        tile.updateTile()
    }

    private fun isServiceRunning(): Boolean {
        return LidoVpnService.isServiceRunning
    }
}
