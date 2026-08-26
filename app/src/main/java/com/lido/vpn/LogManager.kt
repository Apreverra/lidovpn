package com.lido.vpn

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    // Use mutableStateListOf for efficient updates without copying the whole list
    private val _vpnLogs = mutableStateListOf<String>()
    val vpnLogs: List<String> get() = _vpnLogs
    
    private const val MAX_LOGS = 2000
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timestamp] $message"
        
        // Ensure state updates happen on the Main thread to avoid snapshot issues and ConcurrentModificationException
        mainHandler.post {
            _vpnLogs.add(formattedMessage)
            if (_vpnLogs.size > MAX_LOGS) {
                _vpnLogs.removeAt(0)
            }
        }
    }

    fun clearLogs() {
        mainHandler.post {
            _vpnLogs.clear()
        }
    }
}
