package com.lido.vpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lido.vpn.*
import com.lido.vpn.BuildConfig
import com.lido.vpn.ui.components.SettingsSwitch
import com.lido.vpn.ui.components.UpdateDialog
import com.lido.vpn.ui.components.DebugMenuDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isRu = viewModel.language == AppLanguage.RU
    
    var showAppModeMenu by remember { mutableStateOf(false) }
    var showRoutingMenu by remember { mutableStateOf(false) }
    var showPingMethodMenu by remember { mutableStateOf(false) }
    var showFingerprintMenu by remember { mutableStateOf(false) }
    var showDebugMenu by remember { mutableStateOf(false) }
    var versionClickCount by remember { mutableIntStateOf(0) }
    
    var mtuText by rememberSaveable { mutableStateOf(viewModel.mtu.toString()) }
    var checksText by rememberSaveable { mutableStateOf(viewModel.concurrentChecks.toString()) }
    var listenPortText by rememberSaveable { mutableStateOf(viewModel.listenPort.toString()) }
    var pingTimeoutText by rememberSaveable { mutableStateOf(viewModel.pingTimeout.toString()) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { viewModel.currentDestination = AppDestinations.HOME }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.currentDestination = AppDestinations.SERVERS }) {
                        Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = { /* Already here */ }) {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text(
                    text = if (isRu) "Настройки" else "Settings",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            // --- Основные ---
            item {
                SectionHeader(if (isRu) "Основные" else "Basic")
                
                // App Mode
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    OutlinedTextField(
                        value = if (viewModel.appMode == AppMode.SIMPLE) (if (isRu) "Простой" else "Simple") else (if (isRu) "Продвинутый" else "Advanced"),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(if (isRu) "Режим приложения" else "App Mode") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Box(modifier = Modifier.matchParentSize().clickable { showAppModeMenu = true })
                    DropdownMenu(expanded = showAppModeMenu, onDismissRequest = { showAppModeMenu = false }) {
                        AppMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(if (mode == AppMode.SIMPLE) (if (isRu) "Простой" else "Simple") else (if (isRu) "Продвинутый" else "Advanced")) },
                                onClick = { viewModel.updateAppMode(mode); showAppModeMenu = false }
                            )
                        }
                    }
                }

                // ByeDPI
                SettingsSwitch(
                    label = if (isRu) "Обход блокировок (ByeDPI)" else "DPI Bypass (ByeDPI)",
                    subtitle = if (isRu) "Для работы YouTube и сервисов в РФ" else "For YouTube and services in RU",
                    checked = viewModel.isByeDpiEnabled,
                    onCheckedChange = { viewModel.updateByeDpiEnabled(it) }
                )

                AnimatedVisibility(visible = viewModel.isByeDpiEnabled) {
                    Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                        SettingsRow(
                            label = if (isRu) "Подбор стратегии" else "Pick Strategy",
                            subtitle = if (isRu) "Автоматический поиск рабочих настроек" else "Auto-find working parameters",
                            onClick = { viewModel.showOptimizerDialog = true }
                        )
                        
                        OutlinedTextField(
                            value = viewModel.byeDpiArgs,
                            onValueChange = { viewModel.updateByeDpiArgs(it) },
                            label = { Text(if (isRu) "Параметры ByeDPI" else "ByeDPI Arguments") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                    }
                }

                // Sources
                SettingsRow(
                    label = if (isRu) "Выбрать конфигурации" else "Select Configurations",
                    subtitle = if (isRu) "Управление источниками серверов" else "Manage server sources",
                    onClick = { viewModel.showConfigSelector = true }
                )

                // App Filter
                SettingsSwitch(
                    label = if (isRu) "Фильтр приложений" else "App Filter",
                    subtitle = if (isRu) "VPN только для выбранных программ" else "VPN only for selected apps",
                    checked = viewModel.isAppFilterEnabled,
                    onCheckedChange = { viewModel.updateAppFilterEnabled(it) }
                )

                AnimatedVisibility(visible = viewModel.isAppFilterEnabled) {
                    Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                        SettingsRow(
                            label = if (isRu) "Выбрать приложения" else "Select Applications",
                            subtitle = if (isRu) "Выбрано: ${viewModel.selectedApps.size}" else "Selected: ${viewModel.selectedApps.size}",
                            onClick = { 
                                viewModel.loadApps()
                                viewModel.showAppSelection = true 
                            }
                        )
                        
                        SettingsSwitch(
                            label = if (isRu) "Режим исключения" else "Bypass Mode",
                            subtitle = if (isRu) "Выбранные приложения БЕЗ VPN" else "Selected apps WITHOUT VPN",
                            checked = viewModel.isBypassMode,
                            onCheckedChange = { viewModel.updateBypassMode(it) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- Внешний вид ---
            item {
                SectionHeader(if (isRu) "Внешний вид" else "Appearance")
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Language
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isRu) "Язык" else "Language", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LanguageChip(label = "English", selected = viewModel.language == AppLanguage.EN) {
                                    viewModel.updateLanguage(AppLanguage.EN)
                                }
                                LanguageChip(label = "Русский", selected = viewModel.language == AppLanguage.RU) {
                                    viewModel.updateLanguage(AppLanguage.RU)
                                }
                            }
                        }

                        // Theme
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isRu) "Тема" else "Theme", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Light
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White)
                                        .border(1.5.dp, if (viewModel.appTheme == AppTheme.LIGHT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                        .clickable { viewModel.updateAppTheme(AppTheme.LIGHT) }
                                )
                                // Dark
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black)
                                        .border(1.5.dp, if (viewModel.appTheme == AppTheme.DARK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                        .clickable { viewModel.updateAppTheme(AppTheme.DARK) }
                                )
                                // Adaptive
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(1.5.dp, if (viewModel.appTheme == AppTheme.ADAPTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                        .clickable { viewModel.updateAppTheme(AppTheme.ADAPTIVE) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.BrightnessAuto,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (viewModel.appTheme == AppTheme.ADAPTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- Version & Tutorial ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val currentVersion = try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        } catch (_: Exception) { BuildConfig.VERSION_NAME }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isRu) "Версия: $currentVersion" else "Version: $currentVersion",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).clickable {
                                    versionClickCount++
                                    if (versionClickCount >= 5) { showDebugMenu = true; versionClickCount = 0 }
                                }
                            )
                            Text(
                                text = if (isRu) "Обновить" else "Update",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { viewModel.checkForUpdates() }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = { viewModel.showGuide = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRu) "Обучение" else "Tutorial", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // --- Advanced Toggle ---
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .clickable { viewModel.showAdvancedSettings = !viewModel.showAdvancedSettings },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (viewModel.showAdvancedSettings) (if (isRu) "Скрыть продвинутые настройки" else "Hide Advanced Settings")
                        else (if (isRu) "Показать продвинутые настройки" else "Show Advanced Settings"),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Icon(
                        if (viewModel.showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            // --- Advanced Content ---
            item {
                AnimatedVisibility(
                    visible = viewModel.showAdvancedSettings,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        SectionHeader(if (isRu) "Настройки проверки" else "Testing Settings")
                        
                        // Ping Method
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            OutlinedTextField(
                                value = when(viewModel.pingMethod) {
                                    AppViewModel.PingMethod.TCP -> if (isRu) "TCP Handshake (Прокси)" else "TCP Handshake (Proxy)"
                                    AppViewModel.PingMethod.HTTP -> if (isRu) "HTTP Запрос (Сайт)" else "HTTP Request (Site)"
                                },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(if (isRu) "Способ проверки" else "Ping Method") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                            Box(modifier = Modifier.matchParentSize().clickable { showPingMethodMenu = true })
                            DropdownMenu(expanded = showPingMethodMenu, onDismissRequest = { showPingMethodMenu = false }) {
                                AppViewModel.PingMethod.entries.forEach { method ->
                                    DropdownMenuItem(
                                        text = { Text(when(method) {
                                            AppViewModel.PingMethod.TCP -> if (isRu) "TCP Handshake (Прокси)" else "TCP Handshake (Proxy)"
                                            AppViewModel.PingMethod.HTTP -> if (isRu) "HTTP Запрос (Сайт)" else "HTTP Request (Site)"
                                        }) },
                                        onClick = { viewModel.updatePingMethod(method); showPingMethodMenu = false }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = checksText,
                            onValueChange = { checksText = it; it.toIntOrNull()?.let { v -> viewModel.updateConcurrentChecks(v) } },
                            label = { Text(if (isRu) "Параллельных проверок" else "Threads") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(if (isRu) "Настройки ядра VPN" else "VPN Core Settings")

                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = viewModel.listenAddress,
                                onValueChange = { viewModel.updateListenAddress(it) },
                                label = { Text(if (isRu) "IP прослушивания" else "Listen IP") },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                            OutlinedTextField(
                                value = listenPortText,
                                onValueChange = { listenPortText = it; it.toIntOrNull()?.let { v -> viewModel.updateListenPort(v) } },
                                label = { Text(if (isRu) "Порт" else "Port") },
                                modifier = Modifier.width(100.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                        }

                        OutlinedTextField(
                            value = viewModel.dnsServer,
                            onValueChange = { viewModel.updateDnsServer(it) },
                            label = { Text("DNS") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )

                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            OutlinedTextField(
                                value = when(viewModel.routingMode) {
                                    AppViewModel.RoutingMode.GLOBAL -> if (isRu) "Глобальный" else "Global"
                                    AppViewModel.RoutingMode.BYPASS_LAN_RU -> if (isRu) "В обход LAN и РФ" else "Bypass LAN & Russia"
                                    AppViewModel.RoutingMode.ONLY_PROXY -> if (isRu) "Только прокси" else "Only Proxy"
                                },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(if (isRu) "Режим маршрутизации" else "Routing Mode") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                            Box(modifier = Modifier.matchParentSize().clickable { showRoutingMenu = true })
                            DropdownMenu(expanded = showRoutingMenu, onDismissRequest = { showRoutingMenu = false }) {
                                AppViewModel.RoutingMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(when(mode) {
                                            AppViewModel.RoutingMode.GLOBAL -> if (isRu) "Глобальный" else "Global"
                                            AppViewModel.RoutingMode.BYPASS_LAN_RU -> if (isRu) "В обход LAN и РФ" else "Bypass LAN & Russia"
                                            AppViewModel.RoutingMode.ONLY_PROXY -> if (isRu) "Только прокси" else "Only Proxy"
                                        }) },
                                        onClick = { viewModel.updateRoutingMode(mode); showRoutingMenu = false }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = mtuText,
                            onValueChange = { mtuText = it; it.toIntOrNull()?.let { v -> viewModel.updateMtu(v) } },
                            label = { Text("MTU") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )

                        Button(
                            onClick = { viewModel.currentDestination = AppDestinations.LOGS },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRu) "Просмотр логов" else "View Logs")
                        }

                        SettingsSwitch(label = if (isRu) "Сниффинг" else "Sniffing", subtitle = if (isRu) "Определение доменов" else "Detect domain names", checked = viewModel.isSniffingEnabled, onCheckedChange = { viewModel.updateSniffing(it) })
                        SettingsSwitch(label = "Mux", subtitle = if (isRu) "Мультиплексирование" else "Multiplexing", checked = viewModel.isMuxEnabled, onCheckedChange = { viewModel.updateMux(it) })
                        SettingsSwitch(label = "IPv6", subtitle = if (isRu) "Поддержка IPv6" else "IPv6 support", checked = viewModel.isIpv6Enabled, onCheckedChange = { viewModel.updateIpv6(it) })

                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            OutlinedTextField(
                                value = viewModel.utlsFingerprint,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(if (isRu) "TLS отпечаток" else "TLS Fingerprint") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                            Box(modifier = Modifier.matchParentSize().clickable { showFingerprintMenu = true })
                            DropdownMenu(expanded = showFingerprintMenu, onDismissRequest = { showFingerprintMenu = false }) {
                                listOf("chrome", "firefox", "safari", "edge", "android", "random").forEach { fp ->
                                    DropdownMenuItem(text = { Text(fp) }, onClick = { viewModel.updateUtlsFingerprint(fp); showFingerprintMenu = false })
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(if (isRu) "Гео-данные" else "Geo Data")
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                viewModel.geoFilesInfo.forEach { file ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(file.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = if (file.exists) "${file.size / 1024 / 1024} MB" else (if (isRu) "Отсутствует" else "Missing"),
                                            color = if(file.exists) MaterialTheme.colorScheme.onSurface else Color.Red
                                        )
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.refreshGeoInfo() },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                                    ) { Text(if (isRu) "Проверить" else "Check") }
                                    Button(
                                        onClick = { viewModel.downloadGeoData() },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                        enabled = !viewModel.isDownloadingGeo
                                    ) { Text(if (isRu) "Скачать" else "Download") }
                                }
                            }
                        }
                    }
                }
            }
            
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    if (showDebugMenu) {
        DebugMenuDialog(viewModel = viewModel, onDismiss = { showDebugMenu = false })
    }
    viewModel.updateInfo?.let { info ->
        UpdateDialog(viewModel = viewModel, info = info, onDismiss = { viewModel.updateInfo = null })
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

@Composable
fun SettingsRow(label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
