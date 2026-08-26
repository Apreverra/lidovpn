package com.lido.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStreamReader
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.Socket

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

            // Optimized monitoring: Check port availability instead of fixed sleep
            Thread {
                val start = System.currentTimeMillis()
                var ready = false
                while (System.currentTimeMillis() - start < 5000) {
                    if (!isAliveCompat(proc)) break
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(listenAddr, localPort), 200)
                            ready = true
                        }
                    } catch (_: Exception) {}
                    
                    if (ready) break
                    Thread.sleep(300)
                }

                if (ready) {
                    LogManager.addLog("ByeDPI: Engine is ONLINE (Port $localPort ready)")
                } else {
                    val exitCode = try { proc.exitValue() } catch(_: Exception) { -1 }
                    LogManager.addLog("ByeDPI: Engine Failed or Timeout (Code: $exitCode)")
                    if (isAliveCompat(proc)) stop()
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
