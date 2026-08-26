package com.lido.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import libv2ray.Libv2ray

class AppViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    var searchQuery by mutableStateOf("")
    private val _filteredServers = mutableStateListOf<VpnServer>()
    val filteredServers: List<VpnServer> get() = _filteredServers

    var currentDestination by mutableStateOf(AppDestinations.HOME)
    var currentTutorialStep by mutableIntStateOf(0)
    var selectedServerTab by mutableIntStateOf(0) // 0: GitHub, 1: My Servers
    var selectedCountry by mutableStateOf(prefs.getString("selected_country", "") ?: "")

    private fun isServerInCountry(server: VpnServer, unifiedCountry: String): Boolean {
        if (unifiedCountry.isEmpty()) return true
        val targetFlag = extractFlag(unifiedCountry) ?: return false
        val serverFlag = extractFlag(server.country) ?: return false
        return targetFlag == serverFlag
    }

    fun updateSelectedCountry(country: String) {
        selectedCountry = country
        prefs.edit { putString("selected_country", country) }
        applySort()
        
        // Auto-select best server from this country in Simple Mode
        if (appMode == AppMode.SIMPLE) {
            val candidates = if (country.isEmpty()) servers.filter { !it.isManual }
                             else servers.filter { !it.isManual && isServerInCountry(it, country) }
            val best = getSortedList(candidates).firstOrNull { it.status == ServerStatus.WORKING }
            if (best != null) {
                updateSelectedServer(best)
            }
        }
    }
    
    val availableCountries: List<String> by derivedStateOf {
        val isRu = language == AppLanguage.RU
        
        // Include countries that have ANY servers, so user can select region before check
        val sourceServers = servers.filter { !it.isManual }
        
        val groups = sourceServers.map { it.country }
            .filter { it.isNotEmpty() && !it.contains("?", ignoreCase = true) && !it.contains("unknown", ignoreCase = true) && !it.contains("Неизвестная", ignoreCase = true) }
            .groupBy { extractFlag(it) ?: "" }
            .filter { it.key.isNotEmpty() }
            
        groups.map { (flag, _) ->
            val code = flagToCode(flag)
            val name = if (code != null) getCountryName(code, isRu) else ""
            
            if (name.isNotEmpty()) "$flag $name" else flag
        }.distinct().sortedBy { it.substringAfter(" ").lowercase() }
    }

    var appMode by mutableStateOf(
        prefs.getString("app_mode", null)?.let {
            try { AppMode.valueOf(it) } catch (_: Exception) { null }
        } ?: AppMode.SIMPLE
    )
    var mainTargetName by mutableStateOf(prefs.getString("main_target_name", "") ?: "")
    var additionalTargetNames = mutableStateListOf<String>().apply {
        val saved = prefs.getStringSet("additional_target_names", null)
        if (saved != null) addAll(saved)
    }

    fun ensureTargetExists(target: CheckTarget) {
        if (checkTargets.none { it.name == target.name }) {
            checkTargets.add(target)
            saveCheckTargets()
        }
    }
    var hasCompletedInitialSetup by mutableStateOf(prefs.getBoolean("has_completed_setup", false))

    fun updateAppMode(mode: AppMode) {
        appMode = mode
        prefs.edit { putString("app_mode", mode.name) }
    }

    fun updateMainTarget(name: String) {
        val oldMain = mainTargetName
        mainTargetName = name
        prefs.edit { putString("main_target_name", name) }
        
        // Ensure the NEW main is enabled
        val idx = checkTargets.indexOfFirst { it.name == name }
        if (idx != -1) {
            checkTargets[idx] = checkTargets[idx].copy(isEnabled = true)
        }
        
        // Remove from additional if it was there
        additionalTargetNames.remove(name)

        syncTargetStates()
    }

    fun toggleAdditionalTarget(name: String) {
        if (additionalTargetNames.contains(name)) {
            additionalTargetNames.remove(name)
        } else {
            additionalTargetNames.add(name)
        }
        prefs.edit { putStringSet("additional_target_names", additionalTargetNames.toSet()) }
        syncTargetStates()
    }

    private fun syncTargetStates() {
        // 1. Cleanup names that no longer exist in checkTargets
        val existingNames = checkTargets.map { it.name }.toSet()
        if (mainTargetName !in existingNames) mainTargetName = ""
        additionalTargetNames.removeAll { it !in existingNames }

        // 2. Ensure main target is enabled
        if (mainTargetName.isNotEmpty()) {
            val idx = checkTargets.indexOfFirst { it.name == mainTargetName }
            if (idx != -1 && !checkTargets[idx].isEnabled) {
                checkTargets[idx] = checkTargets[idx].copy(isEnabled = true)
            }
        }
        
        // 3. Ensure additional targets are enabled
        additionalTargetNames.forEach { name ->
            val idx = checkTargets.indexOfFirst { it.name == name }
            if (idx != -1 && !checkTargets[idx].isEnabled) {
                checkTargets[idx] = checkTargets[idx].copy(isEnabled = true)
            }
        }

        // 4. Disable everything else
        checkTargets.forEachIndexed { index, target ->
            val shouldBeEnabled = target.name == mainTargetName || additionalTargetNames.contains(target.name)
            if (target.isEnabled != shouldBeEnabled) {
                checkTargets[index] = target.copy(isEnabled = shouldBeEnabled)
            }
        }
        saveCheckTargets()
    }

    fun completeInitialSetup() {
        hasCompletedInitialSetup = true
        prefs.edit { putBoolean("has_completed_setup", true) }
        syncTargetStates()
    }

    fun resetInitialSetup() {
        hasCompletedInitialSetup = false
        prefs.edit { putBoolean("has_completed_setup", false) }
    }

    var isAutoSettingUp by mutableStateOf(false)
    var autoSetupStatus by mutableStateOf("")
    var autoSetupSubStatus by mutableStateOf("")
    private var autoSetupJob: Job? = null

    fun runSimpleAutoSetup() {
        autoSetupJob?.cancel()
        autoSetupJob = viewModelScope.launch {
            isAutoSettingUp = true
            try {
                autoSetupStatus = if (language == AppLanguage.RU) "Подготовка..." else "Preparing..."
                autoSetupSubStatus = if (language == AppLanguage.RU) "Загрузка серверов..." else "Fetching servers..."
                
                // 1. Ensure we have sources
                if (selectedSources.isEmpty()) {
                    val provider = configProviders.find { it.id == "whoahaow" } ?: configProviders.first()
                    val bypassCategory = provider.categories.find { it.name.contains("bypass/") }
                    
                    if (bypassCategory != null) {
                        val allUrl = bypassCategory.items.find { it.name == "bypass-all" }?.url
                        if (allUrl != null) toggleSource(allUrl)
                    } else {
                        val defaultUrl = provider.categories.first().items.first().url
                        toggleSource(defaultUrl)
                    }
                }
                
                // 2. Fetch if empty
                if (servers.none { !it.isManual }) {
                    fetchServers()
                    while(isFetching && isActive) {
                        autoSetupSubStatus = if (language == AppLanguage.RU) "Загружено: ${servers.size}" else "Servers: ${servers.size}"
                        delay(500)
                    }
                }
                
                if (!isActive) return@launch

                // 3. Ensure geo data
                if (!geoFilesInfo.all { it.exists }) {
                    autoSetupStatus = if (language == AppLanguage.RU) "Гео-данные..." else "Geo Data..."
                    downloadGeoData()
                    while(isDownloadingGeo && isActive) {
                        autoSetupSubStatus = if (language == AppLanguage.RU) "Скачивание баз обхода..." else "Downloading routing databases..."
                        delay(500)
                    }
                }

                if (!isActive) return@launch

                // 4. Greedy Search (Find first working server for MAIN target)
                autoSetupStatus = if (language == AppLanguage.RU) "Поиск лучшего..." else "Finding best..."
                val mainTarget = checkTargets.find { it.name == mainTargetName } ?: checkTargets.first()
                val targets = listOf(mainTarget)

                val allCandidates = withContext(Dispatchers.Default) {
                    val base = servers.filter { !it.isManual }
                    val countryFiltered = if (selectedCountry.isNotEmpty()) {
                        base.filter { isServerInCountry(it, selectedCountry) }
                    } else base
                    
                    if (countryFiltered.isEmpty()) base.shuffled() else countryFiltered.shuffled()
                }

                var foundBest = false
                var currentIndex = 0
                
                while (currentIndex < allCandidates.size && !foundBest && isActive) {
                    autoSetupSubStatus = if (language == AppLanguage.RU) "Поиск живых узлов..." else "Scouting nodes..."
                    
                    val scoutBatchSize = 40
                    val subBatch = allCandidates.subList(currentIndex, minOf(currentIndex + scoutBatchSize, allCandidates.size))
                    currentIndex += subBatch.size
                    
                    val scoutResults = subBatch.map { server ->
                        async(Dispatchers.IO) {
                            try {
                                val ip = resolveHostWithTimeout(server.host, 2000) ?: return@async null
                                val ping = fastTcpPing(ip, server.port)
                                if (ping != null) server to ping else null
                            } catch (_: Exception) { null }
                        }
                    }.awaitAll().filterNotNull()
                    
                    if (scoutResults.isEmpty()) continue

                    for ((server, tcpPing) in scoutResults) {
                        if (!isActive) break
                        
                        autoSetupSubStatus = if (language == AppLanguage.RU) 
                            "Проверка: ${server.name}" 
                            else "Testing: ${server.name}"
                        
                        val (success, servicePings) = performQuickServiceCheck(server, targets)
                        if (success) {
                            val best = server.copy(status = ServerStatus.WORKING, ping = tcpPing, servicePings = servicePings)
                            updateSelectedServer(best, restartVpn = false)
                            withContext(Dispatchers.Main) {
                                updateServerInList(best) { best }
                            }
                            foundBest = true
                            autoSetupStatus = if (language == AppLanguage.RU) "Готово!" else "Ready!"
                            autoSetupSubStatus = if (language == AppLanguage.RU) "Сервер найден" else "Best server found"
                            break
                        } else {
                            withContext(Dispatchers.Main) {
                                updateServerInList(server) { it.copy(status = ServerStatus.NOT_WORKING, servicePings = servicePings) }
                            }
                        }
                    }
                }
                
                if (!foundBest && isActive) {
                    autoSetupStatus = if (language == AppLanguage.RU) "Ошибка" else "Error"
                    autoSetupSubStatus = if (language == AppLanguage.RU) "Серверы не найдены" else "No servers found"
                }
            } finally {
                delay(1000)
                isAutoSettingUp = false
            }
        }
    }

    private suspend fun performQuickServiceCheck(server: VpnServer, targets: List<CheckTarget>): Pair<Boolean, Map<String, Long?>> {
        val vpnConfig = try {
            XrayConfigGenerator.generateConfig(
                server = server,
                dns = dnsServer,
                sniffing = isSniffingEnabled,
                mux = isMuxEnabled,
                routingMode = "ONLY_PROXY",
                mtu = mtu,
                assetPath = getApplication<android.app.Application>().filesDir.absolutePath,
                utlsFingerprint = utlsFingerprint,
                isTestConfig = true
            )
        } catch (_: Exception) { null } ?: return false to emptyMap<String, Long?>()

        val results = mutableMapOf<String, Long?>()
        var anyServiceWorking = false

        // In Greedy mode, we just need the FIRST working target from the list provided
        for (target in targets) {
            try {
                val delay = withTimeoutOrNull(5000L) {
                    Libv2ray.measureOutboundDelay(vpnConfig, target.url)
                } ?: -1L
                
                if (delay > 0) {
                    results[target.name] = delay
                    anyServiceWorking = true
                    break // Stop after finding one working service for greedy setup
                }
            } catch (_: Exception) { }
        }

        return anyServiceWorking to results
    }

    fun pickNextBestServer() {
        viewModelScope.launch {
            val lastCheck = prefs.getLong("last_servers_check_time", 0L)
            val isStale = System.currentTimeMillis() - lastCheck > 60 * 60 * 1000 // Старше 1 часа - протухло

            if (isStale || servers.isEmpty()) {
                LogManager.addLog("Server list is stale, refreshing everything...")
                runSimpleAutoSetup()
                return@launch
            }

            val current = selectedServer ?: return@launch
            
            // In Simple Mode, we cycle through curated servers. In Advanced, through all working.
            var candidates = if (appMode == AppMode.SIMPLE) servers.filter { !it.isManual } else servers
            
            // RESPECT SELECTED COUNTRY
            if (selectedCountry.isNotEmpty()) {
                candidates = candidates.filter { isServerInCountry(it, selectedCountry) }
            }
            
            val sorted = getSortedList(candidates).filter { it.status == ServerStatus.WORKING }
            
            if (sorted.isEmpty()) {
                val msg = if (language == AppLanguage.RU) "Нет рабочих серверов в этом регионе" else "No working servers in this region"
                showSnackbar(msg)
                return@launch
            }

            val currentIndex = sorted.indexOfFirst { it.host == current.host && it.port == current.port }
            
            val next = if (currentIndex != -1 && currentIndex < sorted.size - 1) {
                sorted[currentIndex + 1]
            } else {
                sorted.firstOrNull()
            }
            
            if (next != null) {
                // Быстрый чек перед выбором
                autoSetupStatus = if (language == AppLanguage.RU) "Проверка..." else "Checking..."
                isAutoSettingUp = true
                
                val isAlive = withContext(Dispatchers.IO) {
                    try {
                        val ip = resolveHostWithTimeout(next.host, 2000) ?: return@withContext false
                        fastTcpPing(ip, next.port) != null
                    } catch (_: Exception) { false }
                }

                if (isAlive) {
                    updateSelectedServer(next)
                    showSnackbar(if (language == AppLanguage.RU) "Сервер заменен" else "Server switched")
                } else {
                    LogManager.addLog("Next best server is also dead, auto-setup required")
                    runSimpleAutoSetup()
                }
                isAutoSettingUp = false
            } else {
                runSimpleAutoSetup()
            }
        }
    }

    var appTheme by mutableStateOf(
        prefs.getString("app_theme", AppTheme.DARK.name)?.let {
            try { AppTheme.valueOf(it) } catch (_: Exception) { AppTheme.DARK }
        } ?: AppTheme.DARK
    )

    fun updateAppTheme(mode: AppTheme) {
        appTheme = mode
        prefs.edit { putString("app_theme", mode.name) }
    }

    var language by mutableStateOf(
        prefs.getString("language", null)?.let {
            try { AppLanguage.valueOf(it) } catch (_: Exception) { null }
        } ?: if (Locale.getDefault().language == "ru") AppLanguage.RU else AppLanguage.EN
    )
    var selectedSources by mutableStateOf(
        prefs.getStringSet("selected_sources", emptySet()) ?: emptySet()
    )
    // Map of source URL to set of selected chunk indices (0, 1, 2...)
    var sourceSelectedChunks = mutableStateMapOf<String, Set<Int>>().apply {
        val json = prefs.getString("source_selected_chunks", "{}")
        try {
            val type = object : TypeToken<Map<String, Set<Int>>>() {}.type
            val map: Map<String, Set<Int>> = gson.fromJson(json, type)
            putAll(map)
        } catch (_: Exception) {}
    }

    fun toggleSourceChunk(url: String, chunk: Int) {
        val current = sourceSelectedChunks[url] ?: emptySet()
        val next = if (current.contains(chunk)) current - chunk else current + chunk
        
        if (next.isEmpty()) {
            sourceSelectedChunks.remove(url)
            selectedSources = selectedSources - url
        } else {
            sourceSelectedChunks[url] = next
            selectedSources = selectedSources + url
        }
        
        prefs.edit { 
            putStringSet("selected_sources", selectedSources)
            putString("source_selected_chunks", gson.toJson(sourceSelectedChunks.toMap()))
        }
    }

    var selectedProviderId by mutableStateOf(prefs.getString("selected_provider_id", "whoahaow") ?: "whoahaow")

    val configProviders = ConfigData.providers

    val currentProvider: ConfigProvider
        get() = configProviders.find { it.id == selectedProviderId } ?: configProviders.first()

    val providerUpdates = mutableStateMapOf<String, String>()
    var isRefreshingProvider by mutableStateOf(false)

    fun selectProvider(providerId: String) {
        selectedProviderId = providerId
        prefs.edit { putString("selected_provider_id", providerId) }
        fetchProviderUpdate(currentProvider)
    }

    private val providerSemaphore = Semaphore(3) // Лимит параллельных запросов к GitHub

    fun fetchProviderUpdate(provider: ConfigProvider, forceFileUpdate: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (forceFileUpdate) withContext(Dispatchers.Main) { isRefreshingProvider = true }
                LogManager.addLog("[GitHub] Syncing ${provider.name}...")
                
                // 1. Общий статус репозитория
                fetchRepoStatus(provider)

                if (!forceFileUpdate) {
                    loadProviderCache(provider)
                    return@launch
                }

                // 2. Оптимизация для Goida (чтение README)
                if (provider.id == "avencores") {
                    parseGoidaReadme(provider)
                }

                // 3. Обновление файлов с семафором
                val allSources = provider.categories.flatMap { it.items }
                val updatesToSave = ConcurrentHashMap<String, String>()
                
                allSources.map { source ->
                    async {
                        providerSemaphore.withPermit {
                            updateSourceInfo(provider, source, updatesToSave)
                        }
                    }
                }.awaitAll()

                saveProviderCache(provider, updatesToSave)
            } catch (e: Exception) {
                LogManager.addLog("[GitHub] Sync error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { isRefreshingProvider = false }
            }
        }
    }

    private suspend fun fetchRepoStatus(provider: ConfigProvider) {
        val url = "https://api.github.com/repos/${provider.owner}/${provider.repo}/commits?path=githubmirror&page=1&per_page=1"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONArray(response.body?.string() ?: "[]")
                    if (json.length() > 0) {
                        val dateStr = json.getJSONObject(0).getJSONObject("commit").getJSONObject("committer").getString("date")
                        val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        val date = sdfIn.parse(dateStr)
                        date?.let {
                            withContext(Dispatchers.Main) {
                                providerUpdates[provider.id] = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(it)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun updateSourceInfo(provider: ConfigProvider, source: ConfigSource, updatesToSave: MutableMap<String, String>) {
        try {
            val hasDate = providerUpdates.containsKey("${provider.id}_${source.url}_date")
            if (!hasDate) {
                val path = source.url.substringAfter("/main/")
                val fileUrl = "https://api.github.com/repos/${provider.owner}/${provider.repo}/commits?path=$path&page=1&per_page=1"
                client.newCall(Request.Builder().url(fileUrl).build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = org.json.JSONArray(resp.body?.string() ?: "[]")
                        if (json.length() > 0) {
                            val dateStr = json.getJSONObject(0).getJSONObject("commit").getJSONObject("committer").getString("date")
                            val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                            val date = sdfIn.parse(dateStr)
                            date?.let {
                                val d = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(it)
                                val t = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("GMT+3") }.format(it)
                                withContext(Dispatchers.Main) {
                                    providerUpdates["${provider.id}_${source.url}_date"] = d
                                    providerUpdates["${provider.id}_${source.url}_time"] = t
                                }
                                updatesToSave["${source.url}_date"] = d
                                updatesToSave["${source.url}_time"] = t
                            }
                        }
                    }
                }
            }

            // Server Count
            client.newCall(Request.Builder().url(source.url).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val count = resp.body?.string()?.lines()?.count { line ->
                        val t = line.trim()
                        t.startsWith("vless://") || t.startsWith("vmess://") || t.startsWith("trojan://") || t.startsWith("ss://")
                    } ?: 0
                    withContext(Dispatchers.Main) {
                        providerUpdates["${provider.id}_${source.url}_count"] = count.toString()
                    }
                    updatesToSave["${source.url}_count"] = count.toString()
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadProviderCache(provider: ConfigProvider) {
        val saved = prefs.getString("updates_cache_${provider.id}", null) ?: return
        try {
            val map: Map<String, String> = gson.fromJson(saved, object : TypeToken<Map<String, String>>() {}.type)
            providerUpdates.putAll(map.mapKeys { "${provider.id}_${it.key}" })
        } catch (_: Exception) {}
    }

    private fun saveProviderCache(provider: ConfigProvider, newUpdates: Map<String, String>) {
        val current = prefs.getString("updates_cache_${provider.id}", "{}")
        val map: MutableMap<String, String> = gson.fromJson(current, object : TypeToken<MutableMap<String, String>>() {}.type)
        map.putAll(newUpdates)
        prefs.edit { 
            putString("updates_cache_${provider.id}", gson.toJson(map))
            putLong("last_check_${provider.id}", System.currentTimeMillis())
        }
    }

    private suspend fun parseGoidaReadme(provider: ConfigProvider) {
        try {
            val url = "https://raw.githubusercontent.com/${provider.owner}/${provider.repo}/main/README.md"
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val content = resp.body?.string() ?: ""
                    val rowRegex = Regex("""\|\s*(\d+)\s*\|\s*\[?(\d+)\.txt]?.*\|\s*[^|]*\|\s*(\d{2}:\d{2}).*\|\s*(\d{2}\.\d{2}\.\d{4})\s*\|""")
                    val updates = mutableMapOf<String, String>()
                    rowRegex.findAll(content).forEach { m ->
                        val file = m.groupValues[2]
                        val fullUrl = "https://raw.githubusercontent.com/${provider.owner}/${provider.repo}/refs/heads/main/githubmirror/$file.txt"
                        updates["${provider.id}_${fullUrl}_time"] = m.groupValues[3]
                        updates["${provider.id}_${fullUrl}_date"] = m.groupValues[4].substringBeforeLast(".")
                    }
                    withContext(Dispatchers.Main) { providerUpdates.putAll(updates) }
                }
            }
        } catch (_: Exception) {}
    }

    var dnsServer by mutableStateOf(prefs.getString("dns_server", "8.8.8.8") ?: "8.8.8.8")
    var routingMode by mutableStateOf(
        prefs.getString("routing_mode", null)?.let {
            try { RoutingMode.valueOf(it) } catch (_: Exception) { null }
        } ?: RoutingMode.GLOBAL
    )
    var isSniffingEnabled by mutableStateOf(prefs.getBoolean("sniffing", false)) // Battery: default to OFF
    var isMuxEnabled by mutableStateOf(prefs.getBoolean("mux", false))
    var mtu by mutableIntStateOf(prefs.getInt("mtu", 1400)) // Battery: 1400 is safer for fragment reduction
    var concurrentChecks by mutableIntStateOf(prefs.getInt("concurrent_checks", 5))
    var byeDpiDns by mutableStateOf(prefs.getString("byedpi_dns", "8.8.8.8") ?: "8.8.8.8")

    fun updateByeDpiDns(value: String) {
        byeDpiDns = value
        prefs.edit { putString("byedpi_dns", value) }
        restartVpnIfConnected()
    }

    var isKillSwitchEnabled by mutableStateOf(prefs.getBoolean("kill_switch", false))
    var isIpv6Enabled by mutableStateOf(prefs.getBoolean("ipv6_enabled", false))
    var utlsFingerprint by mutableStateOf(prefs.getString("utls_fingerprint", "chrome") ?: "chrome")

    // Advanced Health Check Settings
    var delayTestUrl by mutableStateOf(prefs.getString("delay_test_url", "https://cp.cloudflare.com/") ?: "https://cp.cloudflare.com/")
    var speedTestUrl by mutableStateOf(prefs.getString("speed_test_url", "https://cachefly.cachefly.net/1mb.test") ?: "https://cachefly.cachefly.net/1mb.test")
    var pingTimeout by mutableLongStateOf(prefs.getLong("ping_timeout", 6000L))
    var speedTestTimeout by mutableLongStateOf(prefs.getLong("speed_test_timeout", 5000L))
    var isSpeedTestEnabled by mutableStateOf(prefs.getBoolean("speed_test_enabled", false))

    fun updateDelayTestUrl(value: String) { delayTestUrl = value; prefs.edit { putString("delay_test_url", value) } }
    fun updateSpeedTestUrl(value: String) { speedTestUrl = value; prefs.edit { putString("speed_test_url", value) } }
    fun updatePingTimeout(value: Long) { pingTimeout = value; prefs.edit { putLong("ping_timeout", value) } }
    fun updateSpeedTestTimeout(value: Long) { speedTestTimeout = value; prefs.edit { putLong("speed_test_timeout", value) } }
    fun updateIsSpeedTestEnabled(value: Boolean) { isSpeedTestEnabled = value; prefs.edit { putBoolean("speed_test_enabled", value) } }

    var isByeDpiEnabled by mutableStateOf(prefs.getBoolean("byedpi_enabled", false))
    var byeDpiArgs by mutableStateOf(prefs.getString("bye_dpi_args_v2", "-d 1 -a torst -r 1+s") ?: "-d 1 -a torst -r 1+s")
    var byeDpiListenAddress by mutableStateOf(prefs.getString("byedpi_listen_address", "127.0.0.1") ?: "127.0.0.1")
    var byeDpiListenPort by mutableIntStateOf(prefs.getInt("byedpi_listen_port", 10808))

    fun updateByeDpiListenAddress(value: String) {
        byeDpiListenAddress = value
        prefs.edit { putString("byedpi_listen_address", value) }
        restartVpnIfConnected()
    }

    fun updateByeDpiListenPort(value: Int) {
        byeDpiListenPort = value
        prefs.edit { putInt("byedpi_listen_port", value) }
        restartVpnIfConnected()
    }

    // Listen Settings
    var listenAddress by mutableStateOf(prefs.getString("listen_address", "127.0.0.1") ?: "127.0.0.1")
    var listenPort by mutableIntStateOf(prefs.getInt("listen_port", 10808))

    fun updateListenAddress(value: String) {
        listenAddress = value
        prefs.edit { putString("listen_address", value) }
        restartVpnIfConnected()
    }

    fun updateListenPort(value: Int) {
        listenPort = value
        prefs.edit { putInt("listen_port", value) }
        restartVpnIfConnected()
    }

    // Domain Checker
    var customCheckDns by mutableStateOf(prefs.getString("custom_check_dns", "8.8.8.8") ?: "8.8.8.8")
    var domainCheckResults = mutableStateListOf<DomainCheckResult>()
    var isCheckingDomains by mutableStateOf(false)

    fun updateCustomCheckDns(value: String) {
        customCheckDns = value
        prefs.edit { putString("custom_check_dns", value) }
    }

    // Dialog states
    var showResourceManagement by mutableStateOf(false)
    var showConfigSelector by mutableStateOf(false)
    var showOptimizerDialog by mutableStateOf(false)
    var showAppSelection by mutableStateOf(false)
    var showAddServerDialog by mutableStateOf(false)
    var editingServer by mutableStateOf<VpnServer?>(null)
    var showAdvancedSettings by mutableStateOf(false)

    // Optimizer
    var isOptimizing by mutableStateOf(false)
    var optimizationProgress by mutableFloatStateOf(0f)
    var strategies = mutableStateListOf<ByeDpiStrategy>()
    var favoriteStrategies by mutableStateOf(prefs.getStringSet("favorite_strategies", emptySet()) ?: emptySet())

    fun toggleFavoriteStrategy(args: String) {
        val newFavorites = if (favoriteStrategies.contains(args)) {
            favoriteStrategies - args
        } else {
            favoriteStrategies + args
        }
        favoriteStrategies = newFavorites
        prefs.edit { putStringSet("favorite_strategies", newFavorites) }
    }

    init {
        strategies.clear()
        ConfigData.byeDpiStrategies.forEachIndexed { i, args ->
            strategies.add(ByeDpiStrategy("Strategy ${i + 1}", args))
        }
        strategies.add(ByeDpiStrategy("Custom Current", byeDpiArgs))
    }

    fun runStrategyOptimizer() {
        if (isOptimizing) return
        
        viewModelScope.launch(Dispatchers.IO) {
            val wasConnected = isConnected
            val originalServer = connectedServer
            val originalArgs = byeDpiArgs
            
            if (wasConnected) {
                withContext(Dispatchers.Main) {
                    disconnect()
                    delay(1000)
                }
            }

            isOptimizing = true
            optimizationProgress = 0f
            
            val domainsToTest = listOf(
                "youtu.be", "youtube.com", "i.ytimg.com", "i9.ytimg.com", "yt3.ggpht.com",
                "yt4.ggpht.com", "googleapis.com", "jnn-pa.googleapis.com", "googleusercontent.com",
                "signaler-pa.youtube.com", "youtubei.googleapis.com", "manifest.googlevideo.com",
                "yt3.googleusercontent.com", "rr1---sn-4axm-n8vs.googlevideo.com",
                "rr1---sn-gvnuxaxjvh-o8ge.googlevideo.com", "rr1---sn-ug5onuxaxjvh-p3ul.googlevideo.com",
                "rr1---sn-ug5onuxaxjvh-n8v6.googlevideo.com", "rr4---sn-q4flrnsl.googlevideo.com",
                "rr10---sn-gvnuxaxjvh-304z.googlevideo.com", "rr14---sn-n8v7kn7r.googlevideo.com",
                "rr16---sn-axq7sn76.googlevideo.com", "rr1---sn-8ph2xajvh-5xge.googlevideo.com",
                "rr1---sn-gvnuxaxjvh-5gie.googlevideo.com", "rr12---sn-gvnuxaxjvh-bvwz.googlevideo.com",
                "rr5---sn-n8v7knez.googlevideo.com", "rr1---sn-u5uuxaxjvhg0-ocje.googlevideo.com",
                "rr2---sn-q4fl6ndl.googlevideo.com", "rr5---sn-gvnuxaxjvh-n8vk.googlevideo.com",
                "rr4---sn-jvhnu5g-c35d.googlevideo.com", "rr1---sn-q4fl6n6y.googlevideo.com",
                "rr2---sn-hgn7ynek.googlevideo.com", "rr1---sn-xguxaxjvh-gufl.googlevideo.com",
                "cloudflare.net", "cloudflare.com", "cloudflarecn.net", "cloudflare-ech.com"
            )

            // Reset strategies
            withContext(Dispatchers.Main) {
                strategies.indices.forEach { i ->
                    strategies[i] = strategies[i].copy(
                        successCount = 0, 
                        totalCount = 0, 
                        isTesting = false, 
                        isBest = false,
                        testProgress = 0f,
                        domainResults = domainsToTest.map { DomainCheckResult(it) }
                    )
                }
            }

            val originalOrder = strategies.map { it.args }
            
            originalOrder.forEachIndexed { sIndex, sArgs ->
                if (!isOptimizing) return@forEachIndexed
                
                // Show notification progress
                NotificationHelper.showProgressNotification(
                    getApplication(),
                    if (language == AppLanguage.RU) "Подбор стратегии" else "ByeDPI Optimization",
                    if (language == AppLanguage.RU) "Проверка ${sIndex + 1} из ${originalOrder.size}" else "Testing ${sIndex + 1} of ${originalOrder.size}",
                    sIndex + 1,
                    originalOrder.size,
                    NotificationHelper.NOTIFICATION_ID_BYEDPI
                )

                withContext(Dispatchers.Main) {
                    val currentIndex = strategies.indexOfFirst { it.args == sArgs }
                    if (currentIndex != -1) {
                        strategies[currentIndex] = strategies[currentIndex].copy(isTesting = true, testProgress = 0.01f)
                    }
                }

                ByeDPIController.stop()
                ByeDPIController.start(getApplication(), sArgs, byeDpiListenAddress, byeDpiListenPort)
                delay(800)

                val proxy = try {
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(byeDpiListenAddress, byeDpiListenPort))
                } catch (_: Exception) { null }

                if (proxy != null) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .proxy(proxy)
                        .sslSocketFactory(createUnsafeSslSocketFactory(), createUnsafeX509TrustManager())
                        .hostnameVerifier { _, _ -> true }
                        .build()

                    val semaphore = Semaphore(50)
                    val results = domainsToTest.mapIndexed { dIndex, domain ->
                        async {
                            semaphore.withPermit {
                                var success = false
                                var detail = "Failed"
                                try {
                                    val resolvedIp = java.net.InetAddress.getAllByName(domain).firstOrNull()?.hostAddress
                                    if (resolvedIp != null) {
                                        val request = Request.Builder()
                                            .url("https://$resolvedIp")
                                            .header("Host", domain)
                                            .header("User-Agent", "Mozilla/5.0")
                                            .build()
                                        
                                        val start = System.currentTimeMillis()
                                        client.newCall(request).execute().use { response ->
                                            val time = System.currentTimeMillis() - start
                                            if (response.code < 500) {
                                                success = true
                                                detail = "OK (${time}ms)"
                                            } else {
                                                detail = "HTTP ${response.code}"
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    detail = e.message ?: "Error"
                                }
                                
                                withContext(Dispatchers.Main) {
                                    val idx = strategies.indexOfFirst { it.args == sArgs }
                                    if (idx != -1) {
                                        val currentS = strategies[idx]
                                        val newResults = currentS.domainResults.toMutableList()
                                        newResults[dIndex] = DomainCheckResult(domain, if (success) "WORKING" else "FAILED", detail)
                                        val completed = newResults.count { it.status != "PENDING" }
                                        strategies[idx] = currentS.copy(
                                            domainResults = newResults,
                                            testProgress = completed.toFloat() / domainsToTest.size
                                        )
                                    }
                                }
                                success
                            }
                        }
                    }.awaitAll()

                    withContext(Dispatchers.Main) {
                        val idx = strategies.indexOfFirst { it.args == sArgs }
                        if (idx != -1) {
                            strategies[idx] = strategies[idx].copy(
                                successCount = results.count { it },
                                totalCount = domainsToTest.size,
                                isTesting = false,
                                testProgress = 1f
                            )
                        }
                        
                        val sorted = strategies.sortedWith(
                            compareByDescending<ByeDpiStrategy> { it.isTesting }
                                .thenByDescending { it.successCount }
                                .thenByDescending { it.totalCount > 0 }
                        )
                        strategies.clear()
                        strategies.addAll(sorted)
                        
                        // Early exit if we found a very good strategy
                        if (results.count { it } >= domainsToTest.size * 0.9) {
                            isOptimizing = false
                        }
                    }
                }
                
                optimizationProgress = (sIndex + 1).toFloat() / originalOrder.size
                delay(1000)
            }

            if (isOptimizing) {
                withContext(Dispatchers.Main) {
                    val best = strategies.filter { it.totalCount > 0 }.maxByOrNull { it.successCount }
                    if (best != null && best.successCount > 0) {
                        val bestIndex = strategies.indexOfFirst { it.args == best.args }
                        if (bestIndex != -1) strategies[bestIndex] = strategies[bestIndex].copy(isBest = true)
                    }
                    isOptimizing = false
                    ByeDPIController.stop()
                    
                    if (wasConnected) {
                        byeDpiArgs = originalArgs
                        originalServer?.let { startVpn(it) }
                    }
                    
                    NotificationHelper.dismissProgressNotification(getApplication(), NotificationHelper.NOTIFICATION_ID_BYEDPI)
                    showSnackbar(if (language == AppLanguage.RU) "Подбор завершен" else "Optimization finished")
                }
            }
        }
    }

    fun stopStrategyOptimizer() {
        isOptimizing = false
        NotificationHelper.dismissProgressNotification(getApplication(), NotificationHelper.NOTIFICATION_ID_BYEDPI)
        ByeDPIController.stop()
        LogManager.addLog("Optimizer: Stopped by user")
    }

    fun applyStrategy(strategy: ByeDpiStrategy) {
        byeDpiArgs = strategy.args
        prefs.edit { putString("bye_dpi_args_v2", strategy.args) }
        showSnackbar(if (language == AppLanguage.RU) "Стратегия применена" else "Strategy applied")
        restartVpnIfConnected()
    }


    fun runDomainChecks() {
        val domains = listOf(
            "rr1---sn-4axm-n8vs.googlevideo.com", "rr1---sn-gvnuxaxjvh-o8ge.googlevideo.com",
            "rr1---sn-ug5onuxaxjvh-p3ul.googlevideo.com", "rr1---sn-ug5onuxaxjvh-n8v6.googlevideo.com",
            "rr4---sn-q4flrnsl.googlevideo.com", "rr10---sn-gvnuxaxjvh-304z.googlevideo.com",
            "rr14---sn-n8v7kn7r.googlevideo.com", "rr16---sn-axq7sn76.googlevideo.com",
            "rr1---sn-8ph2xajvh-5xge.googlevideo.com", "rr1---sn-gvnuxaxjvh-5gie.googlevideo.com",
            "rr12---sn-gvnuxaxjvh-bvwz.googlevideo.com", "rr5---sn-n8v7knez.googlevideo.com",
            "rr1---sn-u5uuxaxjvhg0-ocje.googlevideo.com", "rr2---sn-q4fl6ndl.googlevideo.com",
            "rr5---sn-gvnuxaxjvh-n8vk.googlevideo.com", "rr4---sn-jvhnu5g-c35d.googlevideo.com",
            "rr1---sn-q4fl6n6y.googlevideo.com", "rr2---sn-hgn7ynek.googlevideo.com",
            "rr1---sn-xguxaxjvh-gufl.googlevideo.com", "youtu.be", "youtube.com", "i.ytimg.com",
            "i9.ytimg.com", "yt3.ggpht.com", "yt4.ggpht.com", "googleapis.com", "jnn-pa.googleapis.com",
            "googleusercontent.com", "signaler-pa.youtube.com", "youtubei.googleapis.com",
            "manifest.googlevideo.com", "yt3.googleusercontent.com", "cloudflare.net",
            "cloudflare.com", "cloudflarecn.net", "cloudflare-ech.com"
        )
        
        domainCheckResults.clear()
        domainCheckResults.addAll(domains.map { DomainCheckResult(it) })
        
        viewModelScope.launch(Dispatchers.IO) {
            isCheckingDomains = true
            
            // Запускаем временный движок, только если он не запущен основной службой
            val engineAlreadyRunning = ByeDPIController.isActive()
            if (!engineAlreadyRunning) {
                LogManager.addLog("Domain Check: Starting temporary ByeDPI instance...")
                ByeDPIController.start(getApplication(), byeDpiArgs, byeDpiListenAddress, byeDpiListenPort)
                delay(1500)
            }

            LogManager.addLog("Domain Check: Starting test...")
            
            val proxy = try {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(byeDpiListenAddress, byeDpiListenPort))
            } catch (e: Exception) { null }

            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                // Отключаем проверку SSL для теста доступности по IP
                .sslSocketFactory(createUnsafeSslSocketFactory(), createUnsafeX509TrustManager())
                .hostnameVerifier { _, _ -> true }
            
            if (proxy != null) {
                clientBuilder.proxy(proxy)
                LogManager.addLog("Domain Check: Routing through ByeDPI at $byeDpiListenAddress:$byeDpiListenPort")
            }

            val client = clientBuilder.build()
            val semaphore = Semaphore(5) // Ограничиваем нагрузку на прокси

            domains.mapIndexed { index, domain ->
                async {
                    semaphore.withPermit {
                        delay(index * 100L) // Разносим запросы во времени
                        try {
                            // 1. Умное разрешение DNS
                            val resolvedIp = withContext(Dispatchers.IO) {
                                // Сначала пробуем системный
                                val systemIp = try {
                                    java.net.InetAddress.getAllByName(domain).firstOrNull()?.hostAddress
                                } catch (_: Exception) { null }
                                
                                if (systemIp != null) return@withContext systemIp
                                
                                // Если системный подвел, используем каскад DoH (DNS-over-HTTPS)
                                val dohProviders = listOf(
                                    "https://dns.google/resolve?name=$domain&type=A",
                                    "https://cloudflare-dns.com/dns-query?name=$domain&type=A"
                                )
                                
                                for (url in dohProviders) {
                                    try {
                                        val dohRequest = Request.Builder()
                                            .url(url)
                                            .header("Accept", "application/dns-json")
                                            .build()
                                            
                                        NetworkClient.client.newCall(dohRequest).execute().use { response ->
                                            if (response.isSuccessful) {
                                                val body = response.body?.string() ?: ""
                                                val json = org.json.JSONObject(body)
                                                val answers = json.optJSONArray("Answer")
                                                if (answers != null && answers.length() > 0) {
                                                    // Берем первый подходящий IP
                                                    for (i in 0 until answers.length()) {
                                                        val ip = answers.getJSONObject(i).getString("data")
                                                        if (ip.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
                                                            return@withContext ip
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                                
                                null
                            }

                            if (resolvedIp == null) {
                                throw Exception("DNS lookup failed (System & DoH)")
                            }

                            // 2. Делаем запрос на IP, но с правильным Host и SNI
                            val request = Request.Builder()
                                .url("https://$resolvedIp") 
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                .header("Accept", "*/*")
                                .header("Host", domain) // Реальный хост для HTTP
                                .header("Connection", "close")
                                .tag(domain) // Сохраняем имя для SNI (если OkHttp это подхватит)
                                .build()

                            val start = System.currentTimeMillis()
                            // Примечание: Для полноценного SNI через IP в OkHttp обычно нужен кастомный DNS или SSLSocketFactory.
                            // Но для теста обхода DPI через SOCKS это должно сработать, так как ByeDPI увидит попытку соединения с IP.
                            
                            client.newCall(request).execute().use { response ->
                                val time = System.currentTimeMillis() - start
                                val code = response.code
                                val declaredSize = response.body?.contentLength() ?: -1L
                                
                                var actualSize = 0L
                                try {
                                    val source = response.body?.source()
                                    if (source != null) {
                                        val buffer = okio.Buffer()
                                        while (!source.exhausted()) {
                                            val read = source.read(buffer, 8192)
                                            if (read == -1L) break
                                            actualSize += read
                                            buffer.clear()
                                            if (actualSize > 256 * 1024) break 
                                        }
                                    }
                                } catch (_: Exception) {}

                                val status = if (code in 200..499 && actualSize > 0) "WORKING" else "FAILED"
                                
                                val info = "HTTP $code | Actual: $actualSize / Declared: ${if(declaredSize >= 0) declaredSize else "unknown"} bytes ($time ms)"
                                
                                LogManager.addLog("Domain Check: $domain -> $status ($info)")
                                
                                withContext(Dispatchers.Main) {
                                    domainCheckResults[index] = domainCheckResults[index].copy(
                                        status = status,
                                        detail = info
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            val errorMsg = e.message ?: "Error"
                            LogManager.addLog("Domain Check: $domain -> FAILED ($errorMsg)")
                            withContext(Dispatchers.Main) {
                                domainCheckResults[index] = domainCheckResults[index].copy(
                                    status = "FAILED",
                                    detail = errorMsg
                                )
                            }
                        }
                    }
                }
            }.awaitAll()

            // Закрываем соединения
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()

            if (!engineAlreadyRunning) {
                ByeDPIController.stop()
                LogManager.addLog("Domain Check: Temporary instance stopped")
            }

            LogManager.addLog("Domain Check: Finished")
            isCheckingDomains = false
        }
    }

    var hasSeenGuide by mutableStateOf(prefs.getBoolean("has_seen_guide", false))
    var showGuide by mutableStateOf(false)

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

    var pingMethod by mutableStateOf(
        prefs.getString("ping_method", null)?.let {
            try { PingMethod.valueOf(it) } catch (_: Exception) { null }
        } ?: PingMethod.TCP
    )
    var pingTargetUrl by mutableStateOf(prefs.getString("ping_target", "https://www.gstatic.com/generate_204") ?: "https://www.gstatic.com/generate_204")

    private val _servers = mutableStateListOf<VpnServer>()
    var servers: List<VpnServer> 
        get() = _servers
        set(value) {
            _servers.clear()
            _servers.addAll(value)
        }

    private val countryCache = ConcurrentHashMap<String, String>().apply {
        val saved = prefs.getString("country_cache_v2", null)
        if (saved != null) {
            try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val map: Map<String, String> = gson.fromJson(saved, type)
                putAll(map)
            } catch (_: Exception) {}
        }
    }

    private fun saveCountryCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val json = gson.toJson(countryCache.toMap())
            prefs.edit { putString("country_cache_v2", json) }
        }
    }
    private val geoSemaphore = Semaphore(10) // Ограничиваем только запросы к GeoIP API

    var isFetching by mutableStateOf(false)
    var isChecking by mutableStateOf(false)
    var isCheckingTelegram by mutableStateOf(false)

    var isCheckingUpdate by mutableStateOf(false)
    var updateInfo by mutableStateOf<VpnUpdateInfo?>(null)
    var isDownloadingUpdate by mutableStateOf(false)
    var isFakeDownloadMode by mutableStateOf(false)
    var downloadProgress by mutableFloatStateOf(0f)
    var downloadSpeed by mutableStateOf("")
    var downloadedSizeInfo by mutableStateOf("")

    val snackbarHostState = SnackbarHostState()

    fun showSnackbar(message: String) {
        if (isAutoSettingUp) return
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

    fun openTelegramProxy(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("tg://proxy?server=127.0.0.1&port=10808")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            showSnackbar(if (language == AppLanguage.RU) "Telegram не установлен" else "Telegram not found")
        }
    }

    fun cancelCheck() {
        checkJob?.cancel()
        checkJob = null
        autoSetupJob?.cancel()
        autoSetupJob = null
        isAutoSettingUp = false
        isChecking = false
        isCheckingTelegram = false
        NotificationHelper.dismissProgressNotification(getApplication())
    }

    private var trafficMonitorJob: Job? = null
    var isTrafficSilent by mutableStateOf(false)

    private fun startTrafficMonitoring() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            var lastRxBytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
            var silenceSeconds = 0
            
            while (isActive && isConnected) {
                delay(15000) // Increase interval for battery saving (was 5s)
                val currentRxBytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
                
                if (currentRxBytes > lastRxBytes) {
                    lastRxBytes = currentRxBytes
                    silenceSeconds = 0
                    if (isTrafficSilent) withContext(Dispatchers.Main) { isTrafficSilent = false }
                } else {
                    silenceSeconds += 15
                    if (silenceSeconds >= 45 && !isTrafficSilent) {
                        withContext(Dispatchers.Main) { 
                            isTrafficSilent = true 
                            if (appMode == AppMode.SIMPLE) {
                                showSnackbar(if (language == AppLanguage.RU) "Соединение неактивно. Попробуйте сменить сервер." else "Connection inactive. Try switching server.")
                            }
                        }
                    }
                }
            }
        }
    }

    var sortOrder by mutableStateOf(SortOrder.PING_SERVICE)
    
    private val defaultCheckTargets = listOf(
        CheckTarget("Telegram", "https://t.me/telegram", R.drawable.ic_telegram),
        CheckTarget("Instagram", "https://www.instagram.com", R.drawable.ic_instagram),
        CheckTarget("WhatsApp", "https://www.whatsapp.com"),
        CheckTarget("X (Twitter)", "https://x.com", R.drawable.ic_x),
        CheckTarget("Discord", "https://discord.com/app"),
        CheckTarget("TikTok", "https://www.tiktok.com"),
        CheckTarget("YouTube", "https://www.youtube.com", R.drawable.ic_youtube)
    )

    var checkTargets = mutableStateListOf<CheckTarget>().apply {
        val json = prefs.getString("check_targets_v2", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<CheckTarget>>() {}.type
                val saved: List<CheckTarget> = gson.fromJson(json, type)
                
                // Only update icons/urls for existing default targets, don't re-add deleted ones
                val updated = saved.map { target ->
                    val default = defaultCheckTargets.find { it.name == target.name }
                    if (default != null) {
                        target.copy(iconRes = default.iconRes, url = default.url)
                    } else target
                }
                addAll(updated)
            } catch (_: Exception) { 
                addAll(defaultCheckTargets) 
            }
        } else {
            // First run - use defaults
            addAll(defaultCheckTargets)
        }
    }

    var selectedCheckTarget by mutableStateOf(checkTargets.firstOrNull { it.isEnabled } ?: defaultCheckTargets.first())

    fun saveCheckTargets() {
        prefs.edit { putString("check_targets_v2", gson.toJson(checkTargets.toList())) }
    }

    fun toggleCheckTarget(target: CheckTarget) {
        val idx = checkTargets.indexOfFirst { it.name == target.name }
        if (idx == -1) return

        if (appMode == AppMode.SIMPLE) {
            // In Simple Mode, only ONE target can be enabled. 
            // Selecting an already enabled one does nothing (or we could allow deselecting but usually one must be on)
            checkTargets.forEachIndexed { i, t ->
                checkTargets[i] = t.copy(isEnabled = t.name == target.name)
            }
            mainTargetName = target.name
            additionalTargetNames.clear()
            prefs.edit { 
                putString("main_target_name", mainTargetName)
                putStringSet("additional_target_names", emptySet())
            }
        } else {
            // Advanced Mode: Normal toggle behavior
            val updated = checkTargets[idx].copy(isEnabled = !checkTargets[idx].isEnabled)
            checkTargets[idx] = updated
            
            if (updated.isEnabled) {
                if (mainTargetName.isEmpty()) mainTargetName = updated.name
                else if (!additionalTargetNames.contains(updated.name)) additionalTargetNames.add(updated.name)
            } else {
                if (mainTargetName == updated.name) mainTargetName = ""
                additionalTargetNames.remove(updated.name)
            }
            prefs.edit { 
                putString("main_target_name", mainTargetName)
                putStringSet("additional_target_names", additionalTargetNames.toSet())
            }
        }
        saveCheckTargets()
    }

    fun addCustomCheckTarget(name: String, url: String) {
        if (name.isEmpty() || url.isEmpty()) return
        if (checkTargets.any { it.name == name || it.url == url }) return
        val normalizedUrl = if (!url.startsWith("http")) "https://$url" else url
        checkTargets.add(CheckTarget(name, normalizedUrl, isCustom = true))
        saveCheckTargets()
    }

    fun removeCheckTarget(target: CheckTarget) {
        // Delete cached icon if exists
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val host = java.net.URL(target.url).host
                val faviconUrl = "https://www.google.com/s2/favicons?sz=64&domain=$host"
                val fileName = java.security.MessageDigest.getInstance("MD5")
                    .digest(faviconUrl.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val cacheFile = File(getApplication<android.app.Application>().cacheDir, "favicons/$fileName")
                if (cacheFile.exists()) cacheFile.delete()
            } catch (_: Exception) {}
        }

        checkTargets.removeIf { it.name == target.name }
        if (selectedCheckTarget.name == target.name) {
            selectedCheckTarget = checkTargets.firstOrNull { it.isEnabled } ?: defaultCheckTargets.first()
        }
        saveCheckTargets()
    }

    fun moveCheckTarget(fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until checkTargets.size && toIndex in 0 until checkTargets.size) {
            val item = checkTargets.removeAt(fromIndex)
            checkTargets.add(toIndex, item)
            saveCheckTargets()
        }
    }

    private var checkJob: Job? = null

    var isConnected by mutableStateOf(false)
    var connectedServer by mutableStateOf<VpnServer?>(null)
    var selectedServer by mutableStateOf(loadSelectedServer())
    var connectingServer by mutableStateOf<VpnServer?>(null)
    var vpnPermissionIntent by mutableStateOf<Intent?>(null)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LidoVpnService.ACTION_STATE -> {
                    isConnected = intent.getBooleanExtra(LidoVpnService.EXTRA_STATE, false)
                    if (!isConnected) {
                        connectedServer = null
                        trafficMonitorJob?.cancel()
                    } else {
                        startTrafficMonitoring()
                    }
                }
                "VPN_LOG" -> {
                    val message = intent.getStringExtra("MESSAGE") ?: ""
                    if (message.contains("geoip.dat", ignoreCase = true) || 
                        message.contains("geosite.dat", ignoreCase = true) ||
                        message.contains("failed to load geo", ignoreCase = true)) {
                        
                        viewModelScope.launch {
                            val msg = if (language == AppLanguage.RU)
                                "Ошибка загрузки гео-баз. Пытаемся исправить... Попробуйте подключиться позже."
                                else "Geo-data load error. Attempting to fix... Try connecting later."
                            showSnackbar(msg)
                            downloadGeoData()
                        }
                    }
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
        // Фоновая загрузка серверов для предотвращения ANR
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadServers() }
            _servers.addAll(loaded)
        }

        // Оптимизированная фильтрация в фоновом потоке
        snapshotFlow { Triple(servers.toList(), searchQuery, selectedServerTab) }
            .onEach { data ->
                val (allServers, query, tab) = data
                val filtered = withContext(Dispatchers.Default) {
                    val base = if (tab == 0) allServers.filter { !it.isManual } else allServers.filter { it.isManual }
                    if (query.isEmpty()) base
                    else {
                        base.filter { 
                            it.name.contains(query, ignoreCase = true) || 
                            it.host.contains(query, ignoreCase = true) ||
                            it.country.contains(query, ignoreCase = true)
                        }
                    }
                }
                _filteredServers.clear()
                _filteredServers.addAll(filtered)
            }
            .launchIn(viewModelScope)

        // Fetch updates for all providers (this will load from cache first)
        configProviders.forEach { provider ->
            // Load cache into memory immediately
            val savedUpdates = prefs.getString("updates_cache_${provider.id}", null)
            if (savedUpdates != null) {
                try {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    val map: Map<String, String> = gson.fromJson(savedUpdates, type)
                    map.forEach { (k, v) ->
                        providerUpdates["${provider.id}_$k"] = v
                    }
                } catch (_: Exception) {}
            }
            // Optimization: Skip API call on startup if we already have some data
            if (savedUpdates == null) {
                fetchProviderUpdate(provider)
            }
        }

        // Filter out orphaned sources (e.g. after URL updates)
        val allValidUrls = configProviders.flatMap { it.categories }.flatMap { it.items }.map { it.url }.toSet()
        if (selectedSources.any { it !in allValidUrls }) {
            val newSources = selectedSources.filter { it in allValidUrls }.toSet()
            selectedSources = newSources
            prefs.edit { putStringSet("selected_sources", newSources) }
        }

        val filter = IntentFilter(LidoVpnService.ACTION_STATE)
        ContextCompat.registerReceiver(application, stateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        
        // Ensure mainTargetName is valid
        if (checkTargets.isNotEmpty() && checkTargets.none { it.name == mainTargetName }) {
            mainTargetName = checkTargets.first().name
        }

        // State synchronization: Restore connected server after process death
        val savedServerJson = prefs.getString("connected_server", null)
        if (savedServerJson != null) {
            try {
                connectedServer = gson.fromJson(savedServerJson, VpnServer::class.java)
                isConnected = true
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<android.app.Application>().unregisterReceiver(stateReceiver)
        try { blockingExecutor.shutdownNow() } catch (_: Exception) {}
    }

    fun downloadGeoData() {
        viewModelScope.launch {
            isDownloadingGeo = true
            val success = GeoDataManager.downloadGeoFiles(getApplication()) { message ->
                LogManager.addLog(message)
            }
            refreshGeoInfo()
            isDownloadingGeo = false
            
            if (success) {
                showSnackbar(if (language == AppLanguage.RU) "Базы обновлены" else "Bases updated")
            } else {
                showSnackbar(if (language == AppLanguage.RU) "Ошибка загрузки баз" else "Failed to download bases")
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            isCheckingUpdate = true
            LogManager.addLog("[GitHub] Looking for app updates...")
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
                                LogManager.addLog("[GitHub] New version found: ${latest.version}")
                                withContext(Dispatchers.Main) {
                                    updateInfo = latest
                                }
                            } else {
                                LogManager.addLog("[GitHub] App is up to date ($current)")
                                withContext(Dispatchers.Main) {
                                    showSnackbar(if (language == AppLanguage.RU) "У вас установлена последняя версия" else "App is up to date")
                                }
                            }
                        } else {
                            LogManager.addLog("[GitHub] Update look failed: ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.addLog("[GitHub] Update error: ${e.message}")
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
            LogManager.addLog("[GitHub] Starting download: ${info.version} (FakeMode: $isFakeDownloadMode)")
            
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(info.downloadUrl).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
                        
                        val body = response.body ?: throw Exception("Empty body")
                        val totalBytes = body.contentLength()
                        val apkFile = File(getApplication<android.app.Application>().externalCacheDir, "update.apk")
                        var totalRead = 0L

                        body.byteStream().use { input ->
                            FileOutputStream(apkFile).use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
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
                        
                        LogManager.addLog("[GitHub] Download complete, size: ${formatSize(totalRead)}")
                        withContext(Dispatchers.Main) {
                            if (isFakeDownloadMode) {
                                apkFile.delete()
                                showSnackbar(if (language == AppLanguage.RU) "Фейк-загрузка окончена, файл удален" else "Fake download complete, file deleted")
                                isFakeDownloadMode = false
                                updateInfo = null
                            } else {
                                installApk(context, apkFile)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.addLog("[GitHub] Download error: ${e.message}")
                withContext(Dispatchers.Main) {
                    showSnackbar(if (language == AppLanguage.RU) "Ошибка обновления: ${e.message}" else "Update failed: ${e.message}")
                    isFakeDownloadMode = false
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

    fun formatSpeedValue(speed: Long): String {
        return when {
            speed >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB/s", speed.toFloat() / (1024 * 1024))
            speed >= 1024 -> String.format(Locale.getDefault(), "%.1f KB/s", speed.toFloat() / 1024)
            else -> "$speed B/s"
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

    fun triggerRealFakeUpdate() {
        viewModelScope.launch {
            isCheckingUpdate = true
            isFakeDownloadMode = true
            LogManager.addLog("[Debug] Triggering real file download test...")
            try {
                val request = Request.Builder()
                    .url("https://raw.githubusercontent.com/Apreverra/lidovpn/refs/heads/main/update.json")
                    .build()
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            val latest = gson.fromJson(body, VpnUpdateInfo::class.java)
                            withContext(Dispatchers.Main) {
                                updateInfo = latest
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                isFakeDownloadMode = false
            } finally {
                isCheckingUpdate = false
            }
        }
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
                delay(100)
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
            val typeToken = object : TypeToken<List<VpnServer>>() {}.type
            val list: List<VpnServer> = gson.fromJson(json, typeToken)
            list.map { server ->
                // Ensure transient maps are initialized as GSON can leave them null
                var s = if (server.servicePings == null) server.copy(servicePings = emptyMap()) else server
                if (s.serviceErrors == null) s = s.copy(serviceErrors = emptyMap())

                if (s.type.startsWith("SOCKS", ignoreCase = true)) {
                    val version = s.params["version"] ?: s.type.filter { it.isDigit() }.ifEmpty { "5" }
                    s.copy(
                        type = "SOCKS",
                        params = s.params.toMutableMap().apply { put("version", version) }.toMap()
                    )
                } else s
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveServers() {
        val serversToSave = _servers.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val json = gson.toJson(serversToSave)
            prefs.edit { 
                putString("saved_servers", json)
                putLong("last_servers_check_time", System.currentTimeMillis())
            }
            // Battery optimization: only schedule if absolutely necessary, 
            // and reduce frequency to once per 12 hours or even less.
            // scheduleBackgroundCheck() 
        }
    }

    private fun scheduleBackgroundCheck() {
        val workRequest = androidx.work.PeriodicWorkRequestBuilder<VpnBackgroundWorker>(
            6, java.util.concurrent.TimeUnit.HOURS
        ).build()
        
        androidx.work.WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
            "vpn_health_check",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun loadSelectedServer(): VpnServer? {
        val json = prefs.getString("selected_server", null) ?: return null
        return try {
            gson.fromJson(json, VpnServer::class.java)
        } catch (_: Exception) { null }
    }

    fun updateSelectedServer(server: VpnServer, restartVpn: Boolean = true) {
        selectedServer = server
        prefs.edit { putString("selected_server", gson.toJson(server)) }
        if (isConnected && restartVpn) {
            startVpn(server)
        }
    }

    fun deleteServer(server: VpnServer) {
        _servers.remove(server)
        if (selectedServer?.host == server.host && selectedServer?.port == server.port && selectedServer?.name == server.name) {
            selectedServer = null
            prefs.edit { remove("selected_server") }
        }
        saveServers()
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

    fun updateMtu(value: Int) {
        mtu = value
        prefs.edit { putInt("mtu", value) }
    }

    fun updateConcurrentChecks(value: Int) {
        concurrentChecks = value.coerceIn(1, 100)
        prefs.edit { putInt("concurrent_checks", concurrentChecks) }
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

    fun updateByeDpiEnabled(value: Boolean) {
        isByeDpiEnabled = value
        prefs.edit { putBoolean("byedpi_enabled", value) }
        restartVpnIfConnected()
    }

    fun updateByeDpiArgs(value: String) {
        byeDpiArgs = value
        prefs.edit { putString("bye_dpi_args_v2", value) }
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

    private val client = NetworkClient.client

    private val blockingExecutor = java.util.concurrent.Executors.newFixedThreadPool(64)

    private suspend fun resolveHostWithTimeout(host: String, timeoutMs: Long): String? {
        if (host.isEmpty()) return null
        if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) return host
        
        return withContext(Dispatchers.IO) {
            val future = blockingExecutor.submit(java.util.concurrent.Callable<String?> {
                try {
                    // Try getting address with a fresh resolver approach if possible, 
                    // or just rely on the timeout
                    java.net.InetAddress.getAllByName(host).firstOrNull()?.hostAddress
                } catch (_: Exception) {
                    null
                }
            })
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                future.cancel(true)
                null
            }
        }
    }

    private suspend fun fastTcpPing(hostOrIp: String, port: Int): Long? {
        return withContext(Dispatchers.IO) {
            val future = blockingExecutor.submit(java.util.concurrent.Callable<Long?> {
                val start = System.currentTimeMillis()
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(hostOrIp, port), 5000)
                        System.currentTimeMillis() - start
                    }
                } catch (_: Exception) {
                    null
                }
            })
            try {
                future.get(5500, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                future.cancel(true)
                null
            }
        }
    }

    private val testPortCounter = java.util.concurrent.atomic.AtomicInteger(20000)
    private val coreSemaphore = Semaphore(5) // Limit heavy core instances to 5 at once
    private val coreCallbackStub = CoreCallbackStub()

    private suspend fun runAccurateHttpCheck(server: VpnServer, ip: String, url: String, timeoutMs: Long): Pair<Long?, String> {
        return withContext(Dispatchers.IO) {
            coreSemaphore.withPermit {
                val port = testPortCounter.getAndIncrement().let { if (it > 20500) { testPortCounter.set(20000); 20000 } else it }
                val vpnConfig = XrayConfigGenerator.generateConfig(
                    server = server.copy(host = ip),
                    dns = dnsServer,
                    sniffing = isSniffingEnabled,
                    mux = isMuxEnabled,
                    routingMode = "ONLY_PROXY",
                    mtu = mtu,
                    assetPath = getApplication<android.app.Application>().filesDir.absolutePath,
                    utlsFingerprint = utlsFingerprint,
                    isTestConfig = true,
                    socksInboundPort = port
                )

                var core: libv2ray.CoreController? = null
                try {
                    val assetPath = getApplication<android.app.Application>().filesDir.absolutePath
                    System.setProperty("v2ray.location.asset", assetPath)
                    System.setProperty("xray.location.asset", assetPath)

                    core = Libv2ray.newCoreController(coreCallbackStub)
                    core.startLoop(vpnConfig, -1)
                    
                    // Wait a bit for the core to start the SOCKS server
                    var started = false
                    for (i in 1..20) {
                        try {
                            Socket().use { s -> 
                                s.connect(InetSocketAddress("127.0.0.1", port), 100)
                                started = true
                            }
                            if (started) break
                        } catch (_: Exception) { delay(100) }
                    }
                    
                    if (!started) return@withContext Pair(null, "Core Init Failed")

                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                    val client = OkHttpClient.Builder()
                        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .proxy(proxy)
                        // Removed createUnsafeSslSocketFactory (VPN-002)
                        .build()

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                        .build()

                    val startTime = System.currentTimeMillis()
                    val response = client.newCall(request).execute()
                    val delay = System.currentTimeMillis() - startTime
                    val code = response.code
                    response.close()
                    
                    if (code < 500) {
                        Pair(delay, "OK ($code)")
                    } else {
                        Pair(null, "HTTP $code")
                    }
                } catch (e: Exception) {
                    val errorMsg = when (e) {
                        is java.net.SocketTimeoutException -> "Timeout"
                        is java.net.ConnectException -> "Conn Refused"
                        is javax.net.ssl.SSLHandshakeException -> "SSL Error"
                        else -> e.message?.take(30) ?: "Error"
                    }
                    Pair(null, errorMsg)
                } finally {
                    try { core?.stopLoop() } catch (_: Exception) {}
                    yield()
                }
            }
        }
    }


    @androidx.annotation.Keep
    enum class RoutingMode(val label: String) {
        GLOBAL("Global"),
        BYPASS_LAN_RU("Bypass LAN & Russia"),
        ONLY_PROXY("Only Proxy")
    }

    @androidx.annotation.Keep
    enum class SortOrder {
        COUNTRY,
        PING_SERVICE,
        PING_GEN
    }

    @androidx.annotation.Keep
    enum class PingMethod(val label: String) {
        TCP("TCP Handshake (Proxy)"),
        HTTP("HTTP Request (Site)")
    }

    @androidx.annotation.Keep
    enum class SpeedTestMode(val label: String) {
        DOWNLOAD_ONLY("Скачивание"),
        DOWNLOAD_UPLOAD("Скачивание + загрузка")
    }


    fun toggleVpn(context: Context) {
        if (isConnected) {
            disconnect()
            return
        }

        // --- GEO DATA CHECK ---
        val needsGeoData = routingMode != RoutingMode.ONLY_PROXY
        geoFilesInfo = GeoDataManager.getGeoFilesInfo(getApplication()) // Force refresh file info
        
        val isGeoMissing = geoFilesInfo.any { !it.exists || it.size < 1024 } // Check if missing or corrupted
        
        if (needsGeoData && isGeoMissing) {
            val msg = if (language == AppLanguage.RU) 
                "Базы отсутствуют или повреждены. Загружаем..." 
                else "Bases missing or corrupted. Downloading..."
            showSnackbar(msg)
            downloadGeoData()
            return
        }

        // Если включен ТОЛЬКО ByeDPI и сервер не выбран (или мы в режиме простого моста)
        if (isByeDpiEnabled && appMode == AppMode.SIMPLE && selectedServer == null) {
            startVpn(VpnServer(name = "ByeDPI Local Bridge", type = "SOCKS", host = "127.0.0.1", port = byeDpiListenPort, uuid = "", isManual = true))
            return
        }

        val server = selectedServer ?: if (appMode == AppMode.SIMPLE) pickBestServer() else null
        if (server != null) {
            viewModelScope.launch {
                // Quick health check for Simple Mode
                if (appMode == AppMode.SIMPLE) {
                    autoSetupStatus = if (language == AppLanguage.RU) "Проверка..." else "Checking..."
                    isAutoSettingUp = true
                    
                    val isAlive = withContext(Dispatchers.IO) {
                        try {
                            val ip = resolveHostWithTimeout(server.host, 3000) ?: return@withContext false
                            fastTcpPing(ip, server.port) != null
                        } catch (_: Exception) { false }
                    }

                    if (!isAlive) {
                        LogManager.addLog("Selected server is dead, starting auto-setup")
                        runSimpleAutoSetup()
                        return@launch
                    }
                    isAutoSettingUp = false
                }
                connect(server, context)
            }
        } else {
            if (appMode == AppMode.SIMPLE) {
                runSimpleAutoSetup()
            } else {
                showSnackbar(if (language == AppLanguage.RU) "Пожалуйста, сначала выберите сервер" else "Please select a server first")
            }
        }
    }

    private fun pickBestServer(): VpnServer? {
        return servers.firstOrNull { it.status == ServerStatus.WORKING }
    }

    private fun connect(server: VpnServer, context: Context) {
        val intent = android.net.VpnService.prepare(context)
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
            putExtra("MTU", mtu)
            putExtra("ROUTING_MODE", routingMode.name)
            putExtra("KILL_SWITCH", isKillSwitchEnabled)
            putExtra("IPV6_ENABLED", isIpv6Enabled)
            putExtra("UTLS_FINGERPRINT", utlsFingerprint)
            
            putExtra("BYEDPI_ENABLED", isByeDpiEnabled)
            putExtra("BYEDPI_ARGS", byeDpiArgs)
            putExtra("BYEDPI_LISTEN_ADDRESS", byeDpiListenAddress)
            putExtra("BYEDPI_LISTEN_PORT", byeDpiListenPort)
            putExtra("BYEDPI_DNS", byeDpiDns)
            putExtra("LISTEN_PORT", listenPort)
            
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
        if (selectedSources.contains(url)) {
            selectedSources = selectedSources - url
            sourceSelectedChunks.remove(url)
        } else {
            selectedSources = selectedSources + url
            // By default, if it's a large source, we might want to select at least part 1
            // But for simple sources (count < 5000), part selection is implicit
            val countStr = providerUpdates["${selectedProviderId}_${url}_count"]
            val count = countStr?.toIntOrNull() ?: 0
            if (count > 5000) {
                sourceSelectedChunks[url] = setOf(0)
            }
        }
        prefs.edit { 
            putStringSet("selected_sources", selectedSources)
            putString("source_selected_chunks", gson.toJson(sourceSelectedChunks.toMap()))
        }
    }

    fun fetchServers() {
        if (selectedSources.isEmpty()) return
        
        viewModelScope.launch {
            isFetching = true
            LogManager.addLog("[GitHub] Fetching servers from ${selectedSources.size} sources...")
            val manualServers = servers.filter { it.isManual }
            val urls = selectedSources.toList()
            
            val newServers = ConcurrentHashMap.newKeySet<VpnServer>()
            val semaphore = Semaphore(5)

            withContext(Dispatchers.IO) {
                urls.map { url ->
                    async {
                        semaphore.withPermit {
                            try {
                                val fileName = url.substringAfterLast("/")
                                LogManager.addLog("[GitHub] Loading: $fileName")
                                
                                val selectedChunks = sourceSelectedChunks[url] ?: setOf(0)
                                val request = Request.Builder().url(url).build()
                                
                                client.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        var currentValid = 0
                                        response.body?.source()?.inputStream()?.bufferedReader()?.use { reader ->
                                            reader.forEachLine { line ->
                                                val trimmed = line.trim()
                                                if (trimmed.startsWith("vless://") || trimmed.startsWith("vmess://") || trimmed.startsWith("trojan://") || trimmed.startsWith("ss://")) {
                                                    currentValid++
                                                    val chunkIndex = (currentValid - 1) / 5000
                                                    if (selectedChunks.contains(chunkIndex)) {
                                                        if (newServers.size < 30000) {
                                                            parseProxyUrl(trimmed)?.let { newServers.add(it) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        LogManager.addLog("[GitHub] Failed to load $fileName: ${response.code}")
                                    }
                                }
                            } catch (e: Exception) {
                                LogManager.addLog("[GitHub] Error loading ${url.substringAfterLast("/")}: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }

            val addedCount = newServers.size
            LogManager.addLog("[GitHub] Total servers parsed: $addedCount")
            servers = (manualServers + newServers).toList()
            saveServers()
            isFetching = false

            showSnackbar(
                if (language == AppLanguage.RU) "Загружено серверов: $addedCount"
                else "Loaded $addedCount servers"
            )
        }
    }

    fun addManualServer(server: VpnServer) {
        val newList = servers.toMutableList()
        // Check if we are editing an existing server
        val existingIndex = newList.indexOfFirst { 
            it.isManual && it.host == server.host && it.port == server.port && it.name == server.name 
        }
        
        if (existingIndex != -1) {
            newList[existingIndex] = server.copy(isManual = true)
        } else {
            newList.add(0, server.copy(isManual = true))
        }
        
        servers = newList
        saveServers()
        showSnackbar(if (language == AppLanguage.RU) "Сервер сохранен" else "Server saved")
    }

    private fun parseProxyUrl(url: String, isManual: Boolean = false): VpnServer? {
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
                params["scy"] = json.optString("scy", "auto")
                params["aid"] = json.optString("aid", "0")
                params["net"] = json.optString("net", "tcp")

                val serverName = json.optString("ps", "Unnamed VMess")
                return VpnServer(
                    name = serverName,
                    type = "VMESS",
                    host = json.optString("add", ""),
                    port = json.optInt("port", 443),
                    uuid = json.optString("id", ""),
                    params = params,
                    rawUrl = url,
                    country = extractClaimedCountry(serverName)?.let { "? $it" } ?: "",
                    isManual = isManual
                )
            }

            val uri = url.toUri()
            var type = uri.scheme ?: return null
            val originalType = type.lowercase()
            if (originalType == "socks5" || originalType == "socks4") type = "socks"
            if (originalType == "hy2") type = "hysteria2"
            
            val userInfo = uri.userInfo ?: ""
            var decodedUserInfo = userInfo
            if ((type.lowercase() == "ss" || type.lowercase() == "hysteria2" || type.lowercase() == "tuic") && userInfo.isNotEmpty() && !userInfo.contains(":")) {
                try {
                    decodedUserInfo = android.util.Base64.decode(userInfo, android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE).toString(Charsets.UTF_8)
                } catch (_: Exception) {}
            }

            val host = uri.host ?: ""
            val port = if (uri.port != -1) uri.port else when(type.lowercase()) {
                "vless", "vmess", "trojan", "hysteria2", "tuic" -> 443
                "ss" -> 8388
                "socks" -> 1080
                else -> 80
            }
            val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Unnamed Server"

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { pair ->
                val parts = pair.split("=")
                if (parts.size == 2) params[parts[0]] = URLDecoder.decode(parts[1], "UTF-8")
            }
            
            if (originalType == "socks5") params["version"] = "5"
            if (originalType == "socks4") params["version"] = "4"
            
            // Fix: Standard SOCKS parsing with credentials
            val finalUuid = if ((type.lowercase() == "socks" || type.lowercase() == "tuic") && userInfo.isNotEmpty()) {
                if (type.lowercase() == "tuic" && userInfo.contains(":")) {
                    val parts = userInfo.split(":")
                    params["pass"] = parts[1]
                    parts[0]
                } else userInfo
            } else {
                decodedUserInfo
            }

            VpnServer(
                name = name,
                type = type.uppercase(),
                host = host,
                port = port,
                uuid = finalUuid,
                params = params,
                rawUrl = url,
                country = extractClaimedCountry(name)?.let { "? $it" } ?: "",
                isManual = isManual
            )
        } catch (_: Exception) {
            null
        }
    }
    
    fun importFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val server = parseProxyUrl(text, isManual = true)
        if (server != null) {
            addManualServer(server)
        } else {
            showSnackbar(if (language == AppLanguage.RU) "Некорректная ссылка" else "Invalid URL")
        }
    }

    private suspend fun fetchCountry(server: VpnServer): String {
        val unknownText = if (language == AppLanguage.RU) "Неизвестная страна" else "unknown country"
        val isRu = language == AppLanguage.RU
        return withContext(Dispatchers.IO) {
            val resolvedIp = try {
                java.net.InetAddress.getByName(server.host).hostAddress ?: server.host
            } catch (_: Exception) { server.host }

            // 1. Try GeoIP API first (Priority)
            val geo = tryGetGeoInfo(resolvedIp)
            if (geo != null) {
                val code = geo.first
                val flag = codeToFlag(code)
                val name = getCountryName(code, isRu)
                val result = if (name.isNotEmpty()) "$flag $name" else flag
                LogManager.addLog("Geo: API Found ${server.host} -> $result")
                return@withContext result
            }

            // 2. Fallback to name extraction (Last resort)
            val claimed = extractClaimedCountry(server.name)
            if (claimed != null) {
                val code = extractFlag(claimed)?.let { flagToCode(it) }
                val name = if (code != null) getCountryName(code, isRu) else ""
                val result = if (name.isNotEmpty()) "${extractFlag(claimed)} $name" else claimed
                LogManager.addLog("Geo: Claimed ${server.host} -> $result")
                return@withContext result
            }

            LogManager.addLog("Geo: Failed to find info for ${server.host}")
            "🏴‍☠️ $unknownText"
        }
    }

    private val countryNamesMap = mapOf(
        "USA" to "US", "UNITED STATES" to "US",
        "RUSSIA" to "RU", "RUS" to "RU",
        "GERMANY" to "DE", "GER" to "DE",
        "NETHERLANDS" to "NL", "NED" to "NL",
        "SINGAPORE" to "SG", "SGP" to "SG",
        "JAPAN" to "JP", "JPN" to "JP",
        "FRANCE" to "FR", "FRA" to "FR",
        "UNITED KINGDOM" to "GB", "UK" to "GB",
        "FINLAND" to "FI", "TURKEY" to "TR",
        "POLAND" to "PL", "SWEDEN" to "SE"
    )

    private fun extractClaimedCountry(name: String): String? {
        val upperName = name.uppercase()
        
        // 1. Поиск флага (Emoji)
        val flagRegex = Regex("[\uD83C\uDDE6-\uD83C\uDDFF]{2}")
        val flagMatch = flagRegex.find(name)
        if (flagMatch != null) return flagMatch.value

        // 2. Поиск кодов типа [US], (DE), |RU|, RU-, US_
        val codeRegex = Regex("(?:^|[\\[(|\\s])([A-Z]{2})(?:[\\])|\\s_-]|$)")
        val codeMatch = codeRegex.find(upperName)
        if (codeMatch != null) {
            val code = codeMatch.groupValues[1]
            if (code.length == 2) return "${codeToFlag(code)} $code"
        }

        // 3. Поиск упоминаний названий
        for ((cName, code) in countryNamesMap) {
            if (upperName.contains(cName)) {
                return "${codeToFlag(code)} $code"
            }
        }
        
        return null
    }

    private fun tryGetGeoInfo(host: String): Pair<String, String>? {
        val hostPart = if (host.isEmpty()) "" else "/$host"
        val endpoints = listOf(
            "https://ipwho.is$hostPart" to ("country_code" to "country"),
            "https://freeipapi.com/api/json$hostPart" to ("countryCode" to "countryName"),
            "https://ip-api.com/json$hostPart" to ("countryCode" to "country"),
            "https://ipapi.co${if (host.isEmpty()) "/json/" else "/$host/json/"}" to ("country_code" to "country_name"),
            "https://api.country.is$hostPart" to ("country" to "")
        )

        for ((url, keys) in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    
                    val body = response.body?.string() ?: ""
                    if (body.trim().startsWith("{")) {
                        val json = org.json.JSONObject(body)
                        val code = json.optString(keys.first)
                        val name = if (keys.second.isNotEmpty()) json.optString(keys.second) else ""
                        if (code.isNotEmpty()) return Pair(code, name)
                    }
                }
            } catch (_: Exception) {
                // Silently try next provider
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
        val index = _servers.indexOfFirst { 
            it.host == server.host && it.port == server.port && (it.name == server.name || it.uuid == server.uuid)
        }
        if (index != -1) {
            _servers[index] = transform(_servers[index])
        }
    }

    private fun updateServerInListOptimized(server: VpnServer, indexMap: Map<String, Int>, transform: (VpnServer) -> VpnServer) {
        val key = "${server.host}:${server.port}:${server.name}"
        val index = indexMap[key] ?: -1
        var updated = false
        if (index != -1 && index < _servers.size) {
            val current = _servers[index]
            if (current.host == server.host && current.port == server.port) {
                _servers[index] = transform(current)
                updated = true
            }
        }
        
        if (!updated) {
            updateServerInList(server, transform)
        }
    }

    private suspend fun fetchCountryWithCache(server: VpnServer): String {
        val host = server.host
        if (host.isEmpty()) return ""
        
        countryCache[host]?.let { return it }
        
        return withContext(Dispatchers.IO) {
            geoSemaphore.withPermit {
                countryCache[host]?.let { return@withPermit it }
                
                val country = fetchCountry(server)
                if (country.isNotEmpty() && !country.contains("unknown", ignoreCase = true)) {
                    countryCache[host] = country
                    saveCountryCache()
                }
                country
            }
        }
    }

    fun checkAllServers() {
        val filteredList = if (selectedServerTab == 0) {
            servers.filter { !it.isManual }
        } else {
            servers.filter { it.isManual }
        }
        
        if (filteredList.isEmpty()) return
        cancelCheck()
        isChecking = true
        
        checkJob = viewModelScope.launch(Dispatchers.Default) {
            // Reset statuses in background thread
            val resetServers = servers.map { server ->
                val isRelevant = if (selectedServerTab == 0) !server.isManual else server.isManual
                if (isRelevant) {
                    server.copy(status = ServerStatus.UNKNOWN, ping = null, servicePings = emptyMap(), serviceErrors = emptyMap())
                } else server
            }
            withContext(Dispatchers.Main) { servers = resetServers }
            
            val snapshot = filteredList.toList()
            LogManager.addLog(if (language == AppLanguage.RU) "Запуск полной проверки серверов (${snapshot.size})..." else "Starting full server health check (${snapshot.size})...")
            
            val checkedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val totalToCheck = snapshot.size
            var lastUpdateMillis = 0L

            val supervisor = SupervisorJob(coroutineContext[Job])
            val channel = Channel<VpnServer>(Channel.RENDEZVOUS)
            
            // Producer
            launch(Dispatchers.Default + supervisor) {
                snapshot.forEach { if(isActive) channel.send(it) }
                channel.close()
            }

            try {
                // Workers
                val workers = List(concurrentChecks) {
                    launch(Dispatchers.IO + supervisor) {
                        for (server in channel) {
                            if (!isActive) break
                            var pingResult: Long? = null
                            var success = false
                            
                            try {
                                withTimeoutOrNull(pingTimeout + 5000) {
                                    val ip = resolveHostWithTimeout(server.host, 4000)
                                    if (ip == null) return@withTimeoutOrNull

                                    repeat(3) { attempt ->
                                        if (success || !isActive) return@repeat
                                        if (pingMethod == PingMethod.TCP) {
                                            pingResult = fastTcpPing(ip, server.port)
                                            success = pingResult != null
                                        } else {
                                            val (delay, _) = runAccurateHttpCheck(server, ip, delayTestUrl, pingTimeout)
                                            if (delay != null) {
                                                pingResult = delay
                                                success = true
                                            }
                                        }
                                        if (!success && attempt < 2 && isActive) delay(if (attempt == 0) 500 else 1000)
                                    }
                                }

                                if (!isActive) break

                                withContext(Dispatchers.Main) {
                                    val currentChecked = checkedCount.incrementAndGet()
                                    if (success) {
                                        LogManager.addLog("Check: ${server.name} -> ${pingResult}ms")
                                        updateServerInList(server) {
                                            it.copy(status = ServerStatus.WORKING, ping = pingResult)
                                        }
                                        launch(Dispatchers.IO) {
                                            val country = fetchCountryWithCache(server)
                                            if (country.isNotEmpty()) {
                                                withContext(Dispatchers.Main) {
                                                    updateServerInList(server) { it.copy(country = country) }
                                                }
                                            }
                                        }
                                    } else {
                                        LogManager.addLog("Check: ${server.name} -> FAILED")
                                        updateServerInList(server) { 
                                            it.copy(status = ServerStatus.NOT_WORKING, ping = null) 
                                        }
                                    }

                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdateMillis > 1500 || currentChecked == totalToCheck) {
                                        lastUpdateMillis = now
                                        val actualWorkingCount = _servers.count { 
                                            it.status == ServerStatus.WORKING && (if (selectedServerTab == 0) !it.isManual else it.isManual)
                                        }
                                        NotificationHelper.showProgressNotification(
                                            getApplication(),
                                            if (language == AppLanguage.RU) "Проверка серверов" else "Server Health Check",
                                            if (language == AppLanguage.RU) "Обработка: ${server.name}" else "Checking: ${server.name}",
                                            currentChecked,
                                            totalToCheck,
                                            NotificationHelper.NOTIFICATION_ID_SERVER_CHECK,
                                            subText = if (language == AppLanguage.RU) "Рабочих: $actualWorkingCount" else "Working: $actualWorkingCount"
                                        )
                                    }
                                }
                            } catch (_: Exception) {
                                checkedCount.incrementAndGet()
                            }
                        }
                    }
                }
                workers.joinAll()
            } catch (e: Exception) {
                LogManager.addLog("Check error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    servers = servers.map { if (it.status == ServerStatus.UNKNOWN) it.copy(status = ServerStatus.NOT_WORKING) else it }
                    applySort()
                    saveServers()
                    isChecking = false
                    NotificationHelper.dismissProgressNotification(getApplication())
                }
            }
        }
    }

    private suspend fun measureSpeedWithV2Ray(config: String, url: String, timeoutMs: Long): Long? {
        return withContext(Dispatchers.IO) {
            try {
                null // Placeholder for real speed measurement
            } catch (_: Exception) { null }
        }
    }

    fun stopCheck() {
        cancelCheck()
        LogManager.addLog(if (language == AppLanguage.RU) "Проверка остановлена пользователем" else "Check stopped by user")
    }

    fun checkAllServices() {
        val targets = checkTargets.filter { it.isEnabled }
        if (targets.isEmpty()) return
        performBatchServiceCheck(targets)
    }

    fun checkAllService(target: CheckTarget) {
        performBatchServiceCheck(listOf(target))
    }

    private fun performBatchServiceCheck(targets: List<CheckTarget>) {
        val workingServers = if (selectedServerTab == 0) {
            servers.filter { it.status == ServerStatus.WORKING && !it.isManual }
        } else {
            servers.filter { it.status == ServerStatus.WORKING && it.isManual }
        }
        if (workingServers.isEmpty()) return
        
        cancelCheck()
        isCheckingTelegram = true
        
        checkJob = viewModelScope.launch {
            // Optimization: index map for O(1) updates
            val indexMap = servers.withIndex().associateBy({ "${it.value.host}:${it.value.port}:${it.value.name}" }, { it.index })
            
            val checkedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val totalToCheck = workingServers.size
            var lastUpdateMillis = 0L

            // Reset pings for the relevant targets
            withContext(Dispatchers.Main) {
                servers = servers.map { server ->
                    val isRelevant = if (selectedServerTab == 0) !server.isManual else server.isManual
                    if (isRelevant && server.status == ServerStatus.WORKING) {
                        val newPings = (server.servicePings ?: emptyMap()).toMutableMap()
                        val newErrors = (server.serviceErrors ?: emptyMap()).toMutableMap()
                        targets.forEach { target ->
                            newPings[target.name] = null
                            newErrors[target.name] = null
                        }
                        server.copy(servicePings = newPings, serviceErrors = newErrors)
                    } else server
                }
            }

            val supervisor = SupervisorJob(coroutineContext[Job])
            val channel = Channel<VpnServer>(Channel.RENDEZVOUS)
            LogManager.addLog(if (language == AppLanguage.RU) "Запуск проверки сервисов (${targets.size} целей)..." else "Starting service check (${targets.size} targets)...")
            
            launch(Dispatchers.Default + supervisor) {
                workingServers.forEach { channel.send(it) }
                channel.close()
            }

            try {
                val workers = List(concurrentChecks) {
                    launch(Dispatchers.IO + supervisor) {
                        for (server in channel) {
                            if (!isActive) break
                            
                            val vpnConfig = try {
                                XrayConfigGenerator.generateConfig(
                                    server = server,
                                    dns = dnsServer,
                                    sniffing = isSniffingEnabled,
                                    mux = isMuxEnabled,
                                    routingMode = "ONLY_PROXY",
                                    mtu = mtu,
                                    assetPath = getApplication<android.app.Application>().filesDir.absolutePath,
                                    utlsFingerprint = utlsFingerprint,
                                    isTestConfig = true
                                )
                            } catch (_: Exception) { null } ?: continue

                            targets.forEach { target ->
                                if (!isActive) return@forEach
                                
                                var resultPing: Long? = null
                                var errorReason: String? = null
                                
                                try {
                                    val timedOut = withTimeoutOrNull(10000L) {
                                        val startTime = System.currentTimeMillis()
                                        val delay = Libv2ray.measureOutboundDelay(vpnConfig, target.url)
                                        val duration = System.currentTimeMillis() - startTime

                                        if (delay > 0) {
                                            resultPing = duration
                                        } else {
                                            errorReason = "Failed"
                                        }
                                        true
                                    } == null
                                    if (timedOut) errorReason = "Timeout"
                                } catch (e: Exception) {
                                    errorReason = e.message ?: "Error"
                                }

                                withContext(Dispatchers.Main) {
                                    val statusText = if (resultPing != null) "${resultPing}ms" else "FAILED ($errorReason)"
                                    LogManager.addLog("Service Check: ${server.name} [${target.name}] -> $statusText")

                                    updateServerInListOptimized(server, indexMap) { s ->
                                        val pings = (s.servicePings ?: emptyMap()).toMutableMap()
                                        val errors = (s.serviceErrors ?: emptyMap()).toMutableMap()
                                        pings[target.name] = resultPing
                                        errors[target.name] = errorReason
                                        
                                        // Fix: If a service check succeeds, the server IS working
                                        val newStatus = if (resultPing != null) ServerStatus.WORKING else s.status
                                        
                                        s.copy(
                                            status = newStatus,
                                            servicePings = pings, 
                                            serviceErrors = errors
                                        )
                                    }

                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdateMillis > 500) {
                                        lastUpdateMillis = now
                                        applySort()
                                        
                                        val currentCheckedCount = checkedCount.get()
                                        val workingCount = workingServers.count { s ->
                                            val currentS = _servers.find { it.host == s.host && it.port == s.port }
                                            (currentS?.servicePings?.get(target.name) ?: 0L) > 0
                                        }

                                        NotificationHelper.showProgressNotification(
                                            getApplication(),
                                            if (language == AppLanguage.RU) "Проверка: ${target.name}" else "Checking: ${target.name}",
                                            if (language == AppLanguage.RU) "Сервер: ${server.name}" else "Node: ${server.name}",
                                            currentCheckedCount,
                                            totalToCheck,
                                            NotificationHelper.NOTIFICATION_ID_SERVICE_CHECK,
                                            largeIconRes = target.iconRes,
                                            subText = if (language == AppLanguage.RU) "Доступно: $workingCount" else "Online: $workingCount"
                                        )
                                    }
                                }
                            }
                            
                            checkedCount.incrementAndGet()
                            // Final sort at the end of each server if needed
                            withContext(Dispatchers.Main) {
                                if (checkedCount.get() == totalToCheck) {
                                    applySort()
                                }
                            }
                        }
                    }
                }
                withTimeoutOrNull(300000) {
                    workers.joinAll()
                }
            } finally {
                isCheckingTelegram = false
                NotificationHelper.dismissProgressNotification(getApplication(), NotificationHelper.NOTIFICATION_ID_SERVICE_CHECK)
                LogManager.addLog(if (language == AppLanguage.RU) "Проверка сервисов завершена" else "Service check finished")
                saveServers()
            }
        }
    }

    private fun getSortedList(list: List<VpnServer>): List<VpnServer> {
        val targetToUse = if (appMode == AppMode.SIMPLE) mainTargetName else checkTargets.firstOrNull { it.isEnabled }?.name ?: ""
        
        return list.sortedWith(
            compareBy<VpnServer> { it.status != ServerStatus.WORKING }
                .thenBy { it.status == ServerStatus.UNKNOWN }
                .thenBy {
                    if (appMode == AppMode.SIMPLE) {
                        val hasPing = (it.servicePings ?: emptyMap())[targetToUse] != null
                        val error = (it.serviceErrors ?: emptyMap())[targetToUse] ?: ""
                        val isSuccessError = error.contains("403") || error.contains("401") || error.contains("400")
                        
                        // If we are in the middle of checking services, don't drop working servers to the bottom
                        if (isCheckingTelegram && it.status == ServerStatus.WORKING && !hasPing && !isSuccessError) {
                            false // Still treat as "has ping" for sorting stability
                        } else {
                            !(hasPing || isSuccessError)
                        }
                    } else {
                        when (sortOrder) {
                            SortOrder.COUNTRY -> it.country.isEmpty()
                            SortOrder.PING_SERVICE -> {
                                val hasPing = (it.servicePings ?: emptyMap())[targetToUse] != null
                                val error = (it.serviceErrors ?: emptyMap())[targetToUse] ?: ""
                                val isSuccessError = error.contains("403") || error.contains("401") || error.contains("400")
                                
                                if (isCheckingTelegram && it.status == ServerStatus.WORKING && !hasPing && !isSuccessError) {
                                    false
                                } else {
                                    !(hasPing || isSuccessError)
                                }
                            }
                            SortOrder.PING_GEN -> it.ping == null
                        }
                    }
                }
                .thenBy {
                    if (appMode == AppMode.SIMPLE) {
                        val ping = (it.servicePings ?: emptyMap())[targetToUse]
                        if (ping != null) ping 
                        else {
                            val error = (it.serviceErrors ?: emptyMap())[targetToUse] ?: ""
                            if (error.contains("403") || error.contains("401") || error.contains("400")) 0L 
                            else Long.MAX_VALUE
                        }
                    } else {
                        when (sortOrder) {
                            SortOrder.COUNTRY -> it.country
                            SortOrder.PING_SERVICE -> {
                                val ping = (it.servicePings ?: emptyMap())[targetToUse]
                                if (ping != null) ping 
                                else {
                                    val error = (it.serviceErrors ?: emptyMap())[targetToUse] ?: ""
                                    if (error.contains("403") || error.contains("401") || error.contains("400")) 0L 
                                    else Long.MAX_VALUE
                                }
                            }
                            SortOrder.PING_GEN -> it.ping ?: Long.MAX_VALUE
                        }
                    }
                }
        )
    }

    fun applySort() {
        servers = getSortedList(servers)
    }

    fun checkServer(server: VpnServer) {
        viewModelScope.launch(Dispatchers.IO) {
            updateServerInList(server) { it.copy(status = ServerStatus.UNKNOWN, ping = null, servicePings = emptyMap()) }
            try {
                var pingResult: Long? = null
                var success = false
                
                if (pingMethod == PingMethod.TCP) {
                    pingResult = fastTcpPing(server.host, server.port)
                    success = pingResult != null
                } else {
                    val vpnConfig = XrayConfigGenerator.generateConfig(
                        server = server,
                        dns = dnsServer,
                        sniffing = isSniffingEnabled,
                        mux = isMuxEnabled,
                        routingMode = "ONLY_PROXY",
                        mtu = mtu,
                        assetPath = getApplication<android.app.Application>().filesDir.absolutePath,
                        utlsFingerprint = utlsFingerprint,
                        isTestConfig = true
                    )
                    val delay = Libv2ray.measureOutboundDelay(vpnConfig, pingTargetUrl)
                    if (delay > 0) {
                        pingResult = delay
                        success = true
                    }
                }

                withContext(Dispatchers.Main) {
                    updateServerInList(server) {
                        it.copy(
                            status = if (success) ServerStatus.WORKING else ServerStatus.NOT_WORKING,
                            ping = if (success) pingResult else null,
                            servicePings = if (success) it.servicePings else emptyMap(),
                            serviceErrors = if (success) it.serviceErrors else emptyMap()
                        )
                    }
                    if (success) {
                        launch {
                            val country = fetchCountryWithCache(server)
                            updateServerInList(server) { it.copy(country = country) }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateServerInList(server) { 
                        it.copy(
                            status = ServerStatus.NOT_WORKING, 
                            ping = null, 
                            servicePings = emptyMap(), 
                            serviceErrors = emptyMap()
                        ) 
                    }
                }
            }
        }
    }

    private fun createUnsafeSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(createUnsafeX509TrustManager())
        val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun createUnsafeX509TrustManager(): javax.net.ssl.X509TrustManager {
        return object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
    }
}
