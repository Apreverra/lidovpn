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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.ConcurrentHashMap
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
                
                if (!vm.hasSeenGuide || vm.showGuide) {
                    OnboardingGuide(viewModel = vm)
                }
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
                    selected = it == viewModel.currentDestination,
                    onClick = { viewModel.currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) }
        ) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            
            when (viewModel.currentDestination) {
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

data class DpiPreset(
    val name: String,
    var score: Int = 0,
    var lastResults: Map<String, Long?> = emptyMap()
)

class AppViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    
    var isOptimizingDpi by mutableStateOf(false)
    var optimizationProgress by mutableFloatStateOf(0f)
    // var showDpiOptimization by mutableStateOf(false)

    fun runDpiTest() {
        viewModelScope.launch {
            isOptimizingDpi = true
            optimizationProgress = 0f
            testResults = emptyMap()
            
            // Add -n google.com if not present
            var finalCommand = dpiCommand
            if (!finalCommand.contains("-n ") && !finalCommand.contains("--fake-sni")) {
                finalCommand = "-n google.com " + finalCommand
            }

            LogManager.addLog(if (language == AppLanguage.RU) "Запуск проверки DPI настроек (ByeDPI): $finalCommand" else "Starting DPI settings check (ByeDPI): $finalCommand")

            // На время теста останавливаем всё, что может мешать
            if (isConnected) {
                val intent = Intent(getApplication(), LidoVpnService::class.java).apply { action = "STOP" }
                getApplication<android.app.Application>().startService(intent)
                kotlinx.coroutines.delay(1000)
            }

            val testPort = 1080
            // Запускаем ByeDPI на тестовом порту. 
            ByeDPIController.start(getApplication(), finalCommand, testPort)
            kotlinx.coroutines.delay(3000) // Даем больше времени на запуск

            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", testPort))
            
            // Custom DNS resolver to resolve via 8.8.8.8
            val customDns = object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return try {
                        // Attempt to resolve using system resolver (which we'll hope follows our 8.8.8.8 preference)
                        // In a more complex setup, we'd use a dedicated DNS client
                        java.net.InetAddress.getAllByName(hostname).toList()
                    } catch (e: Exception) {
                        LogManager.addLog("DNS Error: Failed to resolve $hostname")
                        throw e
                    }
                }
            }

            val testClient = OkHttpClient.Builder()
                .proxy(proxy)
                .dns(customDns)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            var completed = 0
            val total = dpiTestTargets.size
            val semaphore = Semaphore(5) // Ограничиваем количество одновременных запросов
            
            val resultsList = withContext(Dispatchers.IO) {
                dpiTestTargets.map { target ->
                    async {
                        semaphore.withPermit {
                            val start = System.currentTimeMillis()
                            var errorMsg: String? = null
                            val success = try {
                                val request = Request.Builder()
                                    .url(target.second)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    .build()
                                testClient.newCall(request).execute().use { _ ->
                                    true
                                }
                            } catch (e: Exception) {
                                errorMsg = e.localizedMessage ?: e.message ?: "Connection Error"
                                false
                            }
                            val delay = System.currentTimeMillis() - start
                            
                            val resultValue = if (success) "${delay}ms" else (errorMsg ?: "Timeout")
                            
                            withContext(Dispatchers.Main) {
                                completed++
                                optimizationProgress = completed.toFloat() / total
                                LogManager.addLog("DPI Check: ${target.first} -> $resultValue")
                            }
                            target.first to resultValue
                        }
                    }
                }.awaitAll()
            }
            
            testResults = resultsList.toMap()
            
            // Останавливаем тестовый процесс
            ByeDPIController.stop()
            
            // Если VPN был включен в режиме DPI, перезапускаем на основном порту
            if (isConnected && isDpiOnlyMode) {
                 ByeDPIController.start(getApplication(), dpiCommand, 1080)
            }

            isOptimizingDpi = false
            val workingCount = testResults.values.count { it.contains("ms") }
            LogManager.addLog(if (language == AppLanguage.RU) "Проверка DPI завершена. Доступно: $workingCount/$total" else "DPI check finished. Available: $workingCount/$total")
            showSnackbar(if (language == AppLanguage.RU) "Проверка завершена" else "Test finished")
        }
    }

    var dpiCommand by mutableStateOf(prefs.getString("dpi_command", "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1") ?: "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1")
    var dpiEngine by mutableStateOf("ByeDPI")
    
    fun updateDpiEngine(value: String) {
        // Force ByeDPI
        dpiEngine = "ByeDPI"
        prefs.edit { putString("dpi_engine", "ByeDPI") }
        restartVpnIfConnected()
    }
    
    fun updateDpiCommand(value: String) {
        dpiCommand = value
        prefs.edit { putString("dpi_command", value) }
        
        // Parse and update internal values
        val (p, l, i) = parseDpiCommand(value)
        dpiPackets = p
        dpiLength = l
        dpiInterval = i
        
        restartVpnIfConnected()
    }

    private fun parseDpiCommand(command: String): Triple<String, String, String> {
        var packets = "all"
        var minPos = 1
        var maxPos = 2
        var interval = "10-20"

        val lowerCmd = command.lowercase()
        
        // 1. Протоколы (-K tls,http)
        if (lowerCmd.contains("-k") || lowerCmd.contains("--proto")) {
            if (lowerCmd.contains("tls")) packets = "tlshello"
            else if (lowerCmd.contains("http")) packets = "httpget"
        } else if (lowerCmd.contains("httpget")) {
            packets = "httpget"
        } else if (lowerCmd.contains("tlshello")) {
            packets = "tlshello"
        }

        // 2. Ищем все позиции разреза (-s, -d, -o, -f, -q)
        // Ищем паттерны типа -s1, -s 5, -d10, --split 20
        val posRegex = Regex("(?:-s|-d|-o|-f|-q|--split|--disorder|--oob|--fake)\\s*(\\d+)")
        val matches = posRegex.findAll(lowerCmd).toList()
        
        val positions = matches.map { it.groupValues[1].toInt() }.filter { it > 0 }.distinct().sorted()
        
        if (positions.isNotEmpty()) {
            // Если у нас много позиций (как в примере пользователя), 
            // мы закодируем их в строку "L" через запятую, а генератор разберет их на цепочку
            if (positions.size > 1) {
                val lengthStr = positions.joinToString(",")
                
                // Тайминг для сложных цепочек должен быть очень коротким
                interval = "1-10"
                if (lowerCmd.contains("-r") || lowerCmd.contains("--tlsrec")) interval = "1-3"
                
                return Triple(packets, lengthStr, interval)
            }
            
            minPos = positions.minOrNull() ?: 1
            maxPos = positions.maxOrNull() ?: (minPos + 2)
            
            // Если позиций много или они большие, расширяем диапазон
            if (positions.size > 2 || maxPos > 50) {
                maxPos = maxOf(maxPos, minPos + 5)
            }
        }

        // 3. Учет флагов смещения (+s, +h) - это часто нужно для YouTube
        if (lowerCmd.contains("+s") || lowerCmd.contains("+h")) {
            if (maxPos < 20) maxPos = 40 // Расширяем для покрытия SNI/Host
        }

        // 4. Настройка интервала на основе сложности (Disorder/OOB/Fake)
        if (lowerCmd.contains("-d") || lowerCmd.contains("-o") || lowerCmd.contains("-f")) {
            interval = "5-15" 
        }
        if (lowerCmd.contains("-r") || lowerCmd.contains("--tlsrec")) {
            interval = "1-5" 
        }
        
        // Прямое указание диапазона (если пользователь ввел 100-200)
        val rangeRegex = Regex("(\\d+)-(\\d+)")
        rangeRegex.find(command)?.let {
            return Triple(packets, it.value, interval)
        }

        val length = if (minPos == maxPos) "$minPos-${minPos + 2}" else "$minPos-$maxPos"
        return Triple(packets, length, interval)
    }

    var currentDestination by mutableStateOf(AppDestinations.HOME)
    var currentTutorialStep by mutableIntStateOf(0)

    var isDarkTheme by mutableStateOf(prefs.getBoolean("dark_theme", true))
    var language by mutableStateOf(
        prefs.getString("language", null)?.let {
            try { AppLanguage.valueOf(it) } catch (_: Exception) { null }
        } ?: if (java.util.Locale.getDefault().language == "ru") AppLanguage.RU else AppLanguage.EN
    )
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
    var dnsServer by mutableStateOf(prefs.getString("dns_server", "8.8.8.8") ?: "8.8.8.8")
    var routingMode by mutableStateOf(RoutingMode.valueOf(prefs.getString("routing_mode", "BYPASS_LAN_RU") ?: "BYPASS_LAN_RU"))
    var isSniffingEnabled by mutableStateOf(prefs.getBoolean("sniffing", true))
    var isMuxEnabled by mutableStateOf(prefs.getBoolean("mux", false))
    var isFragmentEnabled by mutableStateOf(prefs.getBoolean("fragment", true))
    var mtu by mutableIntStateOf(prefs.getInt("mtu", 1500))
    var concurrentChecks by mutableIntStateOf(prefs.getInt("concurrent_checks", 15))

    var isKillSwitchEnabled by mutableStateOf(prefs.getBoolean("kill_switch", false))
    var isIpv6Enabled by mutableStateOf(prefs.getBoolean("ipv6_enabled", false))
    var utlsFingerprint by mutableStateOf(prefs.getString("utls_fingerprint", "chrome") ?: "chrome")
    
    var dpiPackets by mutableStateOf(prefs.getString("dpi_packets", "tlshello") ?: "tlshello")
    var dpiLength by mutableStateOf(prefs.getString("dpi_length", "100-200") ?: "100-200")
    var dpiInterval by mutableStateOf(prefs.getString("dpi_interval", "10-20") ?: "10-20")

    var hasSeenGuide by mutableStateOf(prefs.getBoolean("has_seen_guide", false))
    var showGuide by mutableStateOf(false)

    var isAppFilterEnabled by mutableStateOf(prefs.getBoolean("app_filter_enabled", false))
    var isBypassMode by mutableStateOf(prefs.getBoolean("app_filter_bypass", false))
    var isDpiOnlyMode by mutableStateOf(prefs.getBoolean("dpi_only_mode", false))
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

    fun updateDpiOnlyMode(value: Boolean) {
        isDpiOnlyMode = value
        prefs.edit { putBoolean("dpi_only_mode", value) }
        restartVpnIfConnected()
    }

    fun updateDpiPackets(value: String) {
        dpiPackets = value
        prefs.edit { putString("dpi_packets", value) }
        restartVpnIfConnected()
    }
    
    fun updateDpiLength(value: String) {
        dpiLength = value
        prefs.edit { putString("dpi_length", value) }
        restartVpnIfConnected()
    }
    
    fun updateDpiInterval(value: String) {
        dpiInterval = value
        prefs.edit { putString("dpi_interval", value) }
        restartVpnIfConnected()
    }

    val dpiTestTargets = listOf(
        "youtube.com" to "https://youtube.com",
        "youtu.be" to "https://youtu.be",
        "rr1---sn-4axm-n8vs.googlevideo.com" to "https://rr1---sn-4axm-n8vs.googlevideo.com",
        "rr1---sn-gvnuxaxjvh-o8ge.googlevideo.com" to "https://rr1---sn-gvnuxaxjvh-o8ge.googlevideo.com",
        "rr1---sn-ug5onuxaxjvh-p3ul.googlevideo.com" to "https://rr1---sn-ug5onuxaxjvh-p3ul.googlevideo.com",
        "rr1---sn-ug5onuxaxjvh-n8v6.googlevideo.com" to "https://rr1---sn-ug5onuxaxjvh-n8v6.googlevideo.com",
        "rr4---sn-q4flrnsl.googlevideo.com" to "https://rr4---sn-q4flrnsl.googlevideo.com",
        "rr10---sn-gvnuxaxjvh-304z.googlevideo.com" to "https://rr10---sn-gvnuxaxjvh-304z.googlevideo.com",
        "rr14---sn-n8v7kn7r.googlevideo.com" to "https://rr14---sn-n8v7kn7r.googlevideo.com",
        "rr16---sn-axq7sn76.googlevideo.com" to "https://rr16---sn-axq7sn76.googlevideo.com",
        "rr1---sn-8ph2xajvh-5xge.googlevideo.com" to "https://rr1---sn-8ph2xajvh-5xge.googlevideo.com",
        "rr1---sn-gvnuxaxjvh-5gie.googlevideo.com" to "https://rr1---sn-gvnuxaxjvh-5gie.googlevideo.com",
        "rr12---sn-gvnuxaxjvh-bvwz.googlevideo.com" to "https://rr12---sn-gvnuxaxjvh-bvwz.googlevideo.com",
        "rr5---sn-n8v7knez.googlevideo.com" to "https://rr5---sn-n8v7knez.googlevideo.com",
        "rr1---sn-u5uuxaxjvhg0-ocje.googlevideo.com" to "https://rr1---sn-u5uuxaxjvhg0-ocje.googlevideo.com",
        "rr2---sn-q4fl6ndl.googlevideo.com" to "https://rr2---sn-q4fl6ndl.googlevideo.com",
        "rr5---sn-gvnuxaxjvh-n8vk.googlevideo.com" to "https://rr5---sn-gvnuxaxjvh-n8vk.googlevideo.com",
        "rr4---sn-jvhnu5g-c35d.googlevideo.com" to "https://rr4---sn-jvhnu5g-c35d.googlevideo.com",
        "rr1---sn-q4fl6n6y.googlevideo.com" to "https://rr1---sn-q4fl6n6y.googlevideo.com",
        "rr2---sn-hgn7ynek.googlevideo.com" to "https://rr2---sn-hgn7ynek.googlevideo.com",
        "rr1---sn-xguxaxjvh-gufl.googlevideo.com" to "https://rr1---sn-xguxaxjvh-gufl.googlevideo.com",
        "i.ytimg.com" to "https://i.ytimg.com",
        "i9.ytimg.com" to "https://i9.ytimg.com",
        "yt3.ggpht.com" to "https://yt3.ggpht.com",
        "yt4.ggpht.com" to "https://yt4.ggpht.com",
        "googleapis.com" to "https://googleapis.com",
        "jnn-pa.googleapis.com" to "https://jnn-pa.googleapis.com",
        "googleusercontent.com" to "https://googleusercontent.com",
        "signaler-pa.youtube.com" to "https://signaler-pa.youtube.com",
        "youtubei.googleapis.com" to "https://youtubei.googleapis.com",
        "manifest.googlevideo.com" to "https://manifest.googlevideo.com",
        "yt3.googleusercontent.com" to "https://yt3.googleusercontent.com",
        "cloudflare.net" to "https://cloudflare.net",
        "cloudflare.com" to "https://cloudflare.com",
        "cloudflarecn.net" to "https://cloudflarecn.net",
        "cloudflare-ech.com" to "https://cloudflare-ech.com"
    )
    var testResults by mutableStateOf<Map<String, String>>(emptyMap())
    // var isTestingDpi by mutableStateOf(false)

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

    private val countryCache = ConcurrentHashMap<String, String>()
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

    fun completeGuide() {
        setGuideSeen()
        showGuide = false
        currentTutorialStep = 0
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
                    .url("https://raw.githubusercontent.com/Apreverra/lidovpn/refs/heads/main/update.json")
                    .build()
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            val latest = gson.fromJson(body, VpnUpdateInfo::class.java)
                            val current = getApplication<android.app.Application>().packageManager
                                .getPackageInfo(getApplication<android.app.Application>().packageName, 0).versionName ?: "1.0.0"
                            
                            if (isNewerVersion(current, latest.version)) {
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

    private fun isNewerVersion(current: String, latest: String): Boolean {
        try {
            // Убираем лишние символы (например, 'v' в начале) и разбиваем по точкам
            val currParts = current.replace(Regex("[^0-9.]"), "").split(".").map { it.toInt() }
            val lateParts = latest.replace(Regex("[^0-9.]"), "").split(".").map { it.toInt() }
            
            val maxLength = maxOf(currParts.size, lateParts.size)
            
            for (i in 0 until maxLength) {
                val currV = currParts.getOrElse(i) { 0 }
                val lateV = lateParts.getOrElse(i) { 0 }
                
                if (lateV > currV) return true
                if (lateV < currV) return false
            }
        } catch (_: Exception) {
            // Если формат странный (например, DEBUG версия), возвращаем простое сравнение строк
            return latest != current
        }
        return false
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

    fun simulateUpdate() {
        val fakeInfo = VpnUpdateInfo(
            version = "9.9.9-DEBUG",
            description = "Это тестовое обновление для проверки интерфейса загрузки.\n- Добавлена магия\n- Исправлены баги в параллельной вселенной",
            downloadUrl = "https://example.com/fake.apk"
        )
        updateInfo = fakeInfo
    }

    fun startFakeDownload() {
        viewModelScope.launch {
            isDownloadingUpdate = true
            downloadProgress = 0f
            downloadSpeed = "0 KB/s"
            downloadedSizeInfo = "0 MB / 100 MB"
            
            val totalSize = 100 * 1024 * 1024L // 100MB
            var currentRead = 0L
            val startTime = System.currentTimeMillis()

            while (currentRead < totalSize) {
                if (!isDownloadingUpdate) break
                kotlinx.coroutines.delay(100)
                val chunk = (1..3).random() * 1024 * 1024L // 1-3MB per step
                currentRead += chunk
                if (currentRead > totalSize) currentRead = totalSize
                
                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                val speed = if (elapsedSeconds > 0) currentRead / elapsedSeconds else 0.0
                
                downloadProgress = currentRead.toFloat() / totalSize
                downloadSpeed = formatSpeed(speed)
                downloadedSizeInfo = "${formatSize(currentRead)} / ${formatSize(totalSize)}"
            }
            
            if (isDownloadingUpdate) {
                showSnackbar(if (language == AppLanguage.RU) "Имитация загрузки завершена" else "Fake download finished")
                isDownloadingUpdate = false
                updateInfo = null
            }
        }
    }

    fun debugClearAll() {
        prefs.edit { clear() }
        servers = emptyList()
        selectedServer = null
        showSnackbar("Debug: All data cleared")
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
        val serversToSave = _servers.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val json = gson.toJson(serversToSave)
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

    fun setGuideSeen() {
        hasSeenGuide = true
        prefs.edit { putBoolean("has_seen_guide", true) }
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
        if (isConnected) {
            disconnect()
            return
        }

        if (isDpiOnlyMode) {
            val dpiServer = VpnServer(
                name = "Direct DPI Bypass",
                type = "DPI_ONLY",
                host = "127.0.0.1",
                port = 0,
                uuid = ""
            )
            connect(dpiServer, context)
            return
        }

        val server = selectedServer ?: pickBestServer()
        if (server != null) {
            connect(server, context)
        } else {
            showSnackbar(if (language == AppLanguage.RU) "Пожалуйста, сначала выберите сервер" else "Please select a server first")
        }
    }

    private fun pickBestServer(): VpnServer? {
        return servers.firstOrNull { it.status == ServerStatus.WORKING }
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
            putExtra("DPI_PACKETS", dpiPackets)
            putExtra("DPI_LENGTH", dpiLength)
            putExtra("DPI_INTERVAL", dpiInterval)
            putExtra("DPI_ENGINE", dpiEngine)
            
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

    private fun tryGetGeoInfo(host: String): Pair<String, String>? {
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
                val body = client.newCall(request).execute().use { it.body?.string() ?: "" }

                if (body.isEmpty()) continue
                val json = org.json.JSONObject(body)
                val code = json.optString(keys.first)
                val name = if (keys.second.isNotEmpty()) json.optString(keys.second) else ""
                if (code.isNotEmpty()) return Pair(code, name)
            } catch (e: Exception) {
                android.util.Log.w("GeoIP", "Provider failed: $url - ${e.message}")
            }
        }
        return null
    }

    private fun fetchNameFromRestCountries(code: String): String? {
        return try {
            val url = "https://restcountries.com/v3.1/alpha/$code?fields=name"
            val request = Request.Builder().url(url).build()
            val body = client.newCall(request).execute().use { it.body?.string() ?: "" }
            if (body.isEmpty()) return null
            val json = org.json.JSONObject(body)
            json.getJSONObject("name").optString("common")
        } catch (_: Exception) {
            null
        }
    }

    private fun updateServerInList(server: VpnServer, transform: (VpnServer) -> VpnServer) {
        val index = _servers.indexOfFirst { it.host == server.host && it.port == server.port && it.name == server.name }
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
        isChecking = true
        checkJob = viewModelScope.launch {
            LogManager.addLog(if (language == AppLanguage.RU) "Запуск полной проверки серверов..." else "Starting full server health check...")
            
            // Сбрасываем статусы
            servers = servers.map { it.copy(status = ServerStatus.UNKNOWN, ping = null, pingTelegram = null) }

            val semaphore = Semaphore(concurrentChecks)
            try {
                servers.map { server ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            var pingResult: Long? = null
                            var success = false
                            
                            try {
                                if (!this@launch.isActive) return@withPermit
                                
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
                                        utlsFingerprint = utlsFingerprint,
                                        dpiPackets = dpiPackets,
                                        dpiLength = dpiLength,
                                        dpiInterval = dpiInterval,
                                        socksProxyPort = if (isDpiOnlyMode) 1080 else 0
                                    )
                                    val delay = Libv2ray.measureOutboundDelay(vpnConfig, pingTargetUrl)
                                    
                                    if (delay > 0) {
                                        pingResult = delay
                                        success = true
                                    }
                                }

                                if (success) {
                                    if (this@launch.isActive) {
                                        LogManager.addLog("Check: ${server.name} -> ONLINE (${pingResult} ms)")
                                    }
                                    
                                    withContext(Dispatchers.Main) {
                                        updateServerInList(server) {
                                            it.copy(status = ServerStatus.WORKING, ping = pingResult)
                                        }
                                    }

                                    launch {
                                        val country = fetchCountryWithCache(server)
                                        if (country.isNotEmpty()) {
                                            withContext(Dispatchers.Main) {
                                                updateServerInList(server) { it.copy(country = country) }
                                            }
                                        }
                                    }
                                } else {
                                    if (this@launch.isActive) {
                                        LogManager.addLog("Check: ${server.name} -> FAILED OR SLOW")
                                    }
                                    withContext(Dispatchers.Main) {
                                        updateServerInList(server) { it.copy(status = ServerStatus.NOT_WORKING) }
                                    }
                                }
                            } catch (e: Exception) {
                                if (this@launch.isActive && e !is kotlinx.coroutines.CancellationException) {
                                    LogManager.addLog("Check Error: ${server.name} -> ${e.message}")
                                }
                                withContext(Dispatchers.Main) {
                                    updateServerInList(server) { it.copy(status = ServerStatus.NOT_WORKING) }
                                }
                            }
                        }
                    }
                }.joinAll()
                
                applySort()
                saveServers()
            } catch (e: Exception) {
                // Ignore
            } finally {
                isChecking = false
                withContext(Dispatchers.Main) {
                    applySort()
                    val finalWorkingCount = servers.count { it.status == ServerStatus.WORKING }
                    showSnackbar(
                        if (language == AppLanguage.RU) "Проверка окончена. Рабочих: $finalWorkingCount"
                        else "Check finished. Working: $finalWorkingCount"
                    )
                }
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
        isCheckingTelegram = true
        
        // 1. Сбрасываем пинг ТГ перед началом только для рабочих серверов
        servers = servers.map { 
            if (it.status == ServerStatus.WORKING) it.copy(pingTelegram = null) else it 
        }

        val semaphore = Semaphore(concurrentChecks)
        checkJob = viewModelScope.launch {
            LogManager.addLog("Запуск проверки Telegram для ${workingServers.size} серверов...")
            try {
                workingServers.map { server ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            var pingResult: Long? = null
                            try {
                                if (!this@launch.isActive) return@withPermit
                                val vpnConfig = XrayConfigGenerator.generateConfig(
                                    server = server,
                                    dns = dnsServer,
                                    sniffing = isSniffingEnabled,
                                    mux = isMuxEnabled,
                                    fragment = isFragmentEnabled,
                                    routingMode = "ONLY_PROXY",
                                    mtu = mtu,
                                    assetPath = getApplication<android.app.Application>().filesDir.absolutePath,
                                    utlsFingerprint = utlsFingerprint,
                                    dpiPackets = dpiPackets,
                                    dpiLength = dpiLength,
                                    dpiInterval = dpiInterval,
                                    socksProxyPort = if (isDpiOnlyMode) 1080 else 0
                                )
                                val startTime = System.currentTimeMillis()
                                val delay = Libv2ray.measureOutboundDelay(vpnConfig, "https://t.me/telegram")
                                val endTime = System.currentTimeMillis()
                                val totalDuration = endTime - startTime

                                if (delay > 0 && totalDuration < 10000) {
                                    pingResult = totalDuration
                                    if (this@launch.isActive) {
                                        LogManager.addLog("TG Check: ${server.name} -> ONLINE ($totalDuration ms)")
                                    }
                                } else {
                                    if (this@launch.isActive) {
                                        LogManager.addLog("TG Check: ${server.name} -> FAILED")
                                    }
                                }
                            } catch (e: Exception) {
                                if (this@launch.isActive && e !is kotlinx.coroutines.CancellationException) {
                                    LogManager.addLog("TG Check Error: ${server.name} -> ${e.message}")
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    val currentIndex = servers.indexOfFirst { it.host == server.host && it.port == server.port && it.name == server.name }
                                    if (currentIndex != -1) {
                                        val newList = servers.toMutableList()
                                        newList[currentIndex] = newList[currentIndex].copy(pingTelegram = pingResult)
                                        servers = newList 
                                    }
                                }
                            }
                        }
                    }
                }.joinAll()
                
                servers = getSortedList(servers)
                saveServers()
            } catch (e: Exception) {
                // Ignore
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
                    .tutorialHighlight(viewModel.currentTutorialStep == 8)
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
            if (viewModel.isDpiOnlyMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (viewModel.language == AppLanguage.RU) "Режим: DPI Bypass" else "Mode: DPI Bypass",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (viewModel.language == AppLanguage.RU) "Прямое подключение без прокси" else "Direct connection without proxy",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
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
                } ?: Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (viewModel.language == AppLanguage.RU) "Сервер не выбран" else "No server selected",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
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
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier.tutorialHighlight(viewModel.currentTutorialStep == 7)
                    ) {
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
                    enabled = !viewModel.isFetching && viewModel.selectedSources.isNotEmpty(),
                    modifier = Modifier.tutorialHighlight(viewModel.currentTutorialStep == 4)
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
                    enabled = (viewModel.isCheckingTelegram || workingServers.isNotEmpty()),
                    modifier = Modifier.tutorialHighlight(viewModel.currentTutorialStep == 6)
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



                Button(
                    onClick = { 
                        if (viewModel.isChecking) viewModel.stopCheck() 
                        else viewModel.checkAllServers() 
                    },
                    enabled = viewModel.isChecking || viewModel.servers.isNotEmpty(),
                    colors = if (viewModel.isChecking) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.buttonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.tutorialHighlight(viewModel.currentTutorialStep == 5)
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
    val viewModel: AppViewModel = viewModel()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .tutorialHighlight(viewModel.currentTutorialStep == 7 && server.status == ServerStatus.WORKING),
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send, // Иконка самолетика
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = Color(0xFF24A1DE)
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        text = "$it ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF24A1DE)
                                    )
                                }
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
    val logs = LogManager.vpnLogs
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Independent scroll states for each tab
    val allScrollState = rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    val checkScrollState = rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    val geoScrollState = rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    val coreScrollState = rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }

    val currentScrollState = when(selectedTab) {
        1 -> checkScrollState
        2 -> geoScrollState
        3 -> coreScrollState
        else -> allScrollState
    }

    val filteredLogs = remember(logs, selectedTab) {
        when (selectedTab) {
            // Tab 1: Server Health Checks (Ping, Telegram)
            1 -> logs.filter { it.contains("Check") && !it.contains("Geo:") }
            // Tab 2: GeoIP & Geo-data logs
            2 -> logs.filter { it.contains("Geo:") || it.contains("geo-данные") || it.contains("Geo Data") }
            // Tab 3: Connection logs & Xray Engine logs
            3 -> logs.filter { !it.contains("Check") && !it.contains("Geo:") && (it.contains(":") || it.contains("[")) }
            else -> logs
        }
    }

    // Auto-scroll logic for current tab
    LaunchedEffect(filteredLogs.size) {
        val layoutInfo = currentScrollState.layoutInfo
        val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
        if (lastVisibleItem != null && lastVisibleItem.index >= filteredLogs.size - 5) {
            currentScrollState.animateScrollToItem(filteredLogs.size - 1)
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

        SecondaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {}
        ) {
            val tabs = if (viewModel.language == AppLanguage.RU) 
                listOf("Все", "Пинг", "Гео", "Ядро") else listOf("All", "Ping", "Geo", "Core")
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
                    state = currentScrollState,
                    modifier = Modifier.padding(8.dp).fillMaxSize()
                ) {
                    items(filteredLogs) { log ->
                        val color = when {
                            log.contains("Geo:") -> Color(0xFFCE93D8) // Light Purple for Geo
                            (log.startsWith("TG Check") || log.startsWith("Telegram Check")) && (log.contains("FAILED") || log.contains("Error")) -> Color(0xFF1565C0)
                            log.startsWith("TG Check") || log.startsWith("Telegram Check") -> Color(0xFF24A1DE)
                            log.contains("[Error]") || log.contains("FAILED") || log.contains("Error:") -> Color(0xFFF44336)
                            log.contains("[Warning]") -> Color(0xFFFF9800)
                            log.contains("ONLINE") -> Color(0xFF4CAF50)
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
            val showButton by remember(filteredLogs.size, selectedTab) {
                derivedStateOf {
                    val layoutInfo = currentScrollState.layoutInfo
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
                                currentScrollState.animateScrollToItem(filteredLogs.size - 1)
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
                onClick = { 
                    if (info.version.contains("DEBUG")) {
                        viewModel.startFakeDownload()
                    } else {
                        viewModel.downloadAndInstallUpdate(context, info)
                    }
                },
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
    var showDebugMenu by remember { mutableStateOf(false) }
    var versionClickCount by remember { mutableIntStateOf(0) }
    
    // Временные состояния для полей ввода чисел
    var concurrentChecksText by remember { mutableStateOf(viewModel.concurrentChecks.toString()) }
    var mtuText by remember { mutableStateOf(viewModel.mtu.toString()) }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(text = if (viewModel.language == AppLanguage.RU) "Настройки" else "Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 1. DPI Bypass (NEW)
        item {
            Text(if (viewModel.language == AppLanguage.RU) "Обход блокировок (DPI)" else "DPI Bypass", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            SettingsSwitch(
                label = if (viewModel.language == AppLanguage.RU) "Только DPI Bypass (Direct)" else "DPI Bypass Only (Direct)",
                subtitle = if (viewModel.language == AppLanguage.RU) "Работает без прокси-сервера. Только обход цензуры." else "Works without a proxy server. Only censorship bypass.",
                checked = viewModel.isDpiOnlyMode,
                onCheckedChange = { viewModel.updateDpiOnlyMode(it) }
            )

            AnimatedVisibility(visible = viewModel.isDpiOnlyMode || viewModel.isFragmentEnabled) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        if (viewModel.language == AppLanguage.RU) "Командная строка DPI" else "DPI Command Line",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = viewModel.dpiCommand,
                        onValueChange = { viewModel.updateDpiCommand(it) },
                        label = { Text("Command Line (e.g. -o1 -d1 -s1)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("-o1 -d1 -s1 -a1") },
                        singleLine = false,
                        maxLines = 3
                    )
                    
                    
                    /* 
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (viewModel.language == AppLanguage.RU) "Движок DPI:" else "DPI Engine:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row {
                            listOf("Xray", "ByeByeDPI").forEach { engine ->
                                FilterChip(
                                    selected = viewModel.dpiEngine == engine,
                                    onClick = { viewModel.updateDpiEngine(engine) },
                                    label = { Text(engine) },
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                    */

                    Spacer(Modifier.height(16.dp))
                    
                    Button(
                        onClick = { viewModel.runDpiTest() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        enabled = !viewModel.isOptimizingDpi
                    ) {
                        if (viewModel.isOptimizingDpi) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onTertiary)
                        } else {
                            Icon(Icons.Default.Speed, null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (viewModel.language == AppLanguage.RU) "Проверить настройки" else "Test Settings")
                    }

                    if (viewModel.isOptimizingDpi) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { viewModel.optimizationProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                        )
                    }

                    if (viewModel.testResults.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        
                        Card(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                val workingCount = viewModel.testResults.values.count { it.endsWith("ms") }
                                val totalCount = viewModel.dpiTestTargets.size
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (viewModel.language == AppLanguage.RU) "Результаты проверки:" else "Test Results:",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "$workingCount / $totalCount",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (workingCount == totalCount) Color(0xFF4CAF50) else if (workingCount > 0) Color(0xFFFFC107) else Color(0xFFF44336)
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

                                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                    items(viewModel.testResults.toList()) { (name, result) ->
                                        val isWorking = result.matches(Regex("^[0-9]+ms$"))
                                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                            Text(
                                                text = name,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isWorking) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                                            )
                                            Text(
                                                text = result,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isWorking) Color(0xFF4CAF50) else Color(0xFFF44336)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. App Filtering (NEW)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialHighlight(viewModel.currentTutorialStep == 2)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, fontWeight = FontWeight.Bold)
                            }
                            if (file.exists) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${String.format(Locale.getDefault(), "%.2f", file.size / 1024.0 / 1024.0)} MB", style = MaterialTheme.typography.bodySmall)
                                    val date = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(file.lastModified))
                                    Text("${if (viewModel.language == AppLanguage.RU) "Локально: " else "Local: "}$date", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    if (file.remoteVersion.isNotEmpty()) {
                                        Text("GitHub: ${file.remoteVersion}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(if (viewModel.language == AppLanguage.RU) "Отсутствует" else "Missing", color = MaterialTheme.colorScheme.error)
                                    if (file.remoteVersion.isNotEmpty()) {
                                        Text("GitHub: ${file.remoteVersion}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
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
                } catch (_: Exception) { "1.0.0" }
            }
            Text(if (viewModel.language == AppLanguage.RU) "Обновление приложения" else "App Update", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth().clickable { 
                    versionClickCount++
                    if (versionClickCount >= 5) {
                        showDebugMenu = true
                        versionClickCount = 0
                    }
                },
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

            Button(
                onClick = { viewModel.showGuide = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Help, null)
                Spacer(Modifier.width(8.dp))
                Text(if (viewModel.language == AppLanguage.RU) "Показать обучение" else "Show Tutorial")
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showDebugMenu) {
        DebugMenuDialog(viewModel = viewModel, onDismiss = { showDebugMenu = false })
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
fun DebugMenuDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Debug / Admin Menu", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                
                Button(
                    onClick = { 
                        viewModel.simulateUpdate()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simulate Fake Update")
                }
                
                Spacer(Modifier.height(8.dp))
                
                Button(
                    onClick = { 
                        viewModel.debugClearAll()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All Data (Prefs + Servers)")
                }
                
                Spacer(Modifier.height(8.dp))
                
                Button(
                    onClick = { 
                        LogManager.addLog("[Debug] Manual test log entry")
                        viewModel.showSnackbar("Logged test entry")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Test Log Entry")
                }

                Spacer(Modifier.height(16.dp))
                
                TextButton(onClick = onDismiss) {
                    Text("Close")
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

@Composable
fun Modifier.tutorialHighlight(enabled: Boolean): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "tutorial")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    return if (enabled) {
        this.then(
            Modifier
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
        )
    } else this
}

@Composable
fun OnboardingGuide(viewModel: AppViewModel) {
    val isRu = viewModel.language == AppLanguage.RU

    LaunchedEffect(viewModel.showGuide) {
        if (viewModel.showGuide) {
            viewModel.currentTutorialStep = 1
        }
    }

    if (viewModel.currentTutorialStep == 0 && !viewModel.hasSeenGuide) {
        AlertDialog(
            onDismissRequest = { viewModel.setGuideSeen() },
            title = { Text(if (isRu) "Добро пожаловать!" else "Welcome!") },
            text = { Text(if (isRu) "Хотите пройти краткое обучение по работе с приложением?" else "Would you like a short tutorial on how to use the app?") },
            confirmButton = {
                Button(onClick = { viewModel.currentTutorialStep = 1 }) {
                    Text(if (isRu) "Да, обучите меня" else "Yes, show me")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setGuideSeen() }) {
                    Text(if (isRu) "Пропустить" else "Skip")
                }
            }
        )
    }

    if (viewModel.currentTutorialStep > 0) {
        // Handle screen transitions during tutorial
        LaunchedEffect(viewModel.currentTutorialStep) {
            when (viewModel.currentTutorialStep) {
                1, 9 -> viewModel.currentDestination = AppDestinations.HOME
                2 -> viewModel.currentDestination = AppDestinations.SETTINGS
                3, 4, 5, 6, 7, 8 -> viewModel.currentDestination = AppDestinations.SERVERS
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)) // Lighter dimming
                .clickable(enabled = false) {} 
        ) {
            val content = when (viewModel.currentTutorialStep) {
                1 -> StepContent(
                    if (isRu) "Добро пожаловать" else "Welcome",
                    if (isRu) "Давайте быстро разберемся, как пользоваться приложением." else "Let's quickly learn how to use the app.",
                    Alignment.Center
                )
                2 -> StepContent(
                    if (isRu) "Источники серверов" else "Server Sources",
                    if (isRu) "В настройках нажмите 'Выбрать конфигурации', чтобы отметить нужные списки серверов." else "In settings, tap 'Select Configurations' to check the desired server lists.",
                    Alignment.Center
                )
                3 -> StepContent(
                    if (isRu) "Раздел Серверы" else "Servers Section",
                    if (isRu) "Теперь перейдем во вкладку со списком серверов." else "Now let's go to the servers list tab.",
                    Alignment.TopCenter
                )
                4 -> StepContent(
                    if (isRu) "Загрузка серверов" else "Download Servers",
                    if (isRu) "Нажмите на иконку загрузки, чтобы получить список свежих серверов из выбранных источников." else "Tap the download icon to fetch fresh servers from selected sources.",
                    Alignment.Center
                )
                5 -> StepContent(
                    if (isRu) "Проверка пинга" else "Check Ping",
                    if (isRu) "Кнопка 'Проверить' замерит задержку до каждого сервера. Чем меньше число, тем лучше!" else "The 'Check' button measures delay to each server. Lower is better!",
                    Alignment.Center
                )
                6 -> StepContent(
                    if (isRu) "Пинг Telegram" else "Telegram Ping",
                    if (isRu) "Иконка самолетика проверит связь именно с Telegram. Это поможет найти лучший сервер для мессенджера." else "The airplane icon checks connection specifically to Telegram. It helps find the best server for the app.",
                    Alignment.Center
                )
                7 -> StepContent(
                    if (isRu) "Сортировка" else "Sorting",
                    if (isRu) "Используйте эту кнопку, чтобы отсортировать серверы по пингу, стране или скорости Telegram." else "Use this button to sort servers by ping, country, or Telegram speed.",
                    Alignment.TopCenter
                )
                8 -> StepContent(
                    if (isRu) "Выбор сервера" else "Select Server",
                    if (isRu) "Просто нажмите на любой сервер в списке, чтобы выбрать его для подключения." else "Just tap any server in the list to select it for connection.",
                    Alignment.BottomCenter
                )
                9 -> StepContent(
                    if (isRu) "Подключение" else "Connection",
                    if (isRu) "Теперь всё готово! Возвращайтесь на главную и жмите кнопку питания для запуска VPN." else "Now everything is ready! Go back to Home and tap the power button to start the VPN.",
                    Alignment.TopCenter
                )
                else -> StepContent("", "", Alignment.Center)
            }

            // Guidance card
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                Card(
                    modifier = Modifier
                        .align(content.alignment)
                        .fillMaxWidth()
                        .padding(
                            bottom = if (content.alignment == Alignment.BottomCenter) 80.dp else 0.dp,
                            top = if (content.alignment == Alignment.TopCenter) 80.dp else 0.dp
                        ),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = content.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = content.description,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { viewModel.completeGuide() }) {
                                Text(if (isRu) "Пропустить" else "Skip")
                            }
                            Button(
                                onClick = {
                                    if (viewModel.currentTutorialStep < 9) viewModel.currentTutorialStep++ else viewModel.completeGuide()
                                }
                            ) {
                                Text(if (viewModel.currentTutorialStep < 9) (if (isRu) "Далее" else "Next") else (if (isRu) "Понятно" else "Got it"))
                            }
                        }
                    }
                }
            }
        }
    }
}

val AppViewModel.tutorialStep: Int
    @Composable get() = (MainActivity.instance?.viewModel as? AppViewModel)?.let { vm ->
        var step by remember { mutableIntStateOf(0) }
        // This is a bit of a hack to observe the internal step of OnboardingGuide
        // but since they share the same ViewModel context, we can add a property to ViewModel
        vm.currentTutorialStep
    } ?: 0

// Add this to AppViewModel class

data class StepContent(
    val title: String,
    val description: String,
    val alignment: Alignment
)
