package com.lido.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
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
    
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastStartIntent: Intent? = null

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        
        val cm = connectivityManager ?: return
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            private var lastPhysicalNetwork: Network? = null

            override fun onAvailable(network: Network) {
                // This callback is only for physical networks due to the request filter below
                Log.d("LidoVpnService", "Network Available: $network (Last: $lastPhysicalNetwork)")
                if (lastPhysicalNetwork != null && lastPhysicalNetwork != network) {
                    Log.d("LidoVpnService", "Physical network changed from $lastPhysicalNetwork to $network, triggering reconnect")
                    sendLogToActivity("Network changed. Reconnecting...")
                    lastStartIntent?.let { intent ->
                        // Re-run start command with same parameters
                        onStartCommand(intent, 0, 0)
                    }
                }
                lastPhysicalNetwork = network
            }

            override fun onLost(network: Network) {
                if (network == lastPhysicalNetwork) {
                    sendLogToActivity("Connection lost.")
                    lastPhysicalNetwork = null
                }
            }
        }
        
        try {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e("LidoVpnService", "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    companion object {
        @Volatile var isServiceRunning = false
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
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            isServiceRunning = true
            lastStartIntent = intent
            registerNetworkCallback()
            
            val prefs = getSharedPreferences("vpn_settings", MODE_PRIVATE)
            val isRu = (prefs.getString("language", "EN") ?: "EN") == "RU"
            
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
            
            // State Synchronization: Save connected server to prefs immediately
            val gson = com.google.gson.Gson()
            prefs.edit().putString("connected_server", gson.toJson(server)).apply()
            
            val dns = intent.getStringExtra("DNS") ?: "1.1.1.1"
            val sniffing = intent.getBooleanExtra("SNIFFING", true)
            val mux = intent.getBooleanExtra("MUX", false)
            val mtu = intent.getIntExtra("MTU", 1500)
            val routingMode = intent.getStringExtra("ROUTING_MODE") ?: "GLOBAL"
            val killSwitch = intent.getBooleanExtra("KILL_SWITCH", false)
            val ipv6Enabled = intent.getBooleanExtra("IPV6_ENABLED", false)
            val utlsFingerprint = intent.getStringExtra("UTLS_FINGERPRINT") ?: "chrome"

            val isByeDpiEnabled = intent.getBooleanExtra("BYEDPI_ENABLED", false)
            val byeDpiArgs = intent.getStringExtra("BYEDPI_ARGS") ?: ""
            val byeDpiAddress = intent.getStringExtra("BYEDPI_LISTEN_ADDRESS") ?: "127.0.0.1"
            val byeDpiPort = intent.getIntExtra("BYEDPI_LISTEN_PORT", 10808)
            val byeDpiDns = intent.getStringExtra("BYEDPI_DNS") ?: "8.8.8.8"
            val listenPort = intent.getIntExtra("LISTEN_PORT", 10808)

            val appFilterEnabled = intent.getBooleanExtra("APP_FILTER_ENABLED", false)
            val appFilterBypass = intent.getBooleanExtra("APP_FILTER_BYPASS", false)
            val selectedApps = intent.getStringArrayExtra("SELECTED_APPS")?.toList() ?: emptyList()

            val displayTitle = if (isByeDpiEnabled) {
                if (isRu) "Режим ByeDPI" else "ByeDPI Engine"
            } else {
                serverName
            }
            showNotification(displayTitle)

            if (isByeDpiEnabled) {
                startByeDpiVpn(mtu, dns, byeDpiArgs, byeDpiAddress, byeDpiPort, appFilterEnabled, appFilterBypass, selectedApps, ipv6Enabled, byeDpiDns)
            } else {
                startVpn(
                    server, dns, sniffing, mux, mtu, routingMode, appFilterEnabled, appFilterBypass, selectedApps, 
                    killSwitch,
                    ipv6Enabled,
                    utlsFingerprint,
                    listenPort,
                )
            }
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
            .setSmallIcon(R.drawable.ic_vpn_shield)
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
        mtu: Int,
        routingMode: String,
        appFilterEnabled: Boolean = false,
        appFilterBypass: Boolean = false,
        selectedApps: List<String> = emptyList(),
        killSwitch: Boolean = false,
        ipv6Enabled: Boolean = false,
        utlsFingerprint: String = "chrome",
        socksInboundPort: Int = 10808
    ) {
        val assetPath = filesDir.absolutePath
        
        // FORCE SET ASSET PATH BEFORE ANYTHING ELSE
        try {
            System.setProperty("v2ray.location.asset", assetPath)
            System.setProperty("xray.location.asset", assetPath)
            android.system.Os.setenv("XRAY_LOCATION_ASSET", assetPath, true)
            android.system.Os.setenv("V2RAY_LOCATION_ASSET", assetPath, true)
        } catch (_: Exception) {}

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

            if (killSwitch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // On some devices this helps enforce the VPN tunnel
                builder.setMetered(false)
            }
            
            if (!ipv6Enabled) {
                // Prevent IPv6 leak by routing it to TUN and dropping it
                try {
                    builder.addAddress("fd00:1::2", 128)
                    builder.addRoute("::", 0)
                } catch (e: Exception) {
                    Log.e("LidoVpnService", "Failed to add IPv6 leak protection route", e)
                }
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
            
            val config = XrayConfigGenerator.generateConfig(
                server = server,
                dns = dns,
                sniffing = sniffing,
                mux = mux,
                routingMode = routingMode,
                mtu = mtu,
                assetPath = assetPath,
                utlsFingerprint = utlsFingerprint,
                socksInboundPort = socksInboundPort
            )

            sendLogToActivity("Starting Xray core...")
            coreController = Libv2ray.newCoreController(this)
            
            try {
                coreController?.startLoop(config, fd)
                sendLogToActivity("Xray core loop started")
            } catch (e: Throwable) {
                sendLogToActivity("Core Start FATAL: ${e.message}")
                Log.e("LidoVpnService", "Core loop crash", e)
                stopVpn()
            }
            
            sendLogToActivity("Connected")
        } catch (e: Exception) {
            sendLogToActivity("Error: ${e.message}")
            Log.e("LidoVpnService", "Start failed", e)
            stopVpn()
        }
    }

    private fun startByeDpiVpn(
        mtu: Int,
        dns: String,
        byeDpiArgs: String,
        byeDpiAddress: String,
        byeDpiPort: Int,
        appFilterEnabled: Boolean,
        appFilterBypass: Boolean,
        selectedApps: List<String>,
        ipv6Enabled: Boolean,
        byeDpiDns: String = "8.8.8.8"
    ) {
        stopVpnCore()
        try {
            sendLogToActivity("Starting ByeDPI mode...")
            
            try {
                ByeDPIController.start(this, byeDpiArgs, byeDpiAddress, byeDpiPort)
            } catch (e: Exception) {
                sendLogToActivity("ByeDPI Failed to start: ${e.message}")
                stopVpn()
                return
            }
            
            val builder = Builder()
            builder.setSession("LidoByeDPI")
                .setMtu(mtu)
                .addAddress("10.0.0.1", 24)
                .addDnsServer(dns)
                .addRoute("0.0.0.0", 0)

            if (ipv6Enabled) {
                builder.addAddress("fd00:1::1", 64)
                builder.addRoute("::", 0)
            }

            if (appFilterEnabled && selectedApps.isNotEmpty()) {
                if (appFilterBypass) {
                    selectedApps.forEach { try { builder.addDisallowedApplication(it) } catch (_: Exception) {} }
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                } else {
                    selectedApps.forEach { try { builder.addAllowedApplication(it) } catch (_: Exception) {} }
                }
            } else {
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }

            val vpnInterface = builder.establish()
            if (vpnInterface == null) {
                sendLogToActivity("Error: VPN establishment failed")
                stopVpn()
                return
            }
            this.vpnInterface = vpnInterface
            val fd = vpnInterface.fd

            val bridgeConfig = XrayConfigGenerator.generateByeDpiBridgeConfig(
                byeDpiAddress = byeDpiAddress,
                byeDpiPort = byeDpiPort,
                dns = byeDpiDns,
                mtu = mtu
            )
            
            coreController = Libv2ray.newCoreController(this)
            coreController?.startLoop(bridgeConfig, fd)

            sendLogToActivity("ByeDPI VPN Connected (Integrated Stack)")
        } catch (e: Exception) {
            sendLogToActivity("ByeDPI Error: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpnCore() {
        try {
            coreController?.stopLoop()
        } catch (_: Exception) {}
        coreController = null
        
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        
        ByeDPIController.stop()
    }

    private fun stopVpn() {
        sendLogToActivity("Disconnecting...")
        isServiceRunning = false
        sendStateBroadcast(isConnected = false)
        unregisterNetworkCallback()
        lastStartIntent = null
        
        // Clear state sync
        getSharedPreferences("vpn_settings", MODE_PRIVATE).edit().remove("connected_server").apply()
        
        stopVpnCore()
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendLogToActivity("Disconnected")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
