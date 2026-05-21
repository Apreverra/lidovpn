package com.lido.vpn

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.File
import java.io.FileOutputStream

class VpnApplication : Application() {
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("MESSAGE")?.let { 
                LogManager.addLog(it)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        copyAssetsIfNeeded()
        
        val filter = IntentFilter("VPN_LOG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        
        LogManager.addLog("Application started")
    }

    private fun copyAssetsIfNeeded() {
        val assetFiles = listOf("geoip.dat", "geosite.dat")
        assetFiles.forEach { fileName ->
            val targetFile = File(filesDir, fileName)
            if (!targetFile.exists()) {
                try {
                    assets.open(fileName).use { inputStream ->
                        FileOutputStream(targetFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    LogManager.addLog("Asset copied: $fileName")
                } catch (e: Exception) {
                    LogManager.addLog("Failed to copy asset $fileName: ${e.message}")
                }
            }
        }
    }
}
