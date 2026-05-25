package com.lido.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Scanner

object ByeDPIController {
    private var process: Process? = null
    private const val BINARY_NAME = "libbyebyedpi.so"
    private const val EXECUTABLE_NAME = "byedpi_bin"

    fun start(context: Context, args: String, localPort: Int) {
        stop()
        
        try {
            val internalFile = File(context.filesDir, EXECUTABLE_NAME)
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val sourceFile = File(nativeDir, BINARY_NAME)
            
            Log.d("ByeDPI", "Checking binary at: ${sourceFile.absolutePath}")
            
            // На Android 10+ (API 29+) запуск бинарников из папки данных (/files) запрещен.
            // Мы должны запускать его напрямую из папки нативных библиотек.
            val execFile = if (sourceFile.exists()) {
                Log.d("ByeDPI", "Using binary from native library dir: ${sourceFile.absolutePath}")
                sourceFile
            } else {
                Log.w("ByeDPI", "Binary not found in native libs, checking internal storage")
                internalFile
            }

            if (!execFile.exists()) {
                val error = "ByeDPI Critical: Binary not found!"
                Log.e("ByeDPI", error)
                LogManager.addLog(error)
                return
            }

            // Пытаемся установить права на исполнение, если их нет
            try { execFile.setExecutable(true, false) } catch (e: Exception) {
                Log.w("ByeDPI", "Could not set executable bit: ${e.message}")
            }

            val fullArgs = mutableListOf(execFile.absolutePath, "-p", localPort.toString())
            args.split(" ").filter { it.isNotBlank() }.forEach { fullArgs.add(it) }

            LogManager.addLog("ByeDPI: Executing...")
            
            val pb = ProcessBuilder(fullArgs)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            
            val proc = pb.start()
            process = proc
            
            Thread {
                Thread.sleep(1500)
                if (isAliveCompat(proc)) {
                    LogManager.addLog("ByeDPI: Engine is ONLINE")
                } else {
                    val exitCode = try { proc.exitValue() } catch(_: Exception) { -1 }
                    LogManager.addLog("ByeDPI: Stopped immediately (Code: $exitCode)")
                    // Если код 126/127 - это проблемы с правами или архитектурой
                    if (exitCode == 126 || exitCode == 127) {
                        LogManager.addLog("ByeDPI: Permission or Architecture issue")
                    }
                }
            }.start()
            
            Thread {
                try {
                    val scanner = Scanner(proc.inputStream)
                    while (scanner.hasNextLine()) {
                        val line = scanner.nextLine()
                        Log.d("ByeDPI-Core", line)
                        if (line.lowercase().contains("error") || line.contains("listening")) {
                            LogManager.addLog("ByeDPI: $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ByeDPI-Log", "Read error: ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            val error = "ByeDPI Error: ${e.message}"
            LogManager.addLog(error)
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
}
