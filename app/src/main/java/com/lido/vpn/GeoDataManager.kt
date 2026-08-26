package com.lido.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale

object GeoDataManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private const val GEOIP_URL = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
    private const val GEOSITE_URL = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
    private const val GEOIP_API = "https://api.github.com/repos/v2fly/geoip/releases/latest"
    private const val GEOSITE_API = "https://api.github.com/repos/v2fly/domain-list-community/releases/latest"

    data class GeoFileInfo(
        val name: String, 
        val size: Long, 
        val lastModified: Long, 
        val exists: Boolean,
        val localVersion: String = "",
        val remoteVersion: String = "",
    )

    fun getGeoFilesInfo(context: Context): List<GeoFileInfo> {
        val prefs = context.getSharedPreferences("geo_prefs", Context.MODE_PRIVATE)
        return listOf("geoip.dat", "geosite.dat").map { fileName ->
            val file = File(context.filesDir, fileName)
            GeoFileInfo(
                name = fileName,
                size = if (file.exists()) file.length() else 0,
                lastModified = if (file.exists()) file.lastModified() else 0,
                exists = file.exists(),
                localVersion = prefs.getString("version_$fileName", "") ?: ""
            )
        }
    }

    suspend fun getRemoteVersions(): Map<String, String> {
        return withContext(Dispatchers.IO) {
            LogManager.addLog("[GitHub] Getting remote versions for geo files...")
            val versions = mutableMapOf<String, String>()
            listOf("geoip.dat" to GEOIP_API, "geosite.dat" to GEOSITE_API).forEach { (name, url) ->
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body?.string() ?: "")
                            val tag = json.optString("tag_name", "unknown")
                            versions[name] = tag
                            LogManager.addLog("[GitHub] $name remote version: $tag")
                        } else {
                            LogManager.addLog("[GitHub] Failed to get $name tag: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.addLog("[GitHub] Error getting $name tag: ${e.message}")
                }
            }
            versions
        }
    }

    suspend fun downloadGeoFiles(context: Context, onProgress: (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            LogManager.addLog("[GitHub] Starting geo update...")
            val remoteVersions = getRemoteVersions()
            val files = listOf(
                "geoip.dat" to GEOIP_URL,
                "geosite.dat" to GEOSITE_URL
            )

            var allSuccess = true
            files.forEach { (name, url) ->
                try {
                    LogManager.addLog("[GitHub] Downloading $name...")
                    onProgress("Downloading $name...")
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            LogManager.addLog("[GitHub] Failed to download $name: ${response.code}")
                            onProgress("Failed to download $name: ${response.message}")
                            allSuccess = false
                            return@forEach
                        }

                        val body = response.body ?: throw Exception("Empty body")
                        val file = File(context.filesDir, name)
                        FileOutputStream(file).use { output ->
                            body.byteStream().copyTo(output)
                        }
                        
                        // Save local version
                        remoteVersions[name]?.let { tag ->
                            context.getSharedPreferences("geo_prefs", Context.MODE_PRIVATE)
                                .edit().putString("version_$name", tag).apply()
                        }

                        LogManager.addLog("[GitHub] $name updated successfully")
                        onProgress("$name updated successfully")
                    }
                } catch (e: Exception) {
                    LogManager.addLog("[GitHub] Error downloading $name: ${e.message}")
                    onProgress("Error downloading $name: ${e.message}")
                    allSuccess = false
                }
            }
            if (allSuccess) LogManager.addLog("[GitHub] Geo update finished successfully")
            allSuccess
        }
    }
}
