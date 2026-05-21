package com.lido.vpn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    var vpnLogs by mutableStateOf(listOf<String>())
        private set
    
    private const val MAX_LOGS = 2000

    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timestamp] $message"
        
        // Use a background-safe way to update state if needed, but for now simple list update
        val currentLogs = vpnLogs.toMutableList()
        currentLogs.add(formattedMessage)
        if (currentLogs.size > MAX_LOGS) {
            currentLogs.removeAt(0)
        }
        vpnLogs = currentLogs
    }

    fun clearLogs() {
        vpnLogs = emptyList()
    }
}
