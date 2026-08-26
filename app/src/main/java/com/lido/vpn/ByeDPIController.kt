package com.lido.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStreamReader
import java.io.BufferedReader

object ByeDPIController {
    private var process: Process? = null
    private const val BINARY_NAME = "libbyebyedpi.so"

    fun start(context: Context, args: String, listenAddr: String, localPort: Int) {
        stop()
        
        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val execFile = File(nativeDir, BINARY_NAME)
            
            if (!execFile.exists()) {
                LogManager.addLog("ByeDPI Error: Binary not found")
                return
            }

            val userArgs = args.split(Regex("\\s+")).filter { it.isNotBlank() }
            
            // Мы НЕ добавляем DNS-флаги автоматически, так как версии бинарников разные.
            // Также НЕ добавляем -D, так как это может быть Daemonize.
            val cmdList = mutableListOf(execFile.absolutePath, "-i", listenAddr, "-p", localPort.toString())
            
            userArgs.forEach { arg ->
                if (arg !in listOf("-i", "-p", listenAddr, localPort.toString(), "-D")) {
                    cmdList.add(arg)
                }
            }

            LogManager.addLog("ByeDPI: Executing...")
            LogManager.addLog("ByeDPI: byedpi ${cmdList.drop(1).joinToString(" ")}")
            
            val pb = ProcessBuilder(cmdList)
            pb.directory(context.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(true)
            
            val proc = pb.start()
            process = proc
            
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { LogManager.addLog("ByeDPI: $it") }
                    }
                } catch (e: Exception) {}
            }.start()

            Thread {
                Thread.sleep(3000)
                if (isAliveCompat(proc)) {
                    LogManager.addLog("ByeDPI: Engine is ONLINE")
                } else {
                    val exitCode = try { proc.exitValue() } catch(_: Exception) { -1 }
                    LogManager.addLog("ByeDPI: Stopped (Code: $exitCode)")
                }
            }.start()

        } catch (e: Exception) {
            LogManager.addLog("ByeDPI Critical Error: ${e.message}")
        }
    }

    private fun isAliveCompat(p: Process): Boolean {
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    fun stop() {
        if (process != null) {
            process?.destroy()
            process = null
            LogManager.addLog("ByeDPI: Stopped")
        }
    }

    fun isActive(): Boolean {
        return process?.let { isAliveCompat(it) } ?: false
    }
}
