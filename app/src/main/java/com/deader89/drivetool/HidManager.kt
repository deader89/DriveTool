package com.deader89.drivetool

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object HidManager {
    private const val TAG = "HidManager"
    private const val HID_KEYBOARD = "/dev/hidg0"
    private const val HID_MOUSE = "/dev/hidg1"

    // Cache für die FileStreams, um teures Neuerstellen zu verhindern
    private var keyboardStream: FileOutputStream? = null
    private var mouseStream: FileOutputStream? = null

    fun isKeyboardAvailable(): Boolean = File(HID_KEYBOARD).exists() && File(HID_KEYBOARD).canWrite()
    fun isMouseAvailable(): Boolean = File(HID_MOUSE).exists() && File(HID_MOUSE).canWrite()

    /**
     * Lädt das hid-gadget Kernel-Modul und bereitet die Berechtigungen vor.
     * @param modulePath Der absolute Pfad zur .ko Datei (z.B. im App-Cache oder Assets)
     */
    fun setupHidNodes(modulePath: String? = null): Result<Unit> {
        val script = StringBuilder()

        // 1. Falls ein Pfad zum Modul übergeben wurde, versuchen wir es zu laden
        if (!modulePath.isNullOrEmpty()) {
            script.append("insmod $modulePath 2>/dev/null\n")
        }

        // 2. UDC Zuweisung zurücksetzen und neu triggern (erzwingt USB Re-Enumeration am PC)
        script.append("""
            UDC_DEV=$(ls /sys/class/udc | head -n 1)
            if [ ! -z "${'$'}UDC_DEV" ]; then
                # Trennen und neu verbinden falls über ConfigFS/Modul nötig
                echo "" > /sys/config/usb_gadget/g_hid/UDC 2>/dev/null
                echo "${'$'}UDC_DEV" > /sys/config/usb_gadget/g_hid/UDC 2>/dev/null
            fi
            
            # 3. Berechtigungen global freigeben, damit die App ohne Root-Overhead schreiben darf
            chmod 666 $HID_KEYBOARD 2>/dev/null
            chmod 666 $HID_MOUSE 2>/dev/null
            
            test -c $HID_KEYBOARD && test -c $HID_MOUSE
        """.trimIndent())

        val rootResult = RootUtils.execute(script.toString())
        if (rootResult.isFailure) {
            Log.e(TAG, "Root-Setup für HID-Nodes fehlgeschlagen")
            return Result.failure(rootResult.exceptionOrNull() ?: Exception("HID root setup failed"))
        }

        // 4. Streams direkt öffnen, um extrem schnelle Schreibzugriffe zu ermöglichen
        return try {
            closeStreams() // Alte Streams schließen falls vorhanden
            if (File(HID_KEYBOARD).exists()) keyboardStream = FileOutputStream(HID_KEYBOARD, true)
            if (File(HID_MOUSE).exists()) mouseStream = FileOutputStream(HID_MOUSE, true)

            Log.d(TAG, "HID-Streams erfolgreich geöffnet. Echtzeit-Eingabe bereit.")
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "Fehler beim Öffnen der Direct-I/O Streams", e)
            Result.failure(e)
        }
    }

    /**
     * Sendet einen Tastatur-Report (8 Bytes) in Echtzeit ohne Root-Shell-Overhead.
     */
    fun sendKeyboardReport(modifiers: Byte, keyCodes: ByteArray): Result<Unit> {
        val stream = keyboardStream ?: return Result.failure(IllegalStateException("Keyboard stream not initialized"))
        return try {
            val bytes = ByteArray(8)
            bytes[0] = modifiers
            bytes[1] = 0 // Reserviertes Byte
            for (i in 0 until minOf(6, keyCodes.size)) {
                bytes[i + 2] = keyCodes[i]
            }

            stream.write(bytes)
            stream.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing keyboard report", e)
            Result.failure(e)
        }
    }

    /**
     * Sendet einen Maus-Report (4 Bytes) in Echtzeit. Ideal für flüssige Bewegungen.
     */
    fun sendMouseReport(buttons: Byte, dx: Int, dy: Int, wheel: Int = 0): Result<Unit> {
        val stream = mouseStream ?: return Result.failure(IllegalStateException("Mouse stream not initialized"))
        return try {
            val bytes = ByteArray(4)
            bytes[0] = buttons
            bytes[1] = dx.coerceIn(-127, 127).toByte()
            bytes[2] = dy.coerceIn(-127, 127).toByte()
            bytes[3] = wheel.coerceIn(-127, 127).toByte()

            stream.write(bytes)
            stream.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing mouse report", e)
            Result.failure(e)
        }
    }

    fun releaseAllKeys(): Result<Unit> {
        return sendKeyboardReport(0, ByteArray(6))
    }

    fun typeKey(keyCode: Byte, modifiers: Byte = 0): Result<Unit> {
        val res = sendKeyboardReport(modifiers, byteArrayOf(keyCode))
        if (res.isFailure) return res

        // Kurzer Delay, damit der PC die Flanke registriert (10ms ist optimal)
        try { Thread.sleep(10) } catch (_: InterruptedException) {}

        return releaseAllKeys()
    }

    /**
     * Schließt die offenen Dateistreams sauber (z.B. beim Beenden der App).
     */
    fun closeStreams() {
        try {
            keyboardStream?.close()
            mouseStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing streams", e)
        } finally {
            keyboardStream = null
            mouseStream = null
        }
    }
}