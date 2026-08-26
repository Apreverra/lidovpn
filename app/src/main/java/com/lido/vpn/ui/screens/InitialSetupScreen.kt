package com.lido.vpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lido.vpn.AppMode
import com.lido.vpn.AppViewModel
import com.lido.vpn.AppLanguage
import com.lido.vpn.CheckTarget
import com.lido.vpn.tvFocusable
import com.lido.vpn.ui.components.ServiceIcon
import com.lido.vpn.ui.components.ServiceSelectionDialog

@Composable
fun InitialSetupScreen(viewModel: AppViewModel) {
    var step by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                } else {
                    slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                }
            },
            label = "setup_step"
        ) { currentStep ->
            when (currentStep) {
                0 -> ModeSelectionStep(viewModel) { step = 1 }
                1 -> TargetSelectionStep(viewModel) { viewModel.completeInitialSetup() }
            }
        }
    }
}

@Composable
fun ModeSelectionStep(viewModel: AppViewModel, onNext: () -> Unit) {
    val isRu = viewModel.language == AppLanguage.RU
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isRu) "Выберите режим" else "Choose Mode",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRu) "Как вы планируете пользоваться приложением?" else "How do you plan to use the app?",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))

        ModeCard(
            title = if (isRu) "Простой" else "Simple",
            desc = if (isRu) "Приложение само выберет лучший сервер для ваших целей. Идеально для новичков." else "App will automatically pick the best server for you. Ideal for beginners.",
            icon = Icons.Default.RocketLaunch,
            selected = viewModel.appMode == AppMode.SIMPLE,
            onClick = { viewModel.updateAppMode(AppMode.SIMPLE) }
        )

        Spacer(Modifier.height(16.dp))

        ModeCard(
            title = if (isRu) "Продвинутый" else "Advanced",
            desc = if (isRu) "Полный контроль над настройками, серверами и логами. Для опытных пользователей." else "Full control over settings, servers, and logs. For power users.",
            icon = Icons.Default.Settings,
            selected = viewModel.appMode == AppMode.ADVANCED,
            onClick = { viewModel.updateAppMode(AppMode.ADVANCED) }
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp).tvFocusable(MaterialTheme.shapes.large),
            shape = MaterialTheme.shapes.large
        ) {
            Text(if (isRu) "Далее" else "Next")
        }
    }
}

@Composable
fun TargetSelectionStep(viewModel: AppViewModel, onFinish: () -> Unit) {
    val isRu = viewModel.language == AppLanguage.RU
    var showMainSelector by remember { mutableStateOf(false) }
    var showAdditionalSelector by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isRu) "Настройка сервисов" else "Service Setup",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(32.dp))
        
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
        // Убрали лишний Spacer здесь, чтобы слот был ближе к заголовку

        // Additional Slots
        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 8.dp), 
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(viewModel.additionalTargetNames.size) { index ->
                val name = viewModel.additionalTargetNames[index]
                val target = viewModel.checkTargets.find { it.name == name }
                if (target != null) {
                    ServiceSlot(
                        target = target,
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

        Spacer(Modifier.height(24.dp))
        
        val canStart = viewModel.mainTargetName.isNotEmpty()

        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().height(56.dp).tvFocusable(MaterialTheme.shapes.large),
            shape = MaterialTheme.shapes.large,
            enabled = canStart,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        ) {
            Text(if (isRu) "Начать работу" else "Get Started")
        }
    }

    if (showMainSelector) {
        ServiceSelectionDialog(
            viewModel = viewModel,
            onServiceSelected = { 
                viewModel.ensureTargetExists(it)
                viewModel.updateMainTarget(it.name)
                // Если этот сервис был в дополнительных - убираем его оттуда
                if (viewModel.additionalTargetNames.contains(it.name)) {
                    viewModel.toggleAdditionalTarget(it.name)
                }
            },
            onDismiss = { showMainSelector = false }
        )
    }

    if (showAdditionalSelector) {
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
fun ServiceSlot(
    target: CheckTarget?,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    placeholder: String = ""
) {
    var isMainFocused by remember { mutableStateOf(false) }
    var isRemoveFocused by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isMainFocused || isRemoveFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                            else if (target != null) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) 
                            else MaterialTheme.colorScheme.surface
        ),
        border = if (target == null && !isMainFocused) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Main clickable area
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { isMainFocused = it.isFocused }
                    .tvFocusable(MaterialTheme.shapes.medium)
                    .clickable { onClick() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (target != null) {
                    ServiceIcon(target, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(target.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text(target.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                } else {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Independent remove button for TV focus
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(56.dp)
                        .onFocusChanged { isRemoveFocused = it.isFocused }
                        .tvFocusable(RectangleShape)
                        .clickable { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close, 
                        contentDescription = null, 
                        tint = if (isRemoveFocused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ModeCard(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .tvFocusable(MaterialTheme.shapes.large)
            .clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else if (selected) MaterialTheme.colorScheme.primaryContainer 
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
