package com.lido.vpn.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.lido.vpn.R
import com.lido.vpn.NetworkClient
import com.lido.vpn.CheckTarget
import com.lido.vpn.AppViewModel
import com.lido.vpn.AppLanguage
import com.lido.vpn.AppDestinations
import com.lido.vpn.StepContent
import com.lido.vpn.AppInfo
import com.lido.vpn.tvFocusable
import com.lido.vpn.ui.components.ServiceSelectionDialog

@Composable
fun RemoteIcon(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val context = LocalContext.current
    
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "favicons")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                
                val fileName = java.security.MessageDigest.getInstance("MD5")
                    .digest(url.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val cacheFile = File(cacheDir, fileName)
                
                if (cacheFile.exists()) {
                    val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) { bitmap = bmp.asImageBitmap() }
                        return@withContext
                    }
                }
                
                val client = NetworkClient.client
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                try {
                                    cacheFile.outputStream().use { it.write(bytes) }
                                } catch (_: Exception) {}

                                withContext(Dispatchers.Main) {
                                    bitmap = bmp.asImageBitmap()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }
    bitmap?.let {
        Image(bitmap = it, contentDescription = null, modifier = modifier)
    } ?: Box(modifier = modifier.background(Color.Gray.copy(alpha = 0.2f)))
}

@Composable
fun ServiceIcon(target: CheckTarget, modifier: Modifier = Modifier) {
    if (target.iconRes != null) {
        val tint = if (target.name == "Telegram" || target.name == "X (Twitter)") {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.Unspecified
        }
        Icon(
            painter = painterResource(target.iconRes),
            contentDescription = null,
            modifier = modifier,
            tint = tint
        )
    } else {
        val faviconUrl = remember(target.url) {
            try {
                java.net.URL(target.url).host.let { host ->
                    "https://www.google.com/s2/favicons?sz=64&domain=$host"
                }
            } catch (e: Exception) {
                ""
            }
        }
        if (faviconUrl.isNotEmpty()) {
            RemoteIcon(url = faviconUrl, modifier = modifier)
        } else {
            Icon(Icons.Default.Language, null, modifier = modifier)
        }
    }
}

@Composable
fun ServicePingBadge(target: CheckTarget?, name: String, ping: Long) {
    Surface(
        color = Color(0xFF4CAF50).copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF4CAF50).copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (target != null) {
                ServiceIcon(target = target, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = "${ping}ms",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
        }
    }
}

@Composable
fun ServiceErrorBadge(target: CheckTarget?, name: String, error: String) {
    val isSuccessError = error.contains("403") || error.contains("401") || error.contains("400")
    val color = if (isSuccessError) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (target != null) {
                ServiceIcon(target = target, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = if(isSuccessError) "OK" else "ERR",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun SettingsSwitch(label: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(RectangleShape)
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


@Composable
fun AppItem(app: AppInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(RectangleShape)
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
            Box(modifier = Modifier.size(40.dp).background(Color.Gray, MaterialTheme.shapes.medium))
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Checkbox(checked = isSelected, onCheckedChange = { onClick() })
    }
}

@Composable
fun OnboardingGuide(viewModel: AppViewModel) {
    val isRu = viewModel.language == AppLanguage.RU
    val isSimple = viewModel.appMode == com.lido.vpn.AppMode.SIMPLE

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
                Button(onClick = { viewModel.currentTutorialStep = 1 }, modifier = Modifier.tvFocusable()) {
                    Text(if (isRu) "Да, обучите меня" else "Yes, show me")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setGuideSeen() }, modifier = Modifier.tvFocusable()) {
                    Text(if (isRu) "Пропустить" else "Skip")
                }
            }
        )
    }

    if (viewModel.currentTutorialStep > 0) {
        val totalSteps = if (isSimple) 5 else 8

        LaunchedEffect(viewModel.currentTutorialStep) {
            if (isSimple) {
                viewModel.currentDestination = AppDestinations.HOME
            } else {
                when (viewModel.currentTutorialStep) {
                    1, 8 -> viewModel.currentDestination = AppDestinations.HOME
                    2 -> viewModel.currentDestination = AppDestinations.SETTINGS
                    3, 4, 5, 6, 7 -> viewModel.currentDestination = AppDestinations.SERVERS
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(enabled = false) {} 
        ) {
            val content = if (isSimple) {
                when (viewModel.currentTutorialStep) {
                    1 -> StepContent(
                        if (isRu) "Добро пожаловать" else "Welcome",
                        if (isRu) "В простом режиме приложение само находит лучшие серверы." else "In Simple mode, the app automatically finds the best servers for you.",
                        Alignment.Center
                    )
                    2 -> StepContent(
                        if (isRu) "Авто-настройка" else "Auto-Setup",
                        if (isRu) "Нажмите на кнопку обновления вверху, чтобы полностью обновить список серверов и проверить их доступность." else "Tap the refresh icon at the top to fully update and check the server list.",
                        Alignment.TopEnd
                    )
                    3 -> StepContent(
                        if (isRu) "Регион и Ресурсы" else "Region & Resources",
                        if (isRu) "Внизу можно выбрать страну подключения и указать, какие именно сервисы вам нужны (YouTube, Telegram и т.д.)." else "At the bottom, you can pick a connection region and manage which services you need (YouTube, Telegram, etc.).",
                        Alignment.BottomCenter
                    )
                    4 -> StepContent(
                        if (isRu) "Подключение" else "Connection",
                        if (isRu) "Нажмите на центральную кнопку для запуска VPN. Если сервер перестанет работать, нажмите кнопку «Другой» под ней." else "Tap the center button to start the VPN. If a server stops working, use the 'Next' button below it.",
                        Alignment.Center
                    )
                    5 -> StepContent(
                        if (isRu) "Всё готово!" else "Ready!",
                        if (isRu) "Теперь вы можете пользоваться свободным интернетом. Приятной работы!" else "You are all set to enjoy a free internet. Have a nice stay!",
                        Alignment.Center
                    )
                    else -> StepContent("", "", Alignment.Center)
                }
            } else {
                when (viewModel.currentTutorialStep) {
                    1 -> StepContent(
                        if (isRu) "Продвинутый режим" else "Advanced Mode",
                        if (isRu) "Здесь у вас есть полный контроль над каждым сервером и настройками ядра." else "Here you have full control over every server and core settings.",
                        Alignment.Center
                    )
                    2 -> StepContent(
                        if (isRu) "Настройки" else "Settings",
                        if (isRu) "Здесь можно включить ByeDPI для обхода блокировок или добавить свои источники серверов." else "Here you can enable ByeDPI for bypass or add custom server sources.",
                        Alignment.Center
                    )
                    3 -> StepContent(
                        if (isRu) "Загрузка" else "Fetching",
                        if (isRu) "В списке серверов нажмите «Загрузить», чтобы получить свежие прокси из GitHub." else "In the servers list, tap 'Download' to fetch fresh proxies from GitHub.",
                        Alignment.TopCenter
                    )
                    4 -> StepContent(
                        if (isRu) "Проверка пинга" else "Health Check",
                        if (isRu) "Кнопка «Обновить» замерит задержку до всех серверов в списке." else "The 'Update' button measures the ping delay for all servers in the list.",
                        Alignment.TopCenter
                    )
                    5 -> StepContent(
                        if (isRu) "Проверка сервисов" else "Service Checks",
                        if (isRu) "Эта панель позволяет проверить доступность конкретных сайтов через каждый сервер." else "This panel allows checking accessibility of specific sites through each server.",
                        Alignment.Center
                    )
                    6 -> StepContent(
                        if (isRu) "Выбор сервера" else "Select Server",
                        if (isRu) "Просто нажмите на любой рабочий (зеленый) сервер, чтобы выбрать его." else "Just tap any working (green) server to select it.",
                        Alignment.BottomCenter
                    )
                    7 -> StepContent(
                        if (isRu) "Поиск и Сортировка" else "Search & Sort",
                        if (isRu) "Используйте поиск и фильтры, чтобы быстро найти самый быстрый сервер в нужной стране." else "Use search and filters to quickly find the fastest server in the required country.",
                        Alignment.TopCenter
                    )
                    8 -> StepContent(
                        if (isRu) "Готово к запуску" else "Ready to Start",
                        if (isRu) "Возвращайтесь на главный экран и нажимайте кнопку питания для подключения." else "Go back to the home screen and tap the power button to connect.",
                        Alignment.Center
                    )
                    else -> StepContent("", "", Alignment.Center)
                }
            }

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
                            top = if (content.alignment == Alignment.TopCenter || content.alignment == Alignment.TopEnd) 80.dp else 0.dp
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
                            TextButton(onClick = { viewModel.completeGuide() }, modifier = Modifier.tvFocusable()) {
                                Text(if (isRu) "Пропустить" else "Skip")
                            }
                            Button(
                                onClick = {
                                    if (viewModel.currentTutorialStep < totalSteps) viewModel.currentTutorialStep++ else viewModel.completeGuide()
                                },
                                modifier = Modifier.tvFocusable()
                            ) {
                                Text(if (viewModel.currentTutorialStep < totalSteps) (if (isRu) "Далее" else "Next") else (if (isRu) "Понятно" else "Got it"))
                            }
                        }
                    }
                }
            }
        }
    }
}
