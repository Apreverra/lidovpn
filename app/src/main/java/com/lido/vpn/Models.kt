package com.lido.vpn

import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

@Keep
@Immutable
data class VpnServer(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: String, // vless, trojan, vmess
    val host: String,
    val port: Int,
    val uuid: String,
    val params: Map<String, String> = emptyMap(),
    val status: ServerStatus = ServerStatus.UNKNOWN,
    val ping: Long? = null,
    val servicePings: Map<String, Long?>? = emptyMap(), // Store pings for different services
    val serviceErrors: Map<String, String?>? = emptyMap(), // Store error messages
    val rawUrl: String = "",
    val country: String = "",
    val isManual: Boolean = false
)

@Keep
enum class ServerStatus {
    UNKNOWN, WORKING, NOT_WORKING
}

@Keep
data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?
)

@Keep
data class ConfigSource(val name: String, val url: String, val origin: String = "")

@Keep
data class ConfigCategory(val name: String, val items: List<ConfigSource>)

@Keep
data class ConfigProvider(
    val id: String,
    val name: String,
    val owner: String,
    val repo: String,
    val categories: List<ConfigCategory>,
    var lastUpdate: String = ""
)

@Keep
data class VpnUpdateInfo(
    val version: String,
    val description: String,
    val downloadUrl: String,
    val forceUpdate: Boolean = false
)

@Keep
data class DomainCheckResult(
    val domain: String,
    val status: String = "PENDING",
    val detail: String = ""
)

@Keep
data class ByeDpiStrategy(
    val name: String,
    val args: String,
    val successCount: Int = 0,
    val totalCount: Int = 0,
    val isTesting: Boolean = false,
    val isBest: Boolean = false,
    val testProgress: Float = 0f,
    val domainResults: List<DomainCheckResult> = emptyList()
)

@Keep
data class CheckTarget(
    val name: String,
    val url: String,
    val iconRes: Int? = null,
    val isEnabled: Boolean = true,
    val isCustom: Boolean = false
)

@Keep
enum class AppLanguage(val label: String) {
    EN("English"),
    RU("Русский")
}

@Keep
enum class AppTheme {
    LIGHT, DARK, ADAPTIVE
}

@Keep
enum class AppMode {
    SIMPLE, ADVANCED
}

@Keep
enum class AppDestinations {
    HOME,
    SERVERS,
    LOGS,
    SETTINGS,
}

@Keep
data class StepContent(
    val title: String,
    val description: String,
    val alignment: androidx.compose.ui.Alignment
)
