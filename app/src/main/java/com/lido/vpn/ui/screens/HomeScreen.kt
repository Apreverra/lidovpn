package com.lido.vpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lido.vpn.AppViewModel
import com.lido.vpn.AppLanguage
import com.lido.vpn.AppDestinations
import com.lido.vpn.tutorialHighlight
import com.lido.vpn.tvFocusable
import com.lido.vpn.ui.components.ServiceIcon
import com.lido.vpn.ui.components.ServicePingBadge
import com.lido.vpn.ui.components.ServiceErrorBadge

@Composable
fun HomeScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val buttonSize by animateDpAsState(if (viewModel.isConnected) 210.dp else 190.dp, label = "size")
    val buttonColor by animateColorAsState(
        if (viewModel.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
        label = "color"
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize().padding(16.dp)) {
        val isLandscape = maxWidth > maxHeight
        
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Left Side: Status and Power
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    StatusHeader(viewModel)
                    Spacer(modifier = Modifier.height(24.dp))
                    PowerButton(viewModel, buttonSize, buttonColor, context)
                    
                    if (viewModel.isConnected && viewModel.appMode == com.lido.vpn.AppMode.SIMPLE) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NextServerButton(viewModel, Modifier.width(280.dp))
                    }
                }
                
                // Right Side: Info and Quick Settings (at the bottom)
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ServerInfoCard(viewModel)
                        
                        if (viewModel.appMode == com.lido.vpn.AppMode.SIMPLE) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(0.5f)) { CountrySelector(viewModel) }
                                Box(modifier = Modifier.weight(1.5f)) { UnifiedServicesCard(viewModel) }
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StatusHeader(viewModel)
                
                // Central area for Power Button
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PowerButton(viewModel, buttonSize, buttonColor, context)
                        if (viewModel.isConnected && viewModel.appMode == com.lido.vpn.AppMode.SIMPLE) {
                            Spacer(Modifier.height(24.dp))
                            NextServerButton(viewModel, Modifier.width(240.dp))
                        }
                    }
                }
                
                // Bottom area for info
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ServerInfoCard(viewModel)
                    
                    if (viewModel.appMode == com.lido.vpn.AppMode.SIMPLE) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(0.5f)) { CountrySelector(viewModel) }
                            Box(modifier = Modifier.weight(1.5f)) { UnifiedServicesCard(viewModel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnifiedServicesCard(viewModel: AppViewModel) {
    val enabledTargets = viewModel.checkTargets.filter { it.isEnabled }
    val mainTarget = enabledTargets.find { it.name == viewModel.mainTargetName }
    val additionalTargets = enabledTargets.filter { it.name != viewModel.mainTargetName }
    
    var isFocused by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Main Target Info
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mainTarget != null) {
                    ServiceIcon(target = mainTarget, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = mainTarget.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                } else if (enabledTargets.isNotEmpty()) {
                    val first = enabledTargets.first()
                    ServiceIcon(target = first, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text = first.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                
                if (additionalTargets.isNotEmpty()) {
                    VerticalDivider(
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        additionalTargets.take(3).forEach { target ->
                            ServiceIcon(target = target, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            
            // Only the icon and its immediate area are focusable/clickable
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .tvFocusable(MaterialTheme.shapes.small)
                    .clickable { viewModel.showResourceManagement = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Tune, 
                    null, 
                    tint = MaterialTheme.colorScheme.primary, 
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun CountrySelector(viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val isRu = viewModel.language == AppLanguage.RU
    var isFocused by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .tvFocusable(MaterialTheme.shapes.medium)
            .clickable { expanded = true },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val selectedFlag = com.lido.vpn.extractFlag(viewModel.selectedCountry)
            if (selectedFlag != null) {
                Text(selectedFlag, style = MaterialTheme.typography.headlineSmall)
            } else {
                Icon(Icons.Default.Language, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(300.dp).background(MaterialTheme.colorScheme.surface)
        ) {
            DropdownMenuItem(
                text = { Text(if (isRu) "🏳️ Авто" else "🏳️ Auto", modifier = Modifier.padding(horizontal = 12.dp)) },
                onClick = { viewModel.updateSelectedCountry(""); expanded = false }
            )
            viewModel.availableCountries.forEach { country ->
                DropdownMenuItem(
                    text = { Text(country, modifier = Modifier.padding(horizontal = 12.dp)) },
                    onClick = { viewModel.updateSelectedCountry(country); expanded = false }
                )
            }
        }
    }
}

@Composable
fun NextServerButton(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    Button(
        onClick = { viewModel.pickNextBestServer() },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(52.dp).tvFocusable()
    ) {
        Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(if (viewModel.language == AppLanguage.RU) "Не работает? Другой" else "Not working? Next")
    }
}

@Composable
fun StatusHeader(viewModel: AppViewModel) {
    val isRu = viewModel.language == AppLanguage.RU
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (viewModel.isAutoSettingUp) {
                viewModel.autoSetupStatus
            } else if (viewModel.isDownloadingGeo) {
                if (isRu) "Загрузка баз..." else "Downloading bases..."
            } else if (viewModel.isConnected) {
                if (isRu) "Защита включена" else "Protection is ON"
            } else {
                if (isRu) "Защита выключена" else "Protection is OFF"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (viewModel.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (viewModel.isAutoSettingUp || viewModel.isDownloadingGeo) {
            Text(
                text = if (viewModel.isDownloadingGeo) "" else viewModel.autoSetupSubStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun PowerButton(viewModel: AppViewModel, buttonSize: androidx.compose.ui.unit.Dp, buttonColor: Color, context: android.content.Context) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(buttonSize + 40.dp),
        contentAlignment = Alignment.Center
    ) {
        if (viewModel.isConnected) {
            val infiniteTransition = rememberInfiniteTransition(label = "glow")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(buttonColor.copy(alpha = alpha))
            )
        }

        Surface(
            modifier = Modifier
                .size(buttonSize)
                .clip(MaterialTheme.shapes.extraLarge)
                .onFocusChanged { isFocused = it.isFocused }
                .tutorialHighlight(viewModel.currentTutorialStep == 9)
                .tvFocusable(MaterialTheme.shapes.extraLarge)
                .clickable(enabled = !viewModel.isAutoSettingUp) { viewModel.toggleVpn(context) },
            shape = MaterialTheme.shapes.extraLarge,
            color = if (isFocused) buttonColor.copy(alpha = 0.3f) else buttonColor.copy(alpha = 0.15f),
            border = BorderStroke(6.dp, buttonColor),
            tonalElevation = 8.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (viewModel.isAutoSettingUp) {
                    CircularProgressIndicator(color = buttonColor, modifier = Modifier.size(72.dp), strokeWidth = 6.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = buttonColor
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (viewModel.selectedServer == null && viewModel.appMode == com.lido.vpn.AppMode.SIMPLE && !viewModel.isConnected && !viewModel.isByeDpiEnabled) {
                        if (viewModel.language == AppLanguage.RU) "НАЙТИ" else "FIND"
                    } else if (viewModel.isConnected) {
                        if (viewModel.language == AppLanguage.RU) "СТОП" else "STOP"
                    } else {
                        if (viewModel.language == AppLanguage.RU) "СТАРТ" else "START"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = buttonColor
                )
            }
        }
    }
}

@Composable
fun ServerInfoCard(viewModel: AppViewModel) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .tvFocusable(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (viewModel.language == AppLanguage.RU) "Текущий сервер" else "Current Server",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (viewModel.isByeDpiEnabled) "ByeDPI Engine" 
                           else viewModel.selectedServer?.name ?: (if (viewModel.language == AppLanguage.RU) "Не выбрано" else "Not Selected"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                
                if (!viewModel.isByeDpiEnabled && viewModel.selectedServer != null) {
                    val server = viewModel.selectedServer!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        server.ping?.let {
                            Text(
                                text = "$it ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (it < 150) Color(0xFF4CAF50) else Color(0xFFFFC107)
                            )
                            Spacer(Modifier.width(8.dp))
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

            // Working service icons on the right
            if (!viewModel.isByeDpiEnabled && viewModel.selectedServer != null) {
                val server = viewModel.selectedServer!!
                val enabledTargets = viewModel.checkTargets.filter { it.isEnabled }
                val mainTargetName = viewModel.mainTargetName
                val additionalTargetNames = viewModel.additionalTargetNames
                
                // Unified list of what SHOULD be enabled
                val targetsToShow = enabledTargets.filter { 
                    it.name == mainTargetName || additionalTargetNames.contains(it.name)
                }

                val workingTargets = targetsToShow.mapNotNull { target ->
                    val ping = (server.servicePings ?: emptyMap())[target.name]
                    val error = (server.serviceErrors ?: emptyMap())[target.name] ?: ""
                    val isSuccessError = error.contains("403") || error.contains("401") || error.contains("400")
                    if (ping != null || isSuccessError) {
                        target to (ping ?: -1L)
                    } else null
                }
                
                if (workingTargets.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(start = 16.dp)
                    ) {
                        workingTargets.forEach { (target, ping) ->
                            if (ping > 0) {
                                ServicePingBadge(target = target, name = target.name, ping = ping)
                            } else {
                                ServiceErrorBadge(target = target, name = target.name, error = "403")
                            }
                        }
                    }
                }
            }
        }
    }
}
