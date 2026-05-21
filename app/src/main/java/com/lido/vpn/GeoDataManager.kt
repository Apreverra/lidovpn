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
    private val client = OkHttpClient()
    private const val GEOIP_URL = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
    private const val GEOSITE_URL = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
    private const val GEOIP_API = "https://api.github.com/repos/v2fly/geoip/releases/latest"
    private const val GEOSITE_API = "https://api.github.com/repos/v2fly/domain-list-community/releases/latest"

    data class GeoFileInfo(
        val name: String, 
        val size: Long, 
        val lastModified: Long, 
        val exists: Boolean,
        val remoteVersion: String = "",
    )

    fun getGeoFilesInfo(context: Context): List<GeoFileInfo> {
        return listOf("geoip.dat", "geosite.dat").map { fileName ->
            val file = File(context.filesDir, fileName)
            GeoFileInfo(
                name = fileName,
                size = if (file.exists()) file.length() else 0,
                lastModified = if (file.exists()) file.lastModified() else 0,
                exists = file.exists()
            )
        }
    }

    suspend fun getRemoteVersions(): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val versions = mutableMapOf<String, String>()
            listOf("geoip.dat" to GEOIP_API, "geosite.dat" to GEOSITE_API).forEach { (name, url) ->
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body?.string() ?: "")
                            versions[name] = json.optString("tag_name", "unknown")
                        }
                    }
                } catch (_: Exception) {}
            }
            versions
        }
    }

    suspend fun downloadGeoFiles(context: Context, onProgress: (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            val files = listOf(
                "geoip.dat" to GEOIP_URL,
                "geosite.dat" to GEOSITE_URL
            )

            var allSuccess = true
            files.forEach { (name, url) ->
                try {
                    onProgress("Downloading $name...")
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            onProgress("Failed to download $name: ${response.message}")
                            allSuccess = false
                            return@forEach
                        }

                        val body = response.body ?: throw Exception("Empty body")
                        val file = File(context.filesDir, name)
                        FileOutputStream(file).use { output ->
                            body.byteStream().copyTo(output)
                        }
                        onProgress("$name updated successfully")
                    }
                } catch (e: Exception) {
                    onProgress("Error downloading $name: ${e.message}")
                    allSuccess = false
                }
            }
            allSuccess
        }
    }
}
