package com.lido.vpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import com.lido.vpn.AppViewModel
import com.lido.vpn.LogManager
import com.lido.vpn.AppLanguage
import com.lido.vpn.ByeDpiStrategy
import com.lido.vpn.DomainCheckResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val logs = LogManager.vpnLogs
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val allScrollState = androidx.compose.foundation.lazy.rememberLazyListState()
    val checkScrollState = androidx.compose.foundation.lazy.rememberLazyListState()
    val geoScrollState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coreScrollState = androidx.compose.foundation.lazy.rememberLazyListState()

    val currentScrollState = when(selectedTab) {
        1 -> checkScrollState
        2 -> geoScrollState
        3 -> coreScrollState
        else -> allScrollState
    }

    val filteredLogs by remember(selectedTab, logs.size) {
        derivedStateOf {
            when (selectedTab) {
                1 -> logs.filter { (it.contains("Check") || it.contains("ping", ignoreCase = true)) && !it.contains("Geo:") }
                2 -> logs.filter { it.contains("Geo:") || it.contains("geo-данные", ignoreCase = true) || it.contains("Geo Data", ignoreCase = true) }
                3 -> logs.filter { !it.contains("Check") && !it.contains("Geo:") && !it.contains("ping", ignoreCase = true) && (it.contains(":") || it.contains("[")) }
                else -> logs.toList()
            }
        }
    }

    LaunchedEffect(filteredLogs.size, selectedTab) {
        if (filteredLogs.isNotEmpty()) {
            val layoutInfo = currentScrollState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val lastVisibleItem = visibleItems.lastOrNull()
            
            val isAtBottom = lastVisibleItem == null || lastVisibleItem.index >= filteredLogs.size - 10
            
            if (isAtBottom) {
                currentScrollState.scrollToItem(filteredLogs.size - 1)
                yield()
                currentScrollState.scrollToItem(filteredLogs.size - 1)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (viewModel.language == AppLanguage.RU) "Логи (Debug)" else "Logs (Debug)", 
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
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
                        val isSuccessError = log.contains("403") || log.contains("401") || log.contains("400") || log.contains("OK (") || log.contains("[REACHED]")
                        val color = when {
                            isSuccessError -> Color(0xFF4CAF50)
                            log.contains("Geo:") -> Color(0xFFCE93D8)
                            (log.contains("TG Check") || log.contains("Telegram Check") || log.contains("Check:")) && (log.contains("FAILED") || log.contains("Error") || log.contains("Timeout")) -> Color(0xFFF44336)
                            log.contains("TG Check") || log.contains("Telegram Check") || log.contains("Check:") -> if(log.contains("ms")) Color(0xFF4CAF50) else Color(0xFF24A1DE)
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
fun StrategyResultItem(
    strategy: ByeDpiStrategy,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onApply: () -> Unit,
    onFavorite: () -> Unit
) {
    val context = LocalContext.current
    val successColor = when {
        strategy.isTesting -> MaterialTheme.colorScheme.primary
        strategy.successCount > 0 -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.error
    }
    var showMenu by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        colors = CardDefaults.cardColors(
            containerColor = if (strategy.isBest) 
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            else if (strategy.isTesting)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        border = if (strategy.isBest) borderStroke(1.dp, MaterialTheme.colorScheme.tertiary) 
                 else if (strategy.isTesting) borderStroke(1.dp, MaterialTheme.colorScheme.primary)
                 else if (isCurrent) borderStroke(1.dp, MaterialTheme.colorScheme.primary)
                 else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = strategy.args,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2
                        )
                        if (strategy.isBest) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Star, 
                                null, 
                                modifier = Modifier.size(16.dp), 
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        if (isFavorite) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Favorite, 
                                null, 
                                modifier = Modifier.size(16.dp), 
                                tint = Color.Red
                            )
                        }
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (strategy.totalCount > 0) "${strategy.successCount}/${strategy.totalCount}" else if (strategy.isTesting) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = successColor
                    )
                    
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, null)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Apply") },
                                onClick = { onApply(); showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Check, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                onClick = { 
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Strategy Args", strategy.args)
                                    clipboard.setPrimaryClip(clip)
                                    showMenu = false 
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isFavorite) "Remove Favorite" else "Add Favorite") },
                                onClick = { onFavorite(); showMenu = false },
                                leadingIcon = { Icon(if (isFavorite) Icons.Default.FavoriteBorder else Icons.Default.Favorite, null) }
                            )
                        }
                    }
                }
            }
            
            if (strategy.isTesting || (strategy.testProgress > 0 && strategy.testProgress < 1)) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { strategy.testProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    strategy.domainResults.forEach { result ->
                        MiniDomainItem(result)
                    }
                }
            }
        }
    }
}

@Composable
fun MiniDomainItem(result: DomainCheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (result.status) {
                        "WORKING" -> Color.Green
                        "FAILED" -> Color.Red
                        else -> Color.Gray
                    }
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.domain,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            val detailText = if (result.status == "PENDING") "..." else result.detail
            if (detailText.isNotEmpty()) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.status == "WORKING") Color(0xFF4CAF50) else if (result.status == "FAILED") Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun borderStroke(width: androidx.compose.ui.unit.Dp, color: Color) = BorderStroke(width, color)
