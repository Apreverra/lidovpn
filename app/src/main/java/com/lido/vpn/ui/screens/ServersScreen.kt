package com.lido.vpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lido.vpn.*
import com.lido.vpn.ui.components.ServiceIcon
import com.lido.vpn.ui.components.VpnServerItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val servers = viewModel.filteredServers
    val isRu = viewModel.language == AppLanguage.RU
    var showSortMenu by remember { mutableStateOf(false) }

    // Expanded states for sections
    var workingExpanded by rememberSaveable { mutableStateOf(true) }
    var queueExpanded by rememberSaveable { mutableStateOf(false) }
    var failedExpanded by rememberSaveable { mutableStateOf(false) }

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
                    IconButton(onClick = { viewModel.showResourceManagement = true }) {
                        Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = { viewModel.currentDestination = AppDestinations.SETTINGS }) {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.height(60.dp)
            ) {
                NavigationBarItem(
                    selected = viewModel.selectedServerTab == 0,
                    onClick = { viewModel.selectedServerTab = 0 },
                    icon = { Icon(painter = androidx.compose.ui.res.painterResource(com.lido.vpn.R.drawable.ic_github), null, modifier = Modifier.size(20.dp)) },
                    label = { Text(if (isRu) "GitHub Серверы" else "GitHub Servers", fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = viewModel.selectedServerTab == 1,
                    onClick = { viewModel.selectedServerTab = 1 },
                    icon = { Icon(Icons.Default.Person, null, modifier = Modifier.size(20.dp)) },
                    label = { Text(if (isRu) "Свои Серверы" else "My Servers", fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            // Search Bar Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = viewModel.searchQuery,
                    onValueChange = { viewModel.searchQuery = it },
                    placeholder = { Text("Поиск...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                    ),
                    singleLine = true
                )

                Box {
                    Surface(
                        modifier = Modifier.size(44.dp).clickable { showSortMenu = true },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.FilterList, null, tint = if (showSortMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        AppViewModel.SortOrder.entries.forEach { order ->
                            val label = when (order) {
                                AppViewModel.SortOrder.COUNTRY -> if (isRu) "По стране" else "By Country"
                                AppViewModel.SortOrder.PING_SERVICE -> if (isRu) "По пингу сервиса" else "By Service Ping"
                                AppViewModel.SortOrder.PING_GEN -> if (isRu) "По пингу сервера" else "By Server Ping"
                            }
                            DropdownMenuItem(
                                text = { Text(label, color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    viewModel.sortOrder = order
                                    viewModel.applySort()
                                    showSortMenu = false
                                },
                                trailingIcon = {
                                    if (viewModel.sortOrder == order) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }

                if (viewModel.selectedServerTab == 1) {
                    Surface(
                        modifier = Modifier.size(44.dp).clickable { viewModel.showAddServerDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Action Buttons Row
            if (viewModel.selectedServerTab == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.fetchServers() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(painter = androidx.compose.ui.res.painterResource(com.lido.vpn.R.drawable.ic_github), null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Загрузить", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = { if (viewModel.isChecking) viewModel.stopCheck() else viewModel.checkAllServers() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (viewModel.isChecking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (viewModel.isChecking) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        if (viewModel.isChecking) {
                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (viewModel.isChecking) "Стоп" else "Обновить", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                Button(
                    onClick = { if (viewModel.isChecking) viewModel.stopCheck() else viewModel.checkAllServers() },
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(vertical = 2.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isChecking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (viewModel.isChecking) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(if (viewModel.isChecking) Icons.Default.Stop else Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (viewModel.isChecking) "Стоп" else "Обновить", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // Service Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { viewModel.showResourceManagement = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val enabledTargets = viewModel.checkTargets.filter { it.isEnabled }
                    if (enabledTargets.isNotEmpty()) {
                        ServiceIcon(target = enabledTargets.first(), modifier = Modifier.size(28.dp))
                        
                        val additional = enabledTargets.drop(1)
                        additional.take(3).forEach { target ->
                            ServiceIcon(target = target, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Button(
                    onClick = { if (viewModel.isCheckingTelegram) viewModel.cancelCheck() else viewModel.checkAllServices() },
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isCheckingTelegram) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = if (viewModel.isCheckingTelegram) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    if (viewModel.isCheckingTelegram) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (viewModel.isCheckingTelegram) "Стоп" else "Проверить", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            // Section Lists
            val working = servers.filter { it.status == ServerStatus.WORKING }
            val queue = servers.filter { it.status == ServerStatus.UNKNOWN }
            val failed = servers.filter { it.status == ServerStatus.NOT_WORKING }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                item { SectionHeader(if(isRu) "Работают" else "Working", working.size, Color(0xFF4CAF50), workingExpanded) { workingExpanded = !workingExpanded } }
                if (workingExpanded) {
                    itemsIndexed(working, key = { index, s -> "w:${s.host}:${s.port}:${s.name}:$index" }) { _, server ->
                        ServerListItem(viewModel, server)
                    }
                }

                item { SectionHeader(if(isRu) "В очереди" else "In Queue", queue.size, Color.Gray, queueExpanded) { queueExpanded = !queueExpanded } }
                if (queueExpanded) {
                    itemsIndexed(queue, key = { index, s -> "q:${s.host}:${s.port}:${s.name}:$index" }) { _, server ->
                        ServerListItem(viewModel, server)
                    }
                }

                item { SectionHeader(if(isRu) "Ошибка" else "Error", failed.size, MaterialTheme.colorScheme.error, failedExpanded) { failedExpanded = !failedExpanded } }
                if (failedExpanded) {
                    itemsIndexed(failed, key = { index, s -> "f:${s.host}:${s.port}:${s.name}:$index" }) { _, server ->
                        ServerListItem(viewModel, server)
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int, color: Color, isExpanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$title ($count)",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun ServerListItem(viewModel: AppViewModel, server: VpnServer) {
    VpnServerItem(
        server = server,
        isSelected = viewModel.selectedServer?.host == server.host && viewModel.selectedServer?.port == server.port,
        onSelect = { viewModel.updateSelectedServer(server) },
        allTargets = viewModel.checkTargets,
        onMenuClick = {
            if (viewModel.selectedServerTab == 1) {
                viewModel.editingServer = server
                viewModel.showAddServerDialog = true
            }
        }
    )
}
