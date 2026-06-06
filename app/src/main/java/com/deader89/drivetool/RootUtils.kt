package com.deader89.drivetool

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Utility for executing root commands.
 * Root is exclusively used for kernel-level USB Mass Storage (UMS) operations 
 * and raw block device formatting that standard Android APIs cannot perform.
 */
object RootUtils {

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            (output != null) && output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    fun execute(command: String, timeoutMs: Long = 5000): Result<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            val outThread = Thread { try { process.inputStream.bufferedReader().use { output.append(it.readText()) } } catch (e: Exception) {} }
            val errThread = Thread { try { process.errorStream.bufferedReader().use { error.append(it.readText()) } } catch (e: Exception) {} }
            
            outThread.start()
            errThread.start()
            
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            
            if (!finished) {
                process.destroy()
                return Result.failure(Exception("Command timed out: $command"))
            }
            
            outThread.join(500)
            errThread.join(500)

            if (process.exitValue() == 0) {
                Result.success(output.toString())
            } else {
                Result.failure(Exception(error.toString().ifEmpty { "Exit code ${process.exitValue()}" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress
                            if (ip != null && !ip.startsWith("169.254")) return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore and fallback
        }
        
        return getIpAddressRoot()
    }

    private fun getIpAddressRoot(): String {
        try {
            val result = execute("ip addr | grep 'inet ' | grep -v '127.0.0.1'", timeoutMs = 1500)
            if (result.isSuccess) {
                val output = result.getOrNull() ?: ""
                val regex = "inet\\s+(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})".toRegex()
                return regex.find(output)?.groupValues?.get(1) ?: "Unknown"
            }
        } catch (e: Exception) {}

        return "Unknown"
    }
}
