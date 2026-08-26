package com.lido.vpn

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lido.vpn.ui.theme.VpnTheme
import com.lido.vpn.ui.screens.*
import com.lido.vpn.ui.components.*

class MainActivity : ComponentActivity() {
    var viewModel: AppViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createChannels(this)
        setContent {
            val vm: AppViewModel = viewModel()
            viewModel = vm
            
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { /* permission granted or denied */ }

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

            val darkTheme = when (vm.appTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.ADAPTIVE -> isSystemInDarkTheme()
            }

            VpnTheme(darkTheme = darkTheme, dynamicColor = false) {
                if (!vm.hasCompletedInitialSetup) {
                    InitialSetupScreen(vm)
                } else {
                    VpnApp(vm)
                    
                    if (!vm.hasSeenGuide || vm.showGuide) {
                        OnboardingGuide(viewModel = vm)
                    }
                }
            }

            LaunchedEffect(intent) {
                if (intent?.getBooleanExtra("AUTO_START", false) == true && vm.hasCompletedInitialSetup) {
                    vm.toggleVpn(this@MainActivity)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnApp(viewModel: AppViewModel = viewModel()) {
    val isRu = viewModel.language == AppLanguage.RU

    BackHandler(enabled = viewModel.currentDestination != AppDestinations.HOME) {
        viewModel.currentDestination = AppDestinations.HOME
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (viewModel.currentDestination == AppDestinations.HOME) {
                TopAppBar(
                    title = { },
                    actions = {
                        if (viewModel.appMode == AppMode.SIMPLE) {
                            IconButton(
                                onClick = { viewModel.runSimpleAutoSetup() },
                                enabled = !viewModel.isAutoSettingUp && !viewModel.isFetching && !viewModel.isChecking && !viewModel.isCheckingTelegram,
                                modifier = Modifier.tvFocusable(CircleShape)
                            ) {
                                if (viewModel.isAutoSettingUp || viewModel.isFetching || viewModel.isChecking || viewModel.isCheckingTelegram) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = if (isRu) "Обновить" else "Refresh")
                                }
                            }
                        }
                        if (viewModel.appMode == AppMode.ADVANCED) {
                            IconButton(
                                onClick = { viewModel.currentDestination = AppDestinations.SERVERS },
                                modifier = Modifier
                                    .tutorialHighlight(viewModel.currentTutorialStep == 3)
                                    .tvFocusable(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = if (isRu) "Серверы" else "Servers",
                                    tint = if (viewModel.currentDestination == AppDestinations.SERVERS) 
                                        MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.currentDestination = AppDestinations.SETTINGS },
                            modifier = Modifier
                                .tutorialHighlight(viewModel.currentTutorialStep == 2)
                                .tvFocusable(CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = if (isRu) "Настройки" else "Settings",
                                tint = if (viewModel.currentDestination == AppDestinations.SETTINGS) 
                                    MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(if (viewModel.currentDestination == AppDestinations.HOME) innerPadding else PaddingValues(0.dp))) {
            when (viewModel.currentDestination) {
                AppDestinations.HOME -> HomeScreen(viewModel, modifier = Modifier.fillMaxSize())
                AppDestinations.SERVERS -> ServersScreen(viewModel, modifier = Modifier.fillMaxSize())
                AppDestinations.LOGS -> LogsScreen(viewModel, modifier = Modifier.fillMaxSize())
                AppDestinations.SETTINGS -> SettingsScreen(viewModel, modifier = Modifier.fillMaxSize())
            }
        }

        if (viewModel.showResourceManagement) {
            ResourceManagementDialog(viewModel = viewModel, onDismiss = { viewModel.showResourceManagement = false })
        }
        if (viewModel.showConfigSelector) {
            ConfigSelectionDialog(viewModel = viewModel, onDismiss = { viewModel.showConfigSelector = false })
        }
        if (viewModel.showOptimizerDialog) {
            StrategyOptimizerDialog(viewModel = viewModel, onDismiss = { viewModel.showOptimizerDialog = false })
        }
        if (viewModel.showAppSelection) {
            AppSelectionDialog(viewModel = viewModel, onDismiss = { viewModel.showAppSelection = false })
        }
        if (viewModel.showAddServerDialog) {
            AddServerDialog(
                viewModel = viewModel, 
                onDismiss = { 
                    viewModel.showAddServerDialog = false
                    viewModel.editingServer = null
                },
                serverToEdit = viewModel.editingServer
            )
        }
    }
}
