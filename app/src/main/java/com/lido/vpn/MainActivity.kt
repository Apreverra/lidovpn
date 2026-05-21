package com.lido.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lido.vpn.ui.theme.VpnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
 import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import libv2ray.Libv2ray

class MainActivity : ComponentActivity() {
    companion object {
        var instance: MainActivity? = null
            private set
    }
    
    var viewModel: AppViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            viewModel = vm
            
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { /* ... */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val vpnPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    vm.onVpnPermissionGranted()
                }
            }

            LaunchedEffect(vm.vpnPermissionIntent) {
                vm.vpnPermissionIntent?.let {
                    vpnPermissionLauncher.launch(it)
                    vm.vpnPermissionIntent = null
                }
            }

            VpnTheme(darkTheme = vm.isDarkTheme) {
                VpnApp(vm)
            }

            LaunchedEffect(intent) {
                if (intent?.getBooleanExtra("AUTO_START", false) == true) {
                    vm.toggleVpn(this@MainActivity)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}

@Composable
fun VpnApp(viewModel: AppViewModel = viewModel()) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                val label = when(it) {
                    AppDestinations.HOME -> if (viewModel.language == AppLanguage.RU) "Главная" else "Home"
                    AppDestinations.SERVERS -> if (viewModel.language == AppLanguage.RU) "Серверы" else "Servers"
                    AppDestinations.LOGS -> if (viewModel.language == AppLanguage.RU) "Логи" else "Logs"
                    AppDestinations.SETTINGS -> if (viewModel.language == AppLanguage.RU) "Настройки" else "Settings"
                }
                item(
                    icon = {
                        Icon(
                            imageVector = it.icon,
                            contentDescription = label
                        )
                    },
                    label = { Text(label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) }
        ) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            when (currentDestination) {
                AppDestinations.HOME -> HomeScreen(viewModel, modifier = contentModifier)
                AppDestinations.SERVERS -> ServersScreen(viewModel, modifier = contentModifier)
                AppDestinations.LOGS -> LogsScreen(viewModel, modifier = contentModifier)
                AppDestinations.SETTINGS -> SettingsScreen(viewModel, modifier = contentModifier)
            }
        }
    }
}

data class VpnServer(
    val name: String,
    val type: String, // vless, trojan, vmess
    val host: String,
    val port: Int,
    val uuid: String,
    val params: Map<String, String> = emptyMap(),
    val status: ServerStatus = ServerStatus.UNKNOWN,
    val ping: Long? = null,
    val pingTelegram: Long? = null,
    val rawUrl: String = "",
    val country: String = ""
)

enum class ServerStatus {
    UNKNOWN, WORKING, NOT_WORKING
}

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?
)

data class ConfigSource(val name: String, val url: String)
data class ConfigCategory(val name: String, val items: List<ConfigSource>)

data class VpnUpdateInfo(
    val version: String,
    val description: String,
    val downloadUrl: String,
    val forceUpdate: Boolean = false
)

class AppViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    var isDarkTheme by mutableStateOf(prefs.getBoolean("dark_theme", true))
    var language by mutableStateOf(AppLanguage.valueOf(prefs.getString("language", "EN") ?: "EN"))
    var selectedSources by mutableStateOf(
        prefs.getStringSet("selected_sources", emptySet()) ?: emptySet()
    )

    val configCategories = listOf(
        ConfigCategory("Обычные конфиги (default/)", listOf(
            ConfigSource("1", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/1.txt"),
            ConfigSource("6", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/6.txt"),
            ConfigSource("22", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/22.txt"),
            ConfigSource("23", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/23.txt"),
            ConfigSource("24", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/24.txt"),
            ConfigSource("25", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/25.txt"),
            ConfigSource("all.txt", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/all.txt"),
            ConfigSource("all-secure.txt", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/all-secure.txt")
        )),
        ConfigCategory("Конфиги для обхода SNI/CIDR (bypass/)", mutableListOf(
            ConfigSource("bypass-all", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-all.txt")
        ).apply {
            addAll((1..15).map { ConfigSource("bypass-$it", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-$it.txt") })
        }),
        ConfigCategory("Небезопасные конфиги (bypass-unsecure/)", mutableListOf(
            ConfigSource("bypass-unsecure-all", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass-unsecure/bypass-unsecure-all.txt")
        ).apply {
            addAll((1..16).map { ConfigSource("bypass-unsecure-$it", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass-unsecure/bypass-unsecure-$it.txt") })
        }),
    )
    var dnsServer by mutableStateOf(prefs.getString("dns_server", "1.1.1.1") ?: "1.1.1.1")
    var routingMode by mutableStateOf(RoutingMode.valueOf(prefs.getString("routing_mode", "BYPASS_LAN_RU") ?: "BYPASS_LAN_RU"))
    var isSniffingEnabled by mutableStateOf(prefs.getBoolean("sniffing", true))
    var isMuxEnabled by mutableStateOf(prefs.getBoolean("mux", false))
    var isFragmentEnabled by mutableStateOf(prefs.getBoolean("fragment", false))
    var mtu by mutableIntStateOf(prefs.getInt("mtu", 1500))
    var concurrentChecks by mutableIntStateOf(prefs.getInt("concurrent_checks", 15))

    var isKillSwitchEnabled by mutableStateOf(prefs.getBoolean("kill_switch", false))
    var isIpv6Enabled by mutableStateOf(prefs.getBoolean("ipv6_enabled", false))
    var utlsFingerprint by mutableStateOf(prefs.getString("utls_fingerprint", "chrome") ?: "chrome")

    var isAppFilterEnabled by mutableStateOf(prefs.getBoolean("app_filter_enabled", false))
    var isBypassMode by mutableStateOf(prefs.getBoolean("app_filter_bypass", false))
    var selectedApps by mutableStateOf(prefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
    var installedApps by mutableStateOf<List<AppInfo>>(emptyList())
    var isAppsLoading by mutableStateOf(false)

    fun loadApps() {
        if (installedApps.isNotEmpty()) return
        viewModelScope.launch {
            isAppsLoading = true
            withContext(Dispatchers.IO) {
                val pm = getApplication<android.app.Application>().packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { (pm.getLaunchIntentForPackage(it.packageName) != null) }
                    .map { app ->
                        AppInfo(
                            name = pm.getApplicationLabel(app).toString(),
                            packageName = app.packageName,
                            icon = pm.getApplicationIcon(app)
                        )
                    }
                    .sortedBy { it.name }
                withContext(Dispatchers.Main) {
                    installedApps = apps
                    isAppsLoading = false
                }
            }
        }
    }

    fun updateAppFilterEnabled(value: Boolean) {
        isAppFilterEnabled = value
        prefs.edit { putBoolean("app_filter_enabled", value) }
        restartVpnIfConnected()
    }

    fun updateBypassMode(value: Boolean) {
        isBypassMode = value
        prefs.edit { putBoolean("app_filter_bypass", value) }
        restartVpnIfConnected()
    }

    fun toggleAppSelection(packageName: String) {
        val newSelected = if (selectedApps.contains(packageName)) {
            selectedApps - packageName
        } else {
            selectedApps + packageName
        }
        selectedApps = newSelected
        prefs.edit { putStringSet("selected_apps", newSelected) }
        restartVpnIfConnected()
    }

    private fun restartVpnIfConnected() {
        if (isConnected) {
            connectedServer?.let { startVpn(it) }
        }
    }

    var pingMethod by mutableStateOf(PingMethod.valueOf(prefs.getString("ping_method", "TCP") ?: "TCP"))
    var pingTargetUrl by mutableStateOf(prefs.getString("ping_target", "https://www.gstatic.com/generate_204") ?: "https://www.gstatic.com/generate_204")

    private val _servers = mutableStateListOf<VpnServer>().apply { addAll(loadServers()) }
    var servers: List<VpnServer> 
        get() = _servers
        set(value) {
            _servers.clear()
            _servers.addAll(value)
        }

    private val countryCache = mutableMapOf<String, String>()
    private val geoSemaphore = Semaphore(10) // Ограничиваем только запросы к GeoIP API

    var isFetching by mutableStateOf(false)
    var isChecking by mutableStateOf(false)
    var isCheckingTelegram by mutableStateOf(false)

    var isCheckingUpdate by mutableStateOf(false)
    var updateInfo by mutableStateOf<VpnUpdateInfo?>(null)
    var isDownloadingUpdate by mutableStateOf(false)
    var downloadProgress by mutableFloatStateOf(0f)
    var downloadSpeed by mutableStateOf("")
    var downloadedSizeInfo by mutableStateOf("")

    val snackbarHostState = SnackbarHostState()

    fun showSnackbar(message: String) {
        viewModelScope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    private fun cancelCheck() {
        checkJob?.cancel()
        checkJob = null
        isChecking = false
        isCheckingTelegram = false
    }

    var sortOrder by mutableStateOf(SortOrder.PING_TG)

    private var checkJob: Job? = null

    var isConnected by mutableStateOf(false)
    var connectedServer by mutableStateOf<VpnServer?>(null)
    var selectedServer by mutableStateOf(loadSelectedServer())
    var connectingServer by mutableStateOf<VpnServer?>(null)
    var vpnPermissionIntent by mutableStateOf<Intent?>(null)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LidoVpnService.ACTION_STATE) {
                isConnected = intent.getBooleanExtra(LidoVpnService.EXTRA_STATE, false)
                if (!isConnected) {
                    connectedServer = null
                }
            }
        }
    }

    // Geo Data
    var geoFilesInfo by mutableStateOf(GeoDataManager.getGeoFilesInfo(application))
    var isDownloadingGeo by mutableStateOf(false)

    fun refreshGeoInfo() {
        viewModelScope.launch {
            val localInfo = GeoDataManager.getGeoFilesInfo(getApplication())
            val remoteVersions = GeoDataManager.getRemoteVersions()
            geoFilesInfo = localInfo.map { it.copy(remoteVersion = remoteVersions[it.name] ?: "") }
        }
    }

    init {
        // Filter out orphaned sources (e.g. after URL updates)
        val allValidUrls = configCategories.flatMap { it.items }.map { it.url }.toSet()
        if (selectedSources.any { it !in allValidUrls }) {
            val newSources = selectedSources.filter { it in allValidUrls }.toSet()
            selectedSources = newSources
            prefs.edit { putStringSet("selected_sources", newSources) }
        }

        refreshGeoInfo()
        val filter = IntentFilter(LidoVpnService.ACTION_STATE)
        ContextCompat.registerReceiver(application, stateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        
        // Check if service is already running
        val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (LidoVpnService::class.java.name == service.service.className) {
                isConnected = true
                break
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<android.app.Application>().unregisterReceiver(stateReceiver)
    }

    fun downloadGeoData() {
        viewModelScope.launch {
            isDownloadingGeo = true
            GeoDataManager.downloadGeoFiles(getApplication()) { message ->
                LogManager.addLog(message)
            }
            refreshGeoInfo()
            isDownloadingGeo = false
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            isCheckingUpdate = true
            try {
                val request = Request.Builder()
                    .url("https://raw.githubusercontent.com/whoahaow/rjsxrd/main/update.json")
                    .build()
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            val latest = gson.fromJson(body, VpnUpdateInfo::class.java)
                            val current = getApplication<android.app.Application>().packageManager
                                .getPackageInfo(getApplication<android.app.Application>().packageName, 0).versionName
                            if (latest.version != current) {
                                withContext(Dispatchers.Main) {
                                    updateInfo = latest
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    fun downloadAndInstallUpdate(context: Context, info: VpnUpdateInfo) {
        viewModelScope.launch {
            isDownloadingUpdate = true
            downloadProgress = 0f
            downloadSpeed = "0 KB/s"
            downloadedSizeInfo = ""
            
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(info.downloadUrl).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Download failed")
                        
                        val body = response.body ?: throw Exception("Empty body")
                        val totalBytes = body.contentLength()
                        val apkFile = File(getApplication<android.app.Application>().externalCacheDir, "update.apk")
                        
                        body.byteStream().use { input ->
                            FileOutputStream(apkFile).use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalRead = 0L
                                var lastUpdateTime = System.currentTimeMillis()
                                var lastRead = 0L
                                
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                    
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastUpdateTime > 500) {
                                        val progress = if (totalBytes > 0) totalRead.toFloat() / totalBytes else 0f
                                        val timeDiff = (currentTime - lastUpdateTime) / 1000.0
                                        val speed = if (timeDiff > 0) (totalRead - lastRead) / timeDiff else 0.0
                                        
                                        withContext(Dispatchers.Main) {
                                            downloadProgress = progress
                                            downloadSpeed = formatSpeed(speed)
                                            downloadedSizeInfo = if (totalBytes > 0) {
                                                "${formatSize(totalRead)} / ${formatSize(totalBytes)}"
                                            } else {
                                                formatSize(totalRead)
                                            }
                                        }
                                        
                                        lastUpdateTime = currentTime
                                        lastRead = totalRead
                                    }
                                }
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            installApk(context, apkFile)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showSnackbar(if (language == AppLanguage.RU) "Ошибка обновления: ${e.message}" else "Update failed: ${e.message}")
                }
            } finally {
                isDownloadingUpdate = false
            }
        }
    }

    private fun formatSpeed(speed: Double): String {
        return when {
            speed >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB/s", speed / (1024 * 1024))
            speed >= 1024 -> String.format(Locale.getDefault(), "%.1f KB/s", speed / 1024)
            else -> String.format(Locale.getDefault(), "%.0f B/s", speed)
        }
    }

    private fun formatSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", size.toFloat() / (1024 * 1024))
            size >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", size.toFloat() / 1024)
            else -> "$size B"
        }
    }

    private fun installApk(context: Context, file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            showSnackbar(if (language == AppLanguage.RU) "Ошибка установки: ${e.message}" else "Install failed: ${e.message}")
        }
    }

    private fun loadServers(): List<VpnServer> {
        val json = prefs.getString("saved_servers", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<VpnServer>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) { emptyList() }
    }

    private fun saveServers() {
        viewModelScope.launch(Dispatchers.IO) {
            val json = gson.toJson(servers)
            prefs.edit { putString("saved_servers", json) }
        }
    }

    private fun loadSelectedServer(): VpnServer? {
        val json = prefs.getString("selected_server", null) ?: return null
        return try {
            gson.fromJson(json, VpnServer::class.java)
        } catch (_: Exception) { null }
    }

    fun updateSelectedServer(server: VpnServer) {
        selectedServer = server
        prefs.edit { putString("selected_server", gson.toJson(server)) }
        if (isConnected) {
            startVpn(server)
        }
    }

    fun updateDarkTheme(value: Boolean) {
        isDarkTheme = value
        prefs.edit { putBoolean("dark_theme", value) }
    }

    fun updateLanguage(value: AppLanguage) {
        language = value
        prefs.edit { putString("language", value.name) }
    }

    fun updateDnsServer(value: String) {
        dnsServer = value
        prefs.edit { putString("dns_server", value) }
    }

    fun updateRoutingMode(value: RoutingMode) {
        routingMode = value
        prefs.edit { putString("routing_mode", value.name) }
    }

    fun updateSniffing(value: Boolean) {
        isSniffingEnabled = value
        prefs.edit { putBoolean("sniffing", value) }
    }

    fun updateMux(value: Boolean) {
        isMuxEnabled = value
        prefs.edit { putBoolean("mux", value) }
    }

    fun updateFragment(value: Boolean) {
        isFragmentEnabled = value
        prefs.edit { putBoolean("fragment", value) }
    }

    fun updateMtu(value: Int) {
        mtu = value
        prefs.edit { putInt("mtu", value) }
    }

    fun updateConcurrentChecks(value: Int) {
        concurrentChecks = value.coerceIn(1, 100)
        prefs.edit { putInt("concurrent_checks", concurrentChecks) }
    }

    fun updateKillSwitch(value: Boolean) {
        isKillSwitchEnabled = value
        prefs.edit { putBoolean("kill_switch", value) }
    }

    fun updateIpv6(value: Boolean) {
        isIpv6Enabled = value
        prefs.edit { putBoolean("ipv6_enabled", value) }
        restartVpnIfConnected()
    }

    fun updateUtlsFingerprint(value: String) {
        utlsFingerprint = value
        prefs.edit { putString("utls_fingerprint", value) }
        restartVpnIfConnected()
    }

    fun updatePingMethod(value: PingMethod) {
        pingMethod = value
        prefs.edit { putString("ping_method", value.name) }
    }

    fun updatePingTarget(value: String) {
        pingTargetUrl = value
        prefs.edit { putString("ping_target", value) }
    }

    private val client = OkHttpClient()

    private suspend fun fastTcpPing(host: String, port: Int): Long? {
        return withContext(Dispatchers.IO) {
            try {
                // Разрешаем домен в IP заранее, чтобы DNS-запрос не входил в замер пинга
                val inetAddress = java.net.InetAddress.getByName(host)
                val socketAddress = InetSocketAddress(inetAddress, port)
                
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(socketAddress, 3000)
                    System.currentTimeMillis() - start
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    enum class RoutingMode(val label: String) {
        GLOBAL("Global"),
        BYPASS_LAN_RU("Bypass LAN & Russia"),
        ONLY_PROXY("Only Proxy")
    }

    enum class SortOrder {
        COUNTRY,
        PING_TG,
        PING_GEN
    }

    enum class PingMethod(val label: String) {
        TCP("TCP Handshake (Proxy)"),
        HTTP("HTTP Request (Site)")
    }

    fun toggleVpn(context: Context) {
        val server = selectedServer ?: return
        if (isConnected) {
            disconnect()
        } else {
            connect(server, context)
        }
    }

    private fun connect(server: VpnServer, context: Context) {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPermissionIntent = intent
            connectingServer = server
            return
        }
        startVpn(server)
    }

    fun onVpnPermissionGranted() {
        connectingServer?.let { startVpn(it) }
        connectingServer = null
    }

    private fun startVpn(server: VpnServer) {
        connectedServer = server
        isConnected = true
        
        val context = getApplication<android.app.Application>()
        val intent = Intent(context, LidoVpnService::class.java).apply {
            action = "START"
            putExtra("SERVER_NAME", server.name)
            putExtra("SERVER_HOST", server.host)
            putExtra("SERVER_PORT", server.port)
            putExtra("SERVER_UUID", server.uuid)
            putExtra("SERVER_TYPE", server.type)
            
            val paramsBundle = Bundle()
            server.params.forEach { (k, v) -> paramsBundle.putString(k, v) }
            putExtra("SERVER_PARAMS", paramsBundle)

            putExtra("DNS", dnsServer)
            putExtra("SNIFFING", isSniffingEnabled)
            putExtra("MUX", isMuxEnabled)
            putExtra("FRAGMENT", isFragmentEnabled)
            putExtra("MTU", mtu)
            putExtra("ROUTING_MODE", routingMode.name)
            putExtra("KILL_SWITCH", isKillSwitchEnabled)
            putExtra("IPV6_ENABLED", isIpv6Enabled)
            putExtra("UTLS_FINGERPRINT", utlsFingerprint)
            
            putExtra("APP_FILTER_ENABLED", isAppFilterEnabled)
            putExtra("APP_FILTER_BYPASS", isBypassMode)
            val appsArray = selectedApps.toTypedArray()
            putExtra("SELECTED_APPS", appsArray)
        }
        context.startService(intent)
    }

    private fun disconnect() {
        isConnected = false
        connectedServer = null

        val context = getApplication<android.app.Application>()
        val intent = Intent(context, LidoVpnService::class.java).apply {
            action = "STOP"
        }
        context.startService(intent)
    }

    fun toggleSource(url: String) {
        val newSources = if (selectedSources.contains(url)) {
            selectedSources - url
        } else {
            selectedSources + url
        }
        selectedSources = newSources
        prefs.edit { putStringSet("selected_sources", newSources) }
    }

    fun fetchServers() {
        if (selectedSources.isEmpty()) return
        
        viewModelScope.launch {
            isFetching = true
            val urls = selectedSources.toList()
            
            val newServers = mutableListOf<VpnServer>()
            
            withContext(Dispatchers.IO) {
                urls.forEach { url ->
                    try {
                        val request = Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val content = response.body?.string().orEmpty()
                                content.lines().forEach { line ->
                                    val trimmed = line.trim()
                                    if (trimmed.startsWith("vless://") || trimmed.startsWith("trojan://") || trimmed.startsWith("vmess://") || trimmed.startsWith("ss://")) {
                                        parseProxyUrl(trimmed)?.let { newServers.add(it) }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            val addedCount = newServers.size
            servers = newServers
            saveServers()
            isFetching = false

            showSnackbar(
                if (language == AppLanguage.RU) "Загружено серверов: $addedCount"
                else "Loaded $addedCount servers"
            )
        }
    }

    private fun parseProxyUrl(url: String): VpnServer? {
        return try {
            if (url.startsWith("vmess://")) {
                val base64Data = url.substring(8)
                val decoded = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                val json = org.json.JSONObject(decoded)
                
                val params = mutableMapOf<String, String>()
                params["type"] = json.optString("net", "tcp")
                params["path"] = json.optString("path", "")
                params["host"] = json.optString("host", "")
                params["security"] = json.optString("tls", "none")
                params["sni"] = json.optString("sni", "")

                return VpnServer(
                    name = json.optString("ps", "Unnamed VMess"),
                    type = "VMESS",
                    host = json.optString("add", ""),
                    port = json.optInt("port", 443),
                    uuid = json.optString("id", ""),
                    params = params,
                    rawUrl = url
                )
            }

            val uri = url.toUri()
            val type = uri.scheme ?: return null
            val userInfo = uri.userInfo ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port != -1) uri.port else when(type.lowercase()) {
                "vless", "vmess", "trojan" -> 443
                "ss" -> 8388
                else -> 80
            }
            val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Unnamed Server"

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { pair ->
                val parts = pair.split("=")
                if (parts.size == 2) params[parts[0]] = URLDecoder.decode(parts[1], "UTF-8")
            }

            VpnServer(
                name = name,
                type = type.uppercase(),
                host = host,
                port = port,
                uuid = userInfo,
                params = params,
                rawUrl = url,
                country = ""
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchCountry(server: VpnServer): String {
        val unknownText = if (language == AppLanguage.RU) "Неизвестная страна" else "unknown country"
        return withContext(Dispatchers.IO) {
            val resolvedIp = try {
                java.net.InetAddress.getByName(server.host).hostAddress ?: server.host
            } catch (_: Exception) { server.host }

            val geo = tryGetGeoInfo(resolvedIp) ?: run {
                LogManager.addLog("Geo: Failed to find info for ${server.host}")
                return@withContext "🏴‍☠️ $unknownText"
            }
            val code = geo.first
            val flag = codeToFlag(code)
            
            var name = geo.second
            if (name.isEmpty()) {
                name = fetchNameFromRestCountries(code) ?: ""
            }

            val result = if (name.isNotEmpty()) "$flag $name" else flag
            LogManager.addLog("Geo: Found ${server.host} -> $result")
            result
        }
    }

    private fun codeToFlag(code: String): String {
        if (code.length != 2) return ""
        return code.uppercase().map { char ->
            Character.codePointAt(char.toString(), 0) - 0x41 + 0x1F1E6
        }.joinToString("") { String(Character.toChars(it)) }
    }

    private suspend fun tryGetGeoInfo(host: String): Pair<String, String>? {
        val hostPart = if (host.isEmpty()) "" else "/$host"
        val endpoints = listOf(
            "https://ipwho.is$hostPart" to ("country_code" to "country"),
            "https://ipapi.co${if (host.isEmpty()) "/json/" else "/$host/json/"}" to ("country_code" to "country_name"),
            "https://freeipapi.com/api/json$hostPart" to ("countryCode" to "countryName"),
            "https://api.country.is$hostPart" to ("country" to "")
        )

        for ((url, keys) in endpoints) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = org.json.JSONObject(body)
                        val code = json.optString(keys.first)
                        val name = if (keys.second.isNotEmpty()) json.optString(keys.second) else ""
                        if (code.isNotEmpty()) return Pair(code, name)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("GeoIP", "Provider failed: $url - ${e.message}")
            }
        }
        return null
    }

    private suspend fun fetchNameFromRestCountries(code: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://restcountries.com/v3.1/alpha/$code?fields=name")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = org.json.JSONObject(body)
                    json.getJSONObject("name").optString("common")
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateServerInList(rawUrl: String, transform: (VpnServer) -> VpnServer) {
        val index = _servers.indexOfFirst { it.rawUrl == rawUrl }
        if (index != -1) {
            _servers[index] = transform(_servers[index])
        }
    }

    private suspend fun fetchCountryWithCache(server: VpnServer): String {
        return withContext(Dispatchers.IO) {
            val host = server.host
            if (countryCache.containsKey(host)) return@withContext countryCache[host]!!
            
            geoSemaphore.withPermit {
                // Еще раз проверяем кэш после получения разрешения (могли подгрузить пока ждали)
                if (countryCache.containsKey(host)) return@withPermit countryCache[host]!!
                
                val country = fetchCountry(server)
                if (country.isNotEmpty() && !country.contains("unknown")) {
                    countryCache[host] = country
                }
                country
            }
        }
    }

    fun checkAllServers() {
        if (servers.isEmpty()) return
        cancelCheck()
        checkJob = viewModelScope.launch {
            isChecking = true
            LogManager.addLog(if (language == AppLanguage.RU) "Запуск полной проверки серверов..." else "Starting full server health check...")
            var workingCount = 0

            // Сбрасываем только те, что собираемся проверять
            servers = servers.map { it.copy(status = ServerStatus.UNKNOWN, ping = null, pingTelegram = null) }

            val semaphore = Semaphore(concurrentChecks)
            try {
                servers.map { server ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            var pingResult: Long? = null
                            var success = false
                            var countryInfo = ""
                            
                            try {
                                if (!coroutineContext.isActive) return@withPermit
                                
                                if (pingMethod == PingMethod.TCP) {
                                    pingResult = fastTcpPing(server.host, server.port)
                                    success = pingResult != null
                                } else {
                                    val vpnConfig = XrayConfigGenerator.generateConfig(
                                        server = server,
                                        dns = dnsServer,
                                        sniffing = isSniffingEnabled,
                                        mux = isMuxEnabled,
                                        fragment = isFragmentEnabled,
                                        routingMode = "ONLY_PROXY",
                                        mtu = mtu,
                                        assetPath = getApplication<android.app.Application>().filesDir.absolutePath,
                                        utlsFingerprint = utlsFingerprint
                                    )
                                    val delay = Libv2ray.measureOutboundDelay(vpnConfig, pingTargetUrl)
                                    
                                    if (delay > 0) {
                                        pingResult = delay // Используем чистый сетевой пинг от ядра Xray
                                        success = true
                                    }
                                }

                                if (success) {
                                    if (coroutineContext.isActive) {
                                        LogManager.addLog("Check: ${server.name} -> ONLINE (${pingResult} ms)")
                                    }
                                    
                                    // Обновляем статус в UI немедленно
                                    withContext(Dispatchers.Main) {
                                        updateServerInList(server.rawUrl) {
                                            it.copy(status = ServerStatus.WORKING, ping = pingResult)
                                        }
                                    }

                                    // Запускаем поиск страны ПАРАЛЛЕЛЬНО, не занимая слот пинга
                                    launch {
                                        val country = fetchCountryWithCache(server)
                                        if (country.isNotEmpty()) {
                                            withContext(Dispatchers.Main) {
                                                updateServerInList(server.rawUrl) { it.copy(country = country) }
                                            }
                                        }
                                    }
                                } else {
                                    if (coroutineContext.isActive) {
                                        LogManager.addLog("Check: ${server.name} -> FAILED OR SLOW")
                                    }
                                    withContext(Dispatchers.Main) {
                                        updateServerInList(server.rawUrl) { it.copy(status = ServerStatus.NOT_WORKING) }
                                    }
                                }
                            } catch (e: Exception) {
                                if (coroutineContext.isActive && e !is kotlinx.coroutines.CancellationException) {
                                    LogManager.addLog("Check Error: ${server.name} -> ${e.message}")
                                }
                                withContext(Dispatchers.Main) {
                                    updateServerInList(server.rawUrl) { it.copy(status = ServerStatus.NOT_WORKING) }
                                }
                            } finally {
                                // Семафор освободится здесь
                            }
                        }
                    }
                }.joinAll()
                
                applySort()
                saveServers()
            } catch (e: Exception) {
                // Ignore cancellation
            } finally {
                isChecking = false
                applySort()
                val finalWorkingCount = servers.count { it.status == ServerStatus.WORKING }
                showSnackbar(
                    if (language == AppLanguage.RU) "Проверка окончена. Рабочих: $finalWorkingCount"
                    else "Check finished. Working: $finalWorkingCount"
                )
            }
        }
    }

    fun stopCheck() {
        cancelCheck()
        LogManager.addLog(if (language == AppLanguage.RU) "Проверка остановлена пользователем" else "Check stopped by user")
    }

    fun checkAllTelegram() {
        val workingServers = servers.filter { it.status == ServerStatus.WORKING }
        if (workingServers.isEmpty()) return
        
        cancelCheck()
        
        // 1. Сбрасываем пинг ТГ перед началом только для рабочих серверов
        servers = servers.map { 
            if (it.status == ServerStatus.WORKING) it.copy(pingTelegram = null) else it 
        }

        val semaphore = Semaphore(concurrentChecks)
        checkJob = viewModelScope.launch {
            isCheckingTelegram = true
            LogManager.addLog("Запуск проверки Telegram для ${workingServers.size} серверов...")
            try {
                workingServers.map { server ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            var pingResult: Long? = null
                            try {
                                if (!coroutineContext.isActive) return@withPermit
                                val vpnConfig = XrayConfigGenerator.generateConfig(
                                    server = server,
                                    dns = dnsServer,
                                    sniffing = isSniffingEnabled,
                                    mux = isMuxEnabled,
                                    fragment = isFragmentEnabled,
                                    routingMode = "ONLY_PROXY",
                                    mtu = mtu,
                                    assetPath = getApplication<android.app.Application>().filesDir.absolutePath
                                )
                                val startTime = System.currentTimeMillis()
                                val delay = Libv2ray.measureOutboundDelay(vpnConfig, "https://t.me/telegram")
                                val endTime = System.currentTimeMillis()
                                val totalDuration = endTime - startTime

                                if (delay > 0 && totalDuration < 10000) {
                                    pingResult = totalDuration
                                    if (coroutineContext.isActive) {
                                        LogManager.addLog("TG Check: ${server.name} -> ONLINE ($totalDuration ms)")
                                    }
                                } else {
                                    if (coroutineContext.isActive) {
                                        LogManager.addLog("TG Check: ${server.name} -> FAILED")
                                    }
                                }
                            } catch (e: Exception) {
                                if (coroutineContext.isActive && e !is kotlinx.coroutines.CancellationException) {
                                    LogManager.addLog("TG Check Error: ${server.name} -> ${e.message}")
                                }
                            } finally {
                                if (coroutineContext.isActive) {
                                    withContext(Dispatchers.Main) {
                                        val currentIndex = servers.indexOfFirst { it.host == server.host && it.port == server.port && it.name == server.name }
                                        if (currentIndex != -1) {
                                            val newList = servers.toMutableList()
                                            newList[currentIndex] = newList[currentIndex].copy(pingTelegram = pingResult)
                                            // 2. Не сортируем во время проверки, чтобы список не "прыгал"
                                            servers = newList 
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.joinAll()
                
                // 3. Сортируем один раз в самом конце
                servers = getSortedList(servers)
                saveServers()
            } finally {
                isCheckingTelegram = false
            }
        }
    }

    private fun getSortedList(list: List<VpnServer>): List<VpnServer> {
        return list.sortedWith(
            compareBy<VpnServer> { it.status != ServerStatus.WORKING }
                .thenBy { it.status == ServerStatus.UNKNOWN } // Сначала рабочие, потом нерабочие, потом те что в очереди
                .thenBy {
                    when (sortOrder) {
                        SortOrder.COUNTRY -> it.country.isEmpty()
                        SortOrder.PING_TG -> it.pingTelegram == null
                        SortOrder.PING_GEN -> it.ping == null
                    }
                }
                .thenBy {
                    when (sortOrder) {
                        SortOrder.COUNTRY -> it.country
                        SortOrder.PING_TG -> it.pingTelegram ?: Long.MAX_VALUE
                        SortOrder.PING_GEN -> it.ping ?: Long.MAX_VALUE
                    }
                }
        )
    }

    fun applySort() {
        servers = getSortedList(servers)
    }
}

@Composable
fun ConfigSelectionDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                    Text(
                        text = if (viewModel.language == AppLanguage.RU) "Выбор конфигураций" else "Select Configurations",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    viewModel.configCategories.forEach { category ->
                        item {
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                        item {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                category.items.forEach { source ->
                                    FilterChip(
                                        selected = viewModel.selectedSources.contains(source.url),
                                        onClick = { viewModel.toggleSource(source.url) },
                                        label = { Text(source.name) },
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                Button(
                    onClick = {
                        viewModel.fetchServers()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Text(if (viewModel.language == AppLanguage.RU) "Обновить список" else "Update List")
                }
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val buttonSize by animateDpAsState(if (viewModel.isConnected) 200.dp else 180.dp, label = "size")
    val buttonColor by animateColorAsState(if (viewModel.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary, label = "color")

    Box(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val statusText = if (viewModel.isConnected) {
                if (viewModel.language == AppLanguage.RU) "ПОДКЛЮЧЕНО" else "CONNECTED"
            } else {
                if (viewModel.language == AppLanguage.RU) "ОТКЛЮЧЕНО" else "DISCONNECTED"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (viewModel.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.1f))
                    .border(4.dp, buttonColor, CircleShape)
                    .clickable { viewModel.toggleVpn(context) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = buttonColor
                )
            }
        }
        
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            viewModel.selectedServer?.let { server ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (viewModel.language == AppLanguage.RU) "Выбранный сервер" else "Selected Server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(server.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall)
                        }
                        
                        if (server.status != ServerStatus.UNKNOWN) {
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
                                server.pingTelegram?.let {
                                    Text(
                                        text = "TG: $it ms",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF24A1DE)
                                    )
                                }
                                server.ping?.let {
                                    Text(
                                        text = "Ping: $it ms",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (it < 150) Color(0xFF4CAF50) else if (it < 300) Color(0xFFFFC107) else Color(0xFFF44336)
                                    )
                                }
                                if (server.country.isNotEmpty()) {
                                    Text(text = server.country, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } ?: Text(
                text = if (viewModel.language == AppLanguage.RU) "Пожалуйста, сначала выберите сервер" else "Please select a server first",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
fun ServersScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val workingServers = viewModel.servers.filter { it.status == ServerStatus.WORKING }
    val failedServers = viewModel.servers.filter { it.status == ServerStatus.NOT_WORKING }
    val pendingServers = viewModel.servers.filter { it.status == ServerStatus.UNKNOWN }

    var isWorkingExpanded by rememberSaveable { mutableStateOf(true) }
    var isFailedExpanded by rememberSaveable { mutableStateOf(true) }
    var isPendingExpanded by rememberSaveable { mutableStateOf(true) }
    var showSortMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = if (viewModel.language == AppLanguage.RU) "Серверы" else "Servers", style = MaterialTheme.typography.headlineSmall)
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        AppViewModel.SortOrder.entries.forEach { order ->
                            val label = when(order) {
                                AppViewModel.SortOrder.COUNTRY -> if (viewModel.language == AppLanguage.RU) "Страна" else "Country"
                                AppViewModel.SortOrder.PING_TG -> if (viewModel.language == AppLanguage.RU) "Пинг Telegram" else "Telegram Ping"
                                AppViewModel.SortOrder.PING_GEN -> if (viewModel.language == AppLanguage.RU) "Общий пинг" else "General Ping"
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.sortOrder = order
                                    viewModel.applySort()
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { viewModel.fetchServers() },
                    enabled = !viewModel.isFetching && viewModel.selectedSources.isNotEmpty()
                ) {
                    if (viewModel.isFetching) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }

                IconButton(
                    onClick = { 
                        if (viewModel.isCheckingTelegram) viewModel.stopCheck() 
                        else viewModel.checkAllTelegram() 
                    },
                    enabled = (viewModel.isCheckingTelegram || workingServers.isNotEmpty())
                ) {
                    if (viewModel.isCheckingTelegram) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop Telegram Check", tint = Color.Red)
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Check All Telegram",
                            tint = Color(0xFF24A1DE)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                val isAnyChecking = viewModel.isChecking || viewModel.isCheckingTelegram

                Button(
                    onClick = { 
                        if (viewModel.isChecking) viewModel.stopCheck() 
                        else viewModel.checkAllServers() 
                    },
                    enabled = viewModel.isChecking || viewModel.servers.isNotEmpty(),
                    colors = if (viewModel.isChecking) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.buttonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    if (viewModel.isChecking) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (viewModel.language == AppLanguage.RU) "Стоп" else "Stop")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (viewModel.language == AppLanguage.RU) "Проверить" else "Check")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (viewModel.servers.isEmpty()) {
            // ... (Пустой список)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val emptyText = if (viewModel.selectedSources.isEmpty()) {
                    if (viewModel.language == AppLanguage.RU) "Сначала выберите источники в Настройках" else "Select sources in Settings first"
                } else {
                    if (viewModel.language == AppLanguage.RU) "Нажмите иконку загрузки, чтобы получить серверы" else "Tap Download icon to fetch servers"
                }
                Text(text = emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1. WORKING
                if (workingServers.isNotEmpty()) {
                    item {
                        CategoryHeader(
                            title = if (viewModel.language == AppLanguage.RU) "Рабочие" else "Working",
                            count = workingServers.size,
                            color = Color(0xFF4CAF50),
                            isExpanded = isWorkingExpanded,
                            onToggle = { isWorkingExpanded = !isWorkingExpanded }
                        )
                    }
                    if (isWorkingExpanded) {
                        items(workingServers) { ServerItem(it, it == viewModel.selectedServer) { viewModel.updateSelectedServer(it) } }
                    }
                }

                // 2. PENDING (UNKNOWN)
                if (pendingServers.isNotEmpty()) {
                    item {
                        CategoryHeader(
                            title = if (viewModel.language == AppLanguage.RU) "В очереди" else "Pending",
                            count = pendingServers.size,
                            color = Color.Gray,
                            isExpanded = isPendingExpanded,
                            onToggle = { isPendingExpanded = !isPendingExpanded }
                        )
                    }
                    if (isPendingExpanded) {
                        items(pendingServers) { ServerItem(it, it == viewModel.selectedServer) { viewModel.updateSelectedServer(it) } }
                    }
                }

                // 3. FAILED
                if (failedServers.isNotEmpty()) {
                    item {
                        CategoryHeader(
                            title = if (viewModel.language == AppLanguage.RU) "Нерабочие" else "Failed",
                            count = failedServers.size,
                            color = MaterialTheme.colorScheme.error,
                            isExpanded = isFailedExpanded,
                            onToggle = { isFailedExpanded = !isFailedExpanded }
                        )
                    }
                    if (isFailedExpanded) {
                        items(failedServers) { ServerItem(it, it == viewModel.selectedServer) { viewModel.updateSelectedServer(it) } }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryHeader(title: String, count: Int, color: Color, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = color,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = color
        )
    }
}

@Composable
fun ServerItem(server: VpnServer, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isSelected) borderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = server.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(text = "${server.host}:${server.port} • ${server.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (server.status != ServerStatus.UNKNOWN) {
                        Column(horizontalAlignment = Alignment.End) {
                            server.pingTelegram?.let {
                                Text(
                                    text = "TG: $it ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF24A1DE)
                                )
                            }
                            server.ping?.let {
                                Text(
                                    text = "Ping: $it ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (server.status == ServerStatus.WORKING) Color.Green else Color.Red)
                        )
                    }
                }
                if (server.country.isNotEmpty()) {
                    Text(
                        text = server.country,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun LogsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val lazyListState = rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
        androidx.compose.foundation.lazy.LazyListState()
    }
    val logs = LogManager.vpnLogs
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val filteredLogs = remember(logs, selectedTab) {
        when (selectedTab) {
            // Tab 1: Server Health & GeoIP Checks
            1 -> logs.filter { it.contains("Check") || it.contains("Geo:") }
            // Tab 2: Connection logs & Xray Engine logs
            2 -> logs.filter { !it.contains("Check") && !it.contains("Geo:") && it.contains(":") }
            else -> logs
        }
    }

    // Auto-scroll logic: scroll to bottom only if we were already near the bottom
    LaunchedEffect(filteredLogs.size) {
        val lastVisibleItem = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()
        if (lastVisibleItem != null && lastVisibleItem.index >= filteredLogs.size - 5) {
            lazyListState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = if (viewModel.language == AppLanguage.RU) "Логи" else "Logs", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { LogManager.clearLogs() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear")
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {}
        ) {
            val tabs = if (viewModel.language == AppLanguage.RU) 
                listOf("Все", "Проверка", "Ядро") else listOf("All", "Checks", "Core")
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        
        Box(modifier = Modifier.weight(1f)) {
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.padding(8.dp).fillMaxSize()
                ) {
                    items(filteredLogs) { log ->
                        val color = when {
                            log.contains("[Error]") || log.contains("FAILED") || log.contains("Error:") -> Color(0xFFF44336)
                            log.contains("[Warning]") -> Color(0xFFFF9800)
                            log.contains("ONLINE") -> Color(0xFF4CAF50)
                            log.contains("TG Check") || log.contains("Telegram") -> Color(0xFF24A1DE)
                            log.contains("Запуск") || log.contains("Starting") || log.contains("[Info]") -> Color(0xFF8BC34A)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = log,
                            color = color,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            // Scroll to bottom button
            val showButton by remember(filteredLogs.size) {
                derivedStateOf {
                    val layoutInfo = lazyListState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) false
                    else {
                        val lastVisibleItem = visibleItems.last()
                        lastVisibleItem.index < filteredLogs.size - 5
                    }
                }
            }

            if (showButton) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (filteredLogs.isNotEmpty()) {
                                lazyListState.animateScrollToItem(filteredLogs.size - 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
            }
        }
    }
}

@Composable
private fun borderStroke(width: androidx.compose.ui.unit.Dp, color: Color) = androidx.compose.foundation.BorderStroke(width, color)

@Composable
fun UpdateDialog(viewModel: AppViewModel, info: VpnUpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!viewModel.isDownloadingUpdate) onDismiss() },
        title = { Text(if (viewModel.language == AppLanguage.RU) "Доступно обновление" else "Update Available") },
        text = {
            Column {
                Text(text = "${if (viewModel.language == AppLanguage.RU) "Версия" else "Version"}: ${info.version}")
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = info.description)
                
                if (viewModel.isDownloadingUpdate) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { viewModel.downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = viewModel.downloadedSizeInfo, style = MaterialTheme.typography.labelSmall)
                        Text(text = viewModel.downloadSpeed, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.downloadAndInstallUpdate(context, info) },
                enabled = !viewModel.isDownloadingUpdate
            ) {
                Text(if (viewModel.language == AppLanguage.RU) "Обновить" else "Update")
            }
        },
        dismissButton = {
            if (!viewModel.isDownloadingUpdate) {
                TextButton(onClick = onDismiss) {
                    Text(if (viewModel.language == AppLanguage.RU) "Позже" else "Later")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    var showRoutingMenu by remember { mutableStateOf(false) }
    var showPingMethodMenu by remember { mutableStateOf(false) }
    var showFingerprintMenu by remember { mutableStateOf(false) }
    var showAppSelection by remember { mutableStateOf(false) }
    var showConfigSelector by remember { mutableStateOf(false) }
    
    // Временные состояния для полей ввода чисел
    var concurrentChecksText by remember { mutableStateOf(viewModel.concurrentChecks.toString()) }
    var mtuText by remember { mutableStateOf(viewModel.mtu.toString()) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(text = if (viewModel.language == AppLanguage.RU) "Настройки" else "Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 1. App Filtering (NEW)
        item {
            Text(if (viewModel.language == AppLanguage.RU) "Фильтрация приложений" else "App Filtering", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            SettingsSwitch(
                label = if (viewModel.language == AppLanguage.RU) "Включить фильтр" else "Enable App Filter",
                subtitle = if (viewModel.language == AppLanguage.RU) "Использовать VPN только для выбранных приложений" else "Only use VPN for selected apps",
                checked = viewModel.isAppFilterEnabled,
                onCheckedChange = { viewModel.updateAppFilterEnabled(it) }
            )
            
            AnimatedVisibility(visible = viewModel.isAppFilterEnabled) {
                Column {
                    SettingsSwitch(
                        label = if (viewModel.language == AppLanguage.RU) "Режим обхода" else "Bypass Mode",
                        subtitle = if (viewModel.language == AppLanguage.RU) "Выбранные приложения будут идти напрямую" else "Selected apps will connect directly",
                        checked = viewModel.isBypassMode,
                        onCheckedChange = { viewModel.updateBypassMode(it) }
                    )
                    
                    Button(
                        onClick = { 
                            viewModel.loadApps()
                            showAppSelection = true 
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Apps, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (viewModel.language == AppLanguage.RU) "Выбрать приложения" else "Select Applications")
                    }

                    Text(
                        text = "${if (viewModel.language == AppLanguage.RU) "Выбрано приложений" else "Apps selected"}: ${viewModel.selectedApps.size}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. Subscription Sources
        item {
            Text(if (viewModel.language == AppLanguage.RU) "Источники подписок" else "Subscription Sources", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { showConfigSelector = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.LibraryAdd, null)
                Spacer(Modifier.width(8.dp))
                Text(if (viewModel.language == AppLanguage.RU) "Выбрать конфигурации" else "Select Configurations")
            }



            Text(
                text = "${if (viewModel.language == AppLanguage.RU) "Выбрано источников" else "Sources selected"}: ${viewModel.selectedSources.size}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 3. VPN Configuration
        item {
            Text(if (viewModel.language == AppLanguage.RU) "Конфигурация VPN" else "VPN Configuration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = viewModel.dnsServer,
                onValueChange = { viewModel.updateDnsServer(it) },
                label = { Text(if (viewModel.language == AppLanguage.RU) "DNS Сервер" else "DNS Server") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        Spacer(modifier = Modifier.height(16.dp))
            
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = viewModel.routingMode.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (viewModel.language == AppLanguage.RU) "Режим маршрутизации" else "Routing Mode") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showRoutingMenu = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                )
                DropdownMenu(
                    expanded = showRoutingMenu,
                    onDismissRequest = { showRoutingMenu = false }
                ) {
                    AppViewModel.RoutingMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                viewModel.updateRoutingMode(mode)
                                showRoutingMenu = false
                            }
                        )
                    }
                }
            }
        Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = mtuText,
                onValueChange = { 
                    mtuText = it
                    it.toIntOrNull()?.let { num -> viewModel.updateMtu(num) }
                },
                label = { Text("MTU") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = concurrentChecksText,
                onValueChange = { 
                    concurrentChecksText = it
                    val num = it.toIntOrNull()
                    if (num != null) {
                        viewModel.updateConcurrentChecks(num)
                    } else if (it.isEmpty()) {
                        viewModel.updateConcurrentChecks(15)
                    }
                },
                label = { Text(if (viewModel.language == AppLanguage.RU) "Потоки проверки" else "Concurrent Checks") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        Spacer(modifier = Modifier.height(16.dp))

            SettingsSwitch(
                label = if (viewModel.language == AppLanguage.RU) "Сниффинг" else "Sniffing",
                subtitle = if (viewModel.language == AppLanguage.RU) "Определение доменных имен из трафика" else "Detect domain names from traffic",
                checked = viewModel.isSniffingEnabled,
                onCheckedChange = { viewModel.updateSniffing(it) }
            )
            SettingsSwitch(
                label = "Mux",
                subtitle = if (viewModel.language == AppLanguage.RU) "Мультиплексирование для лучшей производительности" else "Multiplexing for better performance",
                checked = viewModel.isMuxEnabled,
                onCheckedChange = { viewModel.updateMux(it) }
            )
            SettingsSwitch(
                label = "Fragment",
                subtitle = if (viewModel.language == AppLanguage.RU) "Обход DPI (глубокого анализа пакетов)" else "Bypass DPI (Deep Packet Inspection)",
                checked = viewModel.isFragmentEnabled,
                onCheckedChange = { viewModel.updateFragment(it) }
            )

            SettingsSwitch(
                label = if (viewModel.language == AppLanguage.RU) "Аварийный выключатель" else "Kill Switch",
                subtitle = if (viewModel.language == AppLanguage.RU) "Блокировать интернет при обрыве VPN" else "Block internet if VPN disconnects",
                checked = viewModel.isKillSwitchEnabled,
                onCheckedChange = { viewModel.updateKillSwitch(it) }
            )

            SettingsSwitch(
                label = "IPv6",
                subtitle = if (viewModel.language == AppLanguage.RU) "Разрешить поддержку IPv6" else "Enable IPv6 support",
                checked = viewModel.isIpv6Enabled,
                onCheckedChange = { viewModel.updateIpv6(it) }
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = viewModel.utlsFingerprint,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (viewModel.language == AppLanguage.RU) "Отпечаток TLS (uTLS)" else "TLS Fingerprint (uTLS)") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showFingerprintMenu = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                )
                DropdownMenu(
                    expanded = showFingerprintMenu,
                    onDismissRequest = { showFingerprintMenu = false }
                ) {
                    listOf("chrome", "firefox", "safari", "edge", "android", "random").forEach { fp ->
                        DropdownMenuItem(
                            text = { Text(fp) },
                            onClick = {
                                viewModel.updateUtlsFingerprint(fp)
                                showFingerprintMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 3. Ping Settings
        item {
            Text(if (viewModel.language == AppLanguage.RU) "Настройки пинга" else "Ping Settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = viewModel.pingMethod.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (viewModel.language == AppLanguage.RU) "Способ проверки" else "Ping Method") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showPingMethodMenu = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                )
                DropdownMenu(
                    expanded = showPingMethodMenu,
                    onDismissRequest = { showPingMethodMenu = false }
                ) {
                    AppViewModel.PingMethod.entries.forEach { method ->
                        DropdownMenuItem(
                            text = { Text(method.label) },
                            onClick = {
                                viewModel.updatePingMethod(method)
                                showPingMethodMenu = false
                            }
                        )
                    }
                }
            }
        Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(visible = viewModel.pingMethod == AppViewModel.PingMethod.HTTP) {
                OutlinedTextField(
                    value = viewModel.pingTargetUrl,
                    onValueChange = { viewModel.updatePingTarget(it) },
                    label = { Text(if (viewModel.language == AppLanguage.RU) "Сайт для проверки" else "Check Target URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            Spacer(modifier = Modifier.height(16.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 4. Geo Data (Penultimate)
        item {
            Text(if (viewModel.language == AppLanguage.RU) "Гео-данные" else "Geo Data", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    viewModel.geoFilesInfo.forEach { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(file.name, fontWeight = FontWeight.Bold)
                                if (file.remoteVersion.isNotEmpty()) {
                                    Text("GitHub: ${file.remoteVersion}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            if (file.exists) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${String.format(Locale.getDefault(), "%.2f", file.size / 1024.0 / 1024.0)} MB", style = MaterialTheme.typography.bodySmall)
                                    val date = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(file.lastModified))
                                    Text(date, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            } else {
                                Text(if (viewModel.language == AppLanguage.RU) "Отсутствует" else "Missing", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.downloadGeoData() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !viewModel.isDownloadingGeo
                    ) {
                        if (viewModel.isDownloadingGeo) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (viewModel.language == AppLanguage.RU) "Обновить гео-данные" else "Update Geo Data")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 5. App Update
        item {
            val context = LocalContext.current
            val currentVersion = remember(context) {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (e: Exception) { "1.0.0" }
            }
            Text(if (viewModel.language == AppLanguage.RU) "Обновление приложения" else "App Update", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(if (viewModel.language == AppLanguage.RU) "Версия: $currentVersion" else "Version: $currentVersion", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { viewModel.checkForUpdates() },
                        enabled = !viewModel.isCheckingUpdate
                    ) {
                        if (viewModel.isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text(if (viewModel.language == AppLanguage.RU) "Проверить" else "Check")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 6. Appearance & Language (Final)
        item {
            Text(if (viewModel.language == AppLanguage.RU) "Внешний вид и Язык" else "Appearance & Language", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (viewModel.language == AppLanguage.RU) "Язык приложения" else "App Language", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.width(16.dp))
                AppLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = viewModel.language == lang,
                        onClick = { viewModel.updateLanguage(lang) },
                        label = { Text(lang.label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (viewModel.language == AppLanguage.RU) "Тема приложения" else "App Theme", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, if (!viewModel.isDarkTheme) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        .clickable { viewModel.updateDarkTheme(false) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(2.dp, if (viewModel.isDarkTheme) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        .clickable { viewModel.updateDarkTheme(true) }
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showConfigSelector) {
        ConfigSelectionDialog(viewModel = viewModel, onDismiss = { showConfigSelector = false })
    }

    viewModel.updateInfo?.let { info ->
        UpdateDialog(viewModel = viewModel, info = info, onDismiss = { viewModel.updateInfo = null })
    }

    if (showAppSelection) {
        AppSelectionDialog(
            viewModel = viewModel,
            onDismiss = { showAppSelection = false }
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
                item { Text(text = if (viewModel.language == AppLanguage.RU) "Выберите источники" else "Choose Sources", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp)) }
                items(15) { index ->
                    val id = (index + 1).toString()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleSource(id) }.padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = viewModel.selectedSources.contains(id), onCheckedChange = { _ -> viewModel.toggleSource(id) })
                        Text(text = "${if (viewModel.language == AppLanguage.RU) "Источник" else "Source"} $id", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AppSelectionDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = if (searchQuery.isEmpty()) {
        viewModel.installedApps
    } else {
        viewModel.installedApps.filter { it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                    Text(
                        text = if (viewModel.language == AppLanguage.RU) "Выберите приложения" else "Select Applications",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(if (viewModel.language == AppLanguage.RU) "Поиск" else "Search") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )

                if (viewModel.isAppsLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredApps) { app ->
                            AppItem(
                                app = app,
                                isSelected = viewModel.selectedApps.contains(app.packageName),
                                onClick = { viewModel.toggleAppSelection(app.packageName) }
                            )
                        }
                    }
                }
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Text(if (viewModel.language == AppLanguage.RU) "Готово" else "Done")
                }
            }
        }
    }
}

@Composable
fun AppItem(app: AppInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(modifier = Modifier.size(40.dp).background(Color.Gray, CircleShape))
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Checkbox(checked = isSelected, onCheckedChange = { onClick() })
    }
}

@Composable
fun SettingsSwitch(label: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

enum class AppLanguage(val label: String) {
    EN("English"),
    RU("Русский")
}

enum class AppDestinations(val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME(Icons.Default.Home),
    SERVERS(Icons.Default.Dns),
    LOGS(Icons.AutoMirrored.Filled.List),
    SETTINGS(Icons.Default.Settings),
}
