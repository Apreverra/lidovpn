package com.lido.vpn.ui.components

import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Context
import java.io.File
import com.lido.vpn.AppViewModel
import com.lido.vpn.VpnServer
import com.lido.vpn.AppLanguage
import com.lido.vpn.AppDestinations
import com.lido.vpn.CheckTarget
import com.lido.vpn.ByeDpiStrategy
import com.lido.vpn.VpnUpdateInfo
import com.lido.vpn.AppInfo
import com.lido.vpn.LogManager
import com.lido.vpn.R
import com.lido.vpn.tvFocusable
import com.lido.vpn.AppMode
import com.lido.vpn.ui.screens.StrategyResultItem
import com.lido.vpn.ui.screens.ServiceSlot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(viewModel: AppViewModel, onDismiss: () -> Unit, serverToEdit: VpnServer? = null) {
    var name by remember { mutableStateOf(serverToEdit?.name ?: "") }
    var type by remember { 
        mutableStateOf(
            if (serverToEdit?.type?.startsWith("SOCKS", ignoreCase = true) == true) "SOCKS" 
            else serverToEdit?.type ?: "VLESS",
        ) 
    }
    var host by remember { mutableStateOf(serverToEdit?.host ?: "") }
    var port by remember { mutableStateOf(serverToEdit?.port?.toString() ?: "443") }
    
    // Protocol specific
    var uuid by remember { mutableStateOf(serverToEdit?.uuid ?: "") } // Also used for Password
    
    // Initialize SS method if editing
    var initialSsMethod = "aes-256-gcm"
    var initialSsPass = uuid
    if ((type == "SS") && uuid.contains(":")) {
        val parts = uuid.split(":")
        initialSsMethod = parts[0]
        initialSsPass = parts[1]
    }
    
    // Initialize SOCKS user/pass if editing
    var initialSocksUser = ""
    var initialSocksPass = ""
    if (type == "SOCKS" && uuid.contains(":")) {
        val parts = uuid.split(":")
        initialSocksUser = parts[0]
        initialSocksPass = parts[1]
    }

    var vmessSecurity by remember { mutableStateOf(serverToEdit?.params?.get("scy") ?: "auto") }
    var ssMethod by remember { mutableStateOf(initialSsMethod) }
    var vmessAid by remember { mutableStateOf(serverToEdit?.params?.get("aid") ?: "0") }
    var socksUser by remember { mutableStateOf(initialSocksUser) }
    var socksPass by remember { mutableStateOf(initialSocksPass) }
    var flow by remember { mutableStateOf(serverToEdit?.params?.get("flow") ?: "") }
    
    // Transport & Security
    var network by remember { mutableStateOf(serverToEdit?.params?.get("net") ?: serverToEdit?.params?.get("type") ?: "tcp") }
    var security by remember { mutableStateOf(serverToEdit?.params?.get("security") ?: "none") }
    var sni by remember { mutableStateOf(serverToEdit?.params?.get("sni") ?: "") }
    var path by remember { mutableStateOf(serverToEdit?.params?.get("path") ?: serverToEdit?.params?.get("serviceName") ?: "") }
    var realityPbk by remember { mutableStateOf(serverToEdit?.params?.get("pbk") ?: "") }
    var realitySid by remember { mutableStateOf(serverToEdit?.params?.get("sid") ?: "") }
    var fingerprint by remember { mutableStateOf(serverToEdit?.params?.get("fp") ?: "chrome") }
    var allowInsecure by remember { mutableStateOf(serverToEdit?.params?.get("insecure") == "true") }
    var alpn by remember { mutableStateOf(serverToEdit?.params?.get("alpn") ?: "") }
    var socksVersion by remember { mutableStateOf(serverToEdit?.params?.get("version") ?: "5") }

    LaunchedEffect(type) {
        if (serverToEdit == null) {
            when (type) {
                "Trojan", "VLESS", "VMess" -> {
                    if (security == "none") security = "tls"
                }
                "SS", "SOCKS" -> {
                    security = "none"
                }
            }
        }
    }

    val isRu = viewModel.language == AppLanguage.RU
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (isRu) "Настройка сервера" else "Server Setup") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, modifier = Modifier.tvFocusable(CircleShape)) {
                            Icon(Icons.Default.Close, null)
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            val p = port.toIntOrNull() ?: 443
                            val params = mutableMapOf<String, String>()
                            params["net"] = network
                            params["security"] = security
                            if (sni.isNotEmpty()) params["sni"] = sni
                            if (alpn.isNotEmpty()) params["alpn"] = alpn
                            if (path.isNotEmpty()) {
                                params["path"] = path
                                params["serviceName"] = path
                            }
                            if (security == "reality" || security == "tls") {
                                params["fp"] = fingerprint
                                if (allowInsecure) params["insecure"] = "true"
                            }
                            if (security == "reality") {
                                params["pbk"] = realityPbk
                                params["sid"] = realitySid
                            }
                            if (type == "VLESS" && flow.isNotEmpty()) {
                                params["flow"] = flow
                            }
                            if (type == "VMESS") {
                                params["aid"] = vmessAid
                                params["scy"] = vmessSecurity
                            }
                            if (type == "SOCKS") {
                                params["version"] = socksVersion
                            }
                            if (type == "TUIC") {
                                params["pass"] = socksPass
                            }
                            
                            val finalUuid = when(type) {
                                "SS" -> "$ssMethod:${if(serverToEdit != null && uuid == initialSsPass) initialSsPass else uuid}"
                                "SOCKS" -> if (socksUser.isNotEmpty()) "$socksUser:$socksPass" else ""
                                else -> uuid
                            }
                            
                            viewModel.addManualServer(
                                VpnServer(
                                id = serverToEdit?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.ifEmpty { "Manual $type" },
                                type = type,
                                host = host, 
                                port = p, 
                                uuid = finalUuid, 
                                params = params,
                                isManual = true,
                                rawUrl = serverToEdit?.rawUrl ?: ""
                            ))
                            onDismiss()
                        }, enabled = host.isNotEmpty() && (uuid.isNotEmpty() || type == "SOCKS" || (type == "SS" && uuid.isNotEmpty())),
                            modifier = Modifier.tvFocusable()) {
                            Text(if (isRu) "СОХРАНИТЬ" else "SAVE", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (serverToEdit == null) {
                    Button(
                        onClick = {
                            viewModel.importFromClipboard(context)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth().tvFocusable(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Icon(Icons.Default.ContentPaste, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRu) "Импорт из буфера обмена" else "Import from Clipboard")
                    }

                    HorizontalDivider()
                }

                Text(if (isRu) "Основные настройки" else "Main Settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isRu) "Название (любое)" else "Display Name") },
                    modifier = Modifier.fillMaxWidth().tvFocusable(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var showTypeMenu by remember { mutableStateOf(value = false) }
                    Box(modifier = Modifier.weight(1.5f)) {
                        OutlinedTextField(
                            value = type,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(if (isRu) "Тип протокола" else "Protocol") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true)
                                .clickable { showTypeMenu = true },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                        )
                        DropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                            listOf("VLESS", "VMess", "Trojan", "SS", "SOCKS", "Hysteria2", "TUIC").forEach {
                                DropdownMenuItem(text = { Text(it) }, onClick = { type = it; showTypeMenu = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f).tvFocusable(),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(if (isRu) "Адрес сервера (IP или домен)" else "Server Address (IP/Host)") },
                    modifier = Modifier.fillMaxWidth().tvFocusable(),
                    singleLine = true
                )

                // Dynamic Fields based on Type
                when (type) {
                    "VLESS", "VMess" -> {
                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("UUID") },
                            modifier = Modifier.fillMaxWidth().tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true),
                            singleLine = true
                        )
                        if (type == "VLESS") {
                            var showFlowMenu by remember { mutableStateOf(false) }
                            Box {
                                OutlinedTextField(
                                    value = flow,
                                    onValueChange = {},
                                    readOnly = true,
                                    enabled = false,
                                    label = { Text("Flow") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .tvFocusable()
                                        .clickable { showFlowMenu = true }
                                )
                                DropdownMenu(expanded = showFlowMenu, onDismissRequest = { showFlowMenu = false }) {
                                    listOf("", "xtls-rprx-vision").forEach {
                                        DropdownMenuItem(text = { Text(it.ifEmpty { "none" }) }, onClick = { flow = it; showFlowMenu = false })
                                    }
                                }
                            }
                        } else {
                            var showScyMenu by remember { mutableStateOf(false) }
                            Box {
                                OutlinedTextField(
                                    value = vmessSecurity,
                                    onValueChange = {},
                                    readOnly = true,
                                    enabled = false,
                                    label = { Text("VMess Security") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .tvFocusable()
                                        .clickable { showScyMenu = true }
                                )
                                DropdownMenu(expanded = showScyMenu, onDismissRequest = { showScyMenu = false }) {
                                    listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none").forEach {
                                        DropdownMenuItem(text = { Text(it) }, onClick = { vmessSecurity = it; showScyMenu = false })
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = vmessAid,
                                onValueChange = { vmessAid = it },
                                label = { Text("alterId") },
                                modifier = Modifier.fillMaxWidth().tvFocusable(),
                                singleLine = true
                            )
                        }
                    }
                    "Trojan", "Hysteria2" -> {
                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text(if (isRu) "Пароль" else "Password") },
                            modifier = Modifier.fillMaxWidth().tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true),
                            singleLine = true
                        )
                    }
                    "TUIC" -> {
                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("UUID") },
                            modifier = Modifier.fillMaxWidth().tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = socksPass, // Reuse socksPass for TUIC password
                            onValueChange = { socksPass = it },
                            label = { Text(if (isRu) "Пароль" else "Password") },
                            modifier = Modifier.fillMaxWidth().tvFocusable(),
                            singleLine = true
                        )
                    }
                    "SS" -> {
                        var showMethodMenu by remember { mutableStateOf(false) }
                        Box {
                            OutlinedTextField(
                                value = ssMethod,
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                label = { Text(if (isRu) "Метод шифрования" else "Encryption Method") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .tvFocusable()
                                    .clickable { showMethodMenu = true }
                            )
                            DropdownMenu(expanded = showMethodMenu, onDismissRequest = { showMethodMenu = false }) {
                                listOf("aes-256-gcm", "aes-128-gcm", "chacha20-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm").forEach {
                                    DropdownMenuItem(text = { Text(it) }, onClick = { ssMethod = it; showMethodMenu = false })
                                }
                            }
                        }
                        OutlinedTextField(
                            value = if(serverToEdit != null && uuid == initialSsPass) "********" else uuid,
                            onValueChange = { uuid = it },
                            label = { Text(if (isRu) "Пароль" else "Password") },
                            modifier = Modifier.fillMaxWidth().tvFocusable(),
                            singleLine = true
                        )
                    }
                    "SOCKS" -> {
                        var showVersionMenu by remember { mutableStateOf(false) }
                        Box {
                            OutlinedTextField(
                                value = "SOCKS$socksVersion",
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                label = { Text("Version") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .tvFocusable()
                                    .clickable { showVersionMenu = true }
                            )
                            DropdownMenu(expanded = showVersionMenu, onDismissRequest = { showVersionMenu = false }) {
                                listOf("4", "5").forEach {
                                    DropdownMenuItem(text = { Text("SOCKS$it") }, onClick = { socksVersion = it; showVersionMenu = false })
                                }
                            }
                        }
                        OutlinedTextField(value = socksUser, onValueChange = { socksUser = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth().tvFocusable())
                        OutlinedTextField(value = socksPass, onValueChange = { socksPass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth().tvFocusable())
                    }
                }

                if (type != "SS" && !type.startsWith("SOCKS", ignoreCase = true)) {
                    HorizontalDivider()
                    Text(if (isRu) "Транспорт и Безопасность" else "Transport & Security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var showNetMenu by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = network,
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                label = { Text("Network") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .tvFocusable()
                                    .clickable { showNetMenu = true }
                            )
                            DropdownMenu(expanded = showNetMenu, onDismissRequest = { showNetMenu = false }) {
                                listOf("tcp", "ws", "grpc", "h2").forEach {
                                    DropdownMenuItem(text = { Text(it) }, onClick = { network = it; showNetMenu = false })
                                }
                            }
                        }
                        var showSecMenu by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = security,
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                label = { Text("Security") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .tvFocusable()
                                    .clickable { showSecMenu = true }
                            )
                            DropdownMenu(expanded = showSecMenu, onDismissRequest = { showSecMenu = false }) {
                                listOf("none", "tls", "reality").forEach {
                                    DropdownMenuItem(text = { Text(it) }, onClick = { security = it; showSecMenu = false })
                                }
                            }
                        }
                    }

                    OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI") }, modifier = Modifier.fillMaxWidth().tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true))
                    OutlinedTextField(value = alpn, onValueChange = { alpn = it }, label = { Text("ALPN (comma separated, e.g. h2,http/1.1)") }, modifier = Modifier.fillMaxWidth().tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true))
                    OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text(if (network == "grpc") "Service Name" else "Path") }, modifier = Modifier.fillMaxWidth().tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true))
                    
                    if (security == "tls" || security == "reality") {
                        var showFpMenu by remember { mutableStateOf(false) }
                        Box {
                            OutlinedTextField(
                                value = fingerprint,
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                label = { Text("uTLS Fingerprint") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .tvFocusable()
                                    .clickable { showFpMenu = true }
                            )
                            DropdownMenu(expanded = showFpMenu, onDismissRequest = { showFpMenu = false }) {
                                listOf("chrome", "firefox", "safari", "edge", "android", "random").forEach {
                                    DropdownMenuItem(text = { Text(it) }, onClick = { fingerprint = it; showFpMenu = false })
                                }
                            }
                        }
                        
                        if (security == "tls") {
                            SettingsSwitch(
                                label = if (isRu) "Разрешить небезопасные сертификаты" else "Allow Insecure Certificates",
                                subtitle = if (isRu) "Пропускать проверку SSL" else "Skip SSL verification",
                                checked = allowInsecure,
                                onCheckedChange = { allowInsecure = it },
                            )
                        }
                    }

                    if (security == "reality") {
                        OutlinedTextField(value = realityPbk, onValueChange = { realityPbk = it }, label = { Text("Reality Public Key") }, modifier = Modifier.fillMaxWidth().tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true))
                        OutlinedTextField(value = realitySid, onValueChange = { realitySid = it }, label = { Text("Reality Short ID") }, modifier = Modifier.fillMaxWidth().tvFocusable(MaterialTheme.shapes.small, extraPadding = 4.dp, forceFocus = true))
                    }
                }
                
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
                    IconButton(onClick = onDismiss, modifier = Modifier.tvFocusable(CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                    Text(
                        text = if (viewModel.language == AppLanguage.RU) "Выбор конфигураций" else "Select Configurations",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Provider Selection
                Text(
                    text = if (viewModel.language == AppLanguage.RU) "Поставщик серверов:" else "Config Provider:",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    viewModel.configProviders.forEach { provider ->
                        FilterChip(
                            selected = viewModel.selectedProviderId == provider.id,
                            onClick = { viewModel.selectProvider(provider.id) },
                            shape = MaterialTheme.shapes.medium,
                            label = { 
                                Column {
                                    Text(provider.name)
                                    viewModel.providerUpdates[provider.id]?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            },
                            modifier = Modifier.padding(bottom = 4.dp).tvFocusable()
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                PullToRefreshBox(
                    isRefreshing = viewModel.isRefreshingProvider,
                    onRefresh = { viewModel.fetchProviderUpdate(viewModel.currentProvider, forceFileUpdate = true) },
                    modifier = Modifier.weight(1f)
                ) {
                    if (viewModel.selectedProviderId == "avencores" || viewModel.selectedProviderId == "whoahaow") {
                        // Табличный вид
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("№", modifier = Modifier.width(25.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                val fileWidth = if(viewModel.selectedProviderId == "whoahaow") 130.dp else 55.dp
                                Text(if (viewModel.language == AppLanguage.RU) "Файл" else "File", modifier = Modifier.width(fileWidth), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                
                                if (viewModel.selectedProviderId != "whoahaow") {
                                    Text(if (viewModel.language == AppLanguage.RU) "Источник" else "Source", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                                
                                Text(if (viewModel.language == AppLanguage.RU) "Серв." else "Srv", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                Text(if (viewModel.language == AppLanguage.RU) "Обновлен" else "Updated", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                            }
                            
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                viewModel.currentProvider.categories.forEach { category ->
                                    if (viewModel.selectedProviderId == "whoahaow") {
                                        item {
                                            Text(
                                                category.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    itemsIndexed(category.items, key = { idx, source -> "${source.url}_$idx" }) { idx, source ->
                                        val isSelected = viewModel.selectedSources.contains(source.url)
                                        val index = idx + 1
                                        
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .tvFocusable()
                                                .clickable { viewModel.toggleSource(source.url) }
                                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                                .border(
                                                    width = if (isSelected) 1.dp else 0.dp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent
                                                )
                                                .padding(vertical = 10.dp, horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(index.toString(), modifier = Modifier.width(25.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val fileWidth = if(viewModel.selectedProviderId == "whoahaow") 130.dp else 55.dp
                                            Text("${source.name}${if(!source.name.contains(".")) ".txt" else ""}", modifier = Modifier.width(fileWidth), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
                                            
                                            if (viewModel.selectedProviderId != "whoahaow") {
                                                Text(source.origin.ifEmpty { category.name.substringBefore(" (") }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                                            } else {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                            
                                            // Count & Chunk Selector
                                            Column(
                                                modifier = Modifier.width(40.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                val countStr = viewModel.providerUpdates["${viewModel.selectedProviderId}_${source.url}_count"] ?: ".."
                                                val count = countStr.toIntOrNull() ?: 0
                                                
                                                Text(
                                                    countStr,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                                
                                                if (count > 5000) {
                                                    val selectedChunks = viewModel.sourceSelectedChunks[source.url] ?: emptySet()
                                                    val totalChunks = (count + 4999) / 5000
                                                    
                                                    Box {
                                                        var showChunkMenu by remember { mutableStateOf(false) }
                                                        val label = if (selectedChunks.isEmpty()) "NONE" 
                                                                   else if (selectedChunks.size == 1) "P${selectedChunks.first() + 1}"
                                                                   else "${selectedChunks.size} pts"
                                                        
                                                        Text(
                                                            text = label,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = if(selectedChunks.isNotEmpty()) MaterialTheme.colorScheme.tertiary else Color.Gray,
                                                            fontWeight = FontWeight.Black,
                                                            modifier = Modifier
                                                                .clickable { showChunkMenu = true }
                                                                .padding(2.dp)
                                                                .border(0.5.dp, (if(selectedChunks.isNotEmpty()) MaterialTheme.colorScheme.tertiary else Color.Gray).copy(alpha = 0.5f), MaterialTheme.shapes.extraSmall)
                                                                .padding(horizontal = 2.dp)
                                                        )
                                                        DropdownMenu(expanded = showChunkMenu, onDismissRequest = { showChunkMenu = false }) {
                                                            (0 until totalChunks).forEach { i ->
                                                                val isChunkSelected = selectedChunks.contains(i)
                                                                val start = i * 5000 + 1
                                                                val end = minOf((i + 1) * 5000, count)
                                                                
                                                                DropdownMenuItem(
                                                                    text = { 
                                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                                            Checkbox(checked = isChunkSelected, onCheckedChange = null)
                                                                            Spacer(Modifier.width(8.dp))
                                                                            Text("Часть ${i + 1} ($start-$end)") 
                                                                        }
                                                                    },
                                                                    onClick = {
                                                                        viewModel.toggleSourceChunk(source.url, i)
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            // Combined Date/Time
                                            Column(modifier = Modifier.width(70.dp), horizontalAlignment = Alignment.End) {
                                                Text(
                                                    viewModel.providerUpdates["${viewModel.selectedProviderId}_${source.url}_time"] ?: "--:--", 
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    viewModel.providerUpdates["${viewModel.selectedProviderId}_${source.url}_date"] ?: "--.--", 
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    } else {
                        // Обычный вид для других
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            viewModel.currentProvider.categories.forEach { category ->
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
                                                shape = MaterialTheme.shapes.medium,
                                                label = { Text(source.name) },
                                                modifier = Modifier.padding(bottom = 8.dp).tvFocusable()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StrategyOptimizerDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val isRu = viewModel.language == AppLanguage.RU

    Dialog(
        onDismissRequest = { if (!viewModel.isOptimizing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = onDismiss, enabled = !viewModel.isOptimizing, modifier = Modifier.tvFocusable(CircleShape)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                        Text(
                            text = if (isRu) "Подбор стратегии" else "Strategy Check",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1
                        )
                    }
                    
                    if (viewModel.isOptimizing) {
                        Button(
                            onClick = { viewModel.stopStrategyOptimizer() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.tvFocusable()
                        ) {
                            Text(if (isRu) "Стоп" else "Stop")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.runStrategyOptimizer() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.tvFocusable()
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRu) "Запуск" else "Start")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (viewModel.isOptimizing) {
                    LinearProgressIndicator(
                        progress = { viewModel.optimizationProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = if (isRu) "Результаты (нажмите для деталей):" else "Results (click for details):",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(viewModel.strategies) { strategy ->
                        StrategyResultItem(
                            strategy = strategy,
                            isCurrent = viewModel.byeDpiArgs == strategy.args,
                            isFavorite = viewModel.favoriteStrategies.contains(strategy.args),
                            onApply = { viewModel.applyStrategy(strategy) },
                            onFavorite = { viewModel.toggleFavoriteStrategy(strategy.args) }
                        )
                    }
                }
            }
        }
    }
}

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
                enabled = !viewModel.isDownloadingUpdate,
                modifier = Modifier.tvFocusable()
            ) {
                Text(if (viewModel.language == AppLanguage.RU) "Обновить" else "Update")
            }
        },
        dismissButton = {
            if (!viewModel.isDownloadingUpdate) {
                TextButton(onClick = onDismiss, modifier = Modifier.tvFocusable()) {
                    Text(if (viewModel.language == AppLanguage.RU) "Позже" else "Later")
                }
            }
        }
    )
}

@Composable
fun ResourceManagementDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val isRu = viewModel.language == AppLanguage.RU
    val isSimple = viewModel.appMode == AppMode.SIMPLE
    var showMainSelector by remember { mutableStateOf(false) }
    var showAdditionalSelector by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.tvFocusable(CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                    Text(
                        text = if (isRu) "Настройка сервисов" else "Service Setup",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))

                if (isSimple) {
                    // Simple Mode: Single List Selection
                    Text(
                        text = if (isRu) "Выберите один основной сервис:" else "Select one main service:",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    val categories = mapOf(
                        (if(isRu) "Соцсети и мессенджеры" else "Social & Messaging") to listOf(
                            CheckTarget("Instagram", "https://www.instagram.com", R.drawable.ic_instagram),
                            CheckTarget("X (Twitter)", "https://x.com", R.drawable.ic_x),
                            CheckTarget("Discord", "https://discord.com/app"),
                            CheckTarget("WhatsApp", "https://www.whatsapp.com"),
                            CheckTarget("Telegram", "https://t.me/telegram", R.drawable.ic_telegram),
                            CheckTarget("Facebook", "https://www.facebook.com"),
                            CheckTarget("TikTok", "https://www.tiktok.com"),
                            CheckTarget("Pinterest", "https://www.pinterest.com")
                        ),
                        (if(isRu) "Нейросети" else "AI") to listOf(
                            CheckTarget("ChatGPT", "https://chatgpt.com"),
                            CheckTarget("Claude", "https://claude.ai"),
                            CheckTarget("Gemini", "https://gemini.google.com"),
                            CheckTarget("Perplexity", "https://www.perplexity.ai"),
                            CheckTarget("Microsoft Copilot", "https://copilot.microsoft.com")
                        ),
                        (if(isRu) "Инструменты и Медиа" else "Tools & Media") to listOf(
                            CheckTarget("YouTube", "https://www.youtube.com", R.drawable.ic_youtube),
                            CheckTarget("Spotify", "https://www.spotify.com"),
                            CheckTarget("Canva", "https://www.canva.com"),
                            CheckTarget("Figma", "https://www.figma.com"),
                            CheckTarget("Adobe", "https://www.adobe.com"),
                            CheckTarget("GitHub", "https://github.com", R.drawable.ic_github),
                            CheckTarget("Docker", "https://www.docker.com"),
                            CheckTarget("Twitch", "https://www.twitch.tv")
                        )
                    )

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        categories.forEach { (catName, items) ->
                            item {
                                Text(catName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
                            }
                            items(items) { target ->
                                val isSelected = viewModel.mainTargetName == target.name
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable()
                                        .clickable { 
                                            viewModel.ensureTargetExists(target)
                                            viewModel.updateMainTarget(target.name)
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ServiceIcon(target, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(16.dp))
                                        Text(
                                            text = target.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.weight(1f))
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                } else {
                    // Advanced Mode: Current logic with slots
                    Text(
                        text = if (isRu) "Главный сервис (приоритет пинга):" else "Main Service (ping priority):",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))

                    // Main Slot
                    val mainTarget = viewModel.checkTargets.find { it.name == viewModel.mainTargetName }
                    ServiceSlot(
                        target = mainTarget,
                        onClick = { showMainSelector = true },
                        onRemove = null,
                        placeholder = if (isRu) "Выбрать главный..." else "Select main..."
                    )

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = if (isRu) "Дополнительные сервисы:" else "Additional Services:",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Additional Slots
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(viewModel.additionalTargetNames.size) { index ->
                            val name = viewModel.additionalTargetNames[index]
                            val target = viewModel.checkTargets.find { it.name == name }
                            target?.let { t ->
                                ServiceSlot(
                                    target = t,
                                    onClick = { },
                                    onRemove = { viewModel.toggleAdditionalTarget(name) }
                                )
                            }
                        }
                        item {
                            ServiceSlot(
                                target = null,
                                onClick = { showAdditionalSelector = true },
                                onRemove = null,
                                placeholder = if (isRu) "+ Добавить ещё..." else "+ Add more..."
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(52.dp).tvFocusable(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(if (isRu) "Готово" else "Done")
                }
            }
        }
    }

    if (!isSimple && showMainSelector) {
        ServiceSelectionDialog(
            viewModel = viewModel,
            onServiceSelected = {
                viewModel.ensureTargetExists(it)
                viewModel.updateMainTarget(it.name)
                if (viewModel.additionalTargetNames.contains(it.name)) {
                    viewModel.toggleAdditionalTarget(it.name)
                }
            },
            onDismiss = { showMainSelector = false }
        )
    }

    if (!isSimple && showAdditionalSelector) {
        ServiceSelectionDialog(
            viewModel = viewModel,
            excludeName = viewModel.mainTargetName,
            onServiceSelected = {
                viewModel.ensureTargetExists(it)
                if (!viewModel.additionalTargetNames.contains(it.name)) {
                    viewModel.toggleAdditionalTarget(it.name)
                }
            },
            onDismiss = { showAdditionalSelector = false }
        )
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
                    IconButton(onClick = onDismiss, modifier = Modifier.tvFocusable(CircleShape)) {
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
                    modifier = Modifier.fillMaxWidth().tvFocusable().padding(vertical = 8.dp),
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
                    modifier = Modifier.fillMaxWidth().tvFocusable().padding(top = 16.dp)
                ) {
                    Text(if (viewModel.language == AppLanguage.RU) "Готово" else "Done")
                }
            }
        }
    }
}

@Composable
fun ServiceSelectionDialog(
    viewModel: AppViewModel, 
    excludeName: String = "",
    onServiceSelected: (CheckTarget) -> Unit, 
    onDismiss: () -> Unit
) {
    val isRu = viewModel.language == AppLanguage.RU
    val categories = mapOf(
        (if(isRu) "Соцсети и мессенджеры" else "Social & Messaging") to listOf(
            CheckTarget("Instagram", "https://www.instagram.com", R.drawable.ic_instagram),
            CheckTarget("X (Twitter)", "https://x.com", R.drawable.ic_x),
            CheckTarget("Discord", "https://discord.com/app"),
            CheckTarget("WhatsApp", "https://www.whatsapp.com"),
            CheckTarget("Telegram", "https://t.me/telegram", R.drawable.ic_telegram),
            CheckTarget("Facebook", "https://www.facebook.com"),
            CheckTarget("TikTok", "https://www.tiktok.com"),
            CheckTarget("Pinterest", "https://www.pinterest.com")
        ),
        (if(isRu) "Нейросети" else "AI") to listOf(
            CheckTarget("ChatGPT", "https://chatgpt.com"),
            CheckTarget("Claude", "https://claude.ai"),
            CheckTarget("Gemini", "https://gemini.google.com"),
            CheckTarget("Perplexity", "https://www.perplexity.ai"),
            CheckTarget("Microsoft Copilot", "https://copilot.microsoft.com")
        ),
        (if(isRu) "Инструменты и Медиа" else "Tools & Media") to listOf(
            CheckTarget("YouTube", "https://www.youtube.com", R.drawable.ic_youtube),
            CheckTarget("Spotify", "https://www.spotify.com"),
            CheckTarget("Canva", "https://www.canva.com"),
            CheckTarget("Figma", "https://www.figma.com"),
            CheckTarget("Adobe", "https://www.adobe.com"),
            CheckTarget("GitHub", "https://github.com", R.drawable.ic_github),
            CheckTarget("Docker", "https://www.docker.com"),
            CheckTarget("Twitch", "https://www.twitch.tv")
        )
    )

    var isCustomMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRu) "Выбрать ресурс" else "Select Resource") },
        text = {
            if (isCustomMode) {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(if (isRu) "Название" else "Name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL (например: google.com)") })
                    TextButton(onClick = { isCustomMode = false }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (isRu) "Назад к списку" else "Back to list")
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    categories.forEach { (catName, items) ->
                        val filteredItems = items.filter { it.name != excludeName }
                        if (filteredItems.isNotEmpty()) {
                            item {
                                Text(catName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                            }
                            items(filteredItems) { target ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable()
                                        .clickable { onServiceSelected(target); onDismiss() }
                                        .padding(vertical = 12.dp, horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ServiceIcon(target, modifier = Modifier.size(24.dp))
                                    Text(target.name, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    item {
                        Button(onClick = { isCustomMode = true }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                            Icon(Icons.Default.Edit, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRu) "Свой вариант" else "Custom URL")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isCustomMode) {
                Button(onClick = {
                    onServiceSelected(CheckTarget(name, if (!url.startsWith("http")) "https://$url" else url, isCustom = true))
                    onDismiss()
                }, enabled = name.isNotEmpty() && url.isNotEmpty()) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isRu) "Отмена" else "Cancel")
            }
        }
    )
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
                        viewModel.triggerRealFakeUpdate()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Trigger Real Download Test")
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
                        viewModel.resetInitialSetup()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("Reset Initial Setup (First Run)")
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
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                // --- TEST DESIGN TABLE ---
                Text("Design Table Preview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))

                // Table Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ID", modifier = Modifier.width(30.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text("NAME", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text("VAL", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    Text("DATE", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                }

                // Table Rows (Mock Data)
                val testItems = listOf(
                    Triple("1", "Production Config", "542"),
                    Triple("2", "Test Mirror", "12"),
                    Triple("3", "Bypass Region RU", "3000")
                )

                testItems.forEachIndexed { idx, item ->
                    var isSelected by remember { mutableStateOf(idx == 0) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusable()
                            .clickable { isSelected = !isSelected }
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.first, modifier = Modifier.width(30.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.second, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(item.third, modifier = Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
                        Column(modifier = Modifier.width(70.dp), horizontalAlignment = Alignment.End) {
                            Text("12:45", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("25.05", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                Spacer(Modifier.height(16.dp))
                
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}
