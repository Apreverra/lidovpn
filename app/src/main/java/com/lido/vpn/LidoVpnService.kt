package com.lido.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import libv2ray.Libv2ray
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler

class LidoVpnService : VpnService(), CoreCallbackHandler {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val channelId = "lido_vpn_channel"

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_STATE = "com.lido.vpn.STATE"
        const val EXTRA_STATE = "STATE"
    }

    private fun sendStateBroadcast(isConnected: Boolean) {
        val intent = Intent(ACTION_STATE)
        intent.putExtra(EXTRA_STATE, isConnected)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // CoreCallbackHandler implementation
    override fun onEmitStatus(status: Long, message: String?): Long {
        message?.let { sendLogToActivity(it) }
        return 0
    }

    override fun shutdown(): Long {
        return 0
    }

    override fun startup(): Long {
        return 0
    }

    private fun sendLogToActivity(message: String) {
        val intent = Intent("VPN_LOG")
        intent.putExtra("MESSAGE", message)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Log.d("LidoVpnService", "Core Log: $message")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val serverName = intent.getStringExtra("SERVER_NAME") ?: "VPN"
            val host = intent.getStringExtra("SERVER_HOST") ?: ""
            val port = intent.getIntExtra("SERVER_PORT", 443)
            val uuid = intent.getStringExtra("SERVER_UUID") ?: ""
            val type = intent.getStringExtra("SERVER_TYPE") ?: "VLESS"
            
            val params = mutableMapOf<String, String>()
            val paramsBundle = intent.getBundleExtra("SERVER_PARAMS")
            paramsBundle?.keySet()?.forEach { key ->
                paramsBundle.getString(key)?.let { params[key] = it }
            }
            
            val server = VpnServer(
                name = serverName,
                host = host,
                port = port,
                uuid = uuid,
                type = type,
                params = params,
            )
            
            val dns = intent.getStringExtra("DNS") ?: "1.1.1.1"
            val sniffing = intent.getBooleanExtra("SNIFFING", true)
            val mux = intent.getBooleanExtra("MUX", false)
            val fragment = intent.getBooleanExtra("FRAGMENT", false)
            val mtu = intent.getIntExtra("MTU", 1500)
            val routingMode = intent.getStringExtra("ROUTING_MODE") ?: "BYPASS_LAN_RU"
            val killSwitch = intent.getBooleanExtra("KILL_SWITCH", false)
            val ipv6Enabled = intent.getBooleanExtra("IPV6_ENABLED", false)
            val utlsFingerprint = intent.getStringExtra("UTLS_FINGERPRINT") ?: "chrome"
            val dpiPackets = intent.getStringExtra("DPI_PACKETS") ?: "tlshello"
            val dpiLength = intent.getStringExtra("DPI_LENGTH") ?: "100-200"
            val dpiInterval = intent.getStringExtra("DPI_INTERVAL") ?: "10-20"
            val dpiEngine = intent.getStringExtra("DPI_ENGINE") ?: "Xray"

            val appFilterEnabled = intent.getBooleanExtra("APP_FILTER_ENABLED", false)
            val appFilterBypass = intent.getBooleanExtra("APP_FILTER_BYPASS", false)
            val selectedApps = intent.getStringArrayExtra("SELECTED_APPS")?.toList() ?: emptyList()

            showNotification(serverName)
            startVpn(server, dns, sniffing, mux, fragment, mtu, routingMode, appFilterEnabled, appFilterBypass, selectedApps, killSwitch, ipv6Enabled, utlsFingerprint, dpiPackets, dpiLength, dpiInterval, dpiEngine)
            sendStateBroadcast(isConnected = true)
        } else if (action == ACTION_STOP) {
            stopVpn()
        }
        return START_STICKY
    }

    private fun showNotification(serverName: String) {
        val prefs = getSharedPreferences("vpn_settings", MODE_PRIVATE)
        val language = prefs.getString("language", "EN") ?: "EN"
        val isRu = language == "RU"

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, LidoVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (isRu) "VPN Подключен" else "VPN Connected")
            .setContentText(if (isRu) "Сервер: $serverName" else "Connected to $serverName")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (isRu) "Отключить" else "Disconnect",
                stopPendingIntent
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VPN Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startVpn(
        server: VpnServer,
        dns: String,
        sniffing: Boolean,
        mux: Boolean,
        fragment: Boolean,
        mtu: Int,
        routingMode: String,
        appFilterEnabled: Boolean = false,
        appFilterBypass: Boolean = false,
        selectedApps: List<String> = emptyList(),
        killSwitch: Boolean = false,
        ipv6Enabled: Boolean = false,
        utlsFingerprint: String = "chrome",
        dpiPackets: String = "tlshello",
        dpiLength: String = "100-200",
        dpiInterval: String = "10-20",
        dpiEngine: String = "Xray"
    ) {
        if ((vpnInterface != null) || (coreController != null)) {
            sendLogToActivity("Reconnecting...")
            try {
                coreController?.stopLoop()
            } catch (_: Exception) {}
            coreController = null
            
            try {
                vpnInterface?.close()
            } catch (_: Exception) {}
            vpnInterface = null
        }

        try {
            sendLogToActivity("Connecting...")
            
            // Мы больше не блокируем старт, если файлов нет, так как теперь используем встроенные правила
            val geoIpFile = java.io.File(filesDir, "geoip.dat")
            val geoSiteFile = java.io.File(filesDir, "geosite.dat")
            if (!geoIpFile.exists() || !geoSiteFile.exists()) {
                sendLogToActivity("Warning: Geo-data missing. Using built-in basic Russia bypass.")
            }

            val builder = Builder()
            builder.setSession("LidoVpn")
                .setMtu(mtu)
                .addAddress("10.0.0.2", 24)
                .addDnsServer(dns)
                .addRoute("0.0.0.0", 0)

            if (killSwitch) {
                // Kill Switch basically means we don't allow any traffic outside the VPN
                // In Android VpnService, we can achieve this by adding a blocking route
                // but usually it's handled by the OS if "Block connections without VPN" is enabled in settings.
                // For an app-level implementation, we ensure all traffic is routed to the interface.
            }
            
            if (!ipv6Enabled) {
                // By NOT adding IPv6 addresses/routes, we effectively block IPv6 traffic 
                // if the system doesn't have another path for it. 
            } else {
                builder.addAddress("fd00:1::2", 64)
                builder.addRoute("::", 0)
            }
            
            if (appFilterEnabled && selectedApps.isNotEmpty()) {
                if (appFilterBypass) {
                    // Selected apps connect directly
                    selectedApps.forEach { pkg ->
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (e: Exception) {
                            Log.e("LidoVpnService", "Failed to disallow $pkg", e)
                        }
                    }
                    // Always disallow ourselves to avoid loops
                    if (!selectedApps.contains(packageName)) {
                        try {
                            builder.addDisallowedApplication(packageName)
                        } catch (_: Exception) {}
                    }
                } else {
                    // Only selected apps use VPN
                    selectedApps.forEach { pkg ->
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (e: Exception) {
                            Log.e("LidoVpnService", "Failed to allow $pkg", e)
                        }
                    }
                }
            } else {
                // Default: exclude ourselves to avoid loops
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {}
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                sendLogToActivity("Error: connection failed")
                stopVpn()
                return
            }

            val fd = vpnInterface!!.fd
            val assetPath = filesDir.absolutePath
            
            // Устанавливаем пути КРАЙНЕ агрессивно
            try {
                System.setProperty("v2ray.location.asset", assetPath)
                System.setProperty("xray.location.asset", assetPath)
                android.system.Os.setenv("XRAY_LOCATION_ASSET", assetPath, true)
                android.system.Os.setenv("V2RAY_LOCATION_ASSET", assetPath, true)
            } catch (_: Exception) {}

            val isDpiOnlyMode = server.type == "DPI_ONLY"
            if (isDpiOnlyMode) {
                // Запускаем внешний бинарник ByeDPI только в режиме DPI Only
                val prefs = getSharedPreferences("vpn_settings", MODE_PRIVATE)
                val fullCmd = prefs.getString("dpi_command", "-o1 -d1 -s1") ?: "-o1 -d1 -s1"
                ByeDPIController.start(this, fullCmd, 1080)
            }

            val config = XrayConfigGenerator.generateConfig(
                server = server,
                dns = dns,
                sniffing = sniffing,
                mux = mux,
                fragment = fragment,
                routingMode = routingMode,
                mtu = mtu,
                assetPath = assetPath,
                utlsFingerprint = utlsFingerprint,
                dpiPackets = dpiPackets,
                dpiLength = dpiLength,
                dpiInterval = dpiInterval,
                socksProxyPort = if (isDpiOnlyMode) 1080 else 0
            )
            
            coreController = Libv2ray.newCoreController(this)
            coreController?.startLoop(config, fd)
            
            sendLogToActivity("Connected")
        } catch (e: Exception) {
            sendLogToActivity("Error: ${e.message}")
            Log.e("LidoVpnService", "Start failed", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        sendLogToActivity("Disconnecting...")
        sendStateBroadcast(isConnected = false)
        ByeDPIController.stop()
        try {
            coreController?.stopLoop()
        } catch (_: Exception) {}
        coreController = null
        
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendLogToActivity("Disconnected")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
