package com.deader89.drivetool

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay

object HidManager {
    private const val TAG = "HidManager"
    private const val HID_KEYBOARD = "/dev/hidg0"
    private const val HID_MOUSE = "/dev/hidg1"
    private const val CONFIGFS_HOME = "/config/usb_gadget/g_drivetool"
    private const val DUMMY_IMG = "/data/local/tmp/drivetool_dummy.img"

    // Cache für die FileStreams
    private var keyboardStream: FileOutputStream? = null
    private var mouseStream: FileOutputStream? = null

    fun isKeyboardAvailable(): Boolean = File(HID_KEYBOARD).exists()
    fun isMouseAvailable(): Boolean = File(HID_MOUSE).exists()
    fun isKeyboardWritable(): Boolean = File(HID_KEYBOARD).canWrite()
    fun isMouseWritable(): Boolean = File(HID_MOUSE).canWrite()

    fun isActive(): Boolean = isKeyboardAvailable() && isKeyboardWritable()

    /**
     * Erstellt HID-Gadgets via ConfigFS (Exklusiv-Modus).
     */
    fun setupHidNodes(modulePath: String? = null): Result<Unit> {
        val script = StringBuilder()

        // 1. Module laden
        script.append("modprobe libcomposite 2>/dev/null\n")
        script.append("modprobe usb_f_hid 2>/dev/null\n")
        if (!modulePath.isNullOrEmpty()) {
            script.append("insmod $modulePath 2>/dev/null\n")
        }

        // 2. ConfigFS Setup
        script.append("""
            # BusyBox Pfad priorisieren
            export PATH=/data/adb/magisk:/data/adb/busybox:/system/xbin:/sbin:${'$'}PATH
            
            # Android USB-Funktionen stoppen
            setprop sys.usb.config none
            
            # Falls bereits existiert, erst abbauen um Fehler zu vermeiden
            if [ -d $CONFIGFS_HOME ]; then
                echo "" > $CONFIGFS_HOME/UDC 2>/dev/null
                echo "" > $CONFIGFS_HOME/functions/mass_storage.0/lun.0/file 2>/dev/null
                sleep 0.1
                # Rekursiver "Inside-Out" Teardown
                find $CONFIGFS_HOME -mindepth 1 -depth -type l -exec rm -f {} + 2>/dev/null
                find $CONFIGFS_HOME -mindepth 1 -depth -type d -exec rmdir {} + 2>/dev/null
            fi
            
            # Standard-Android Gadgets aggressiv lösen
            if [ -e /config/usb_gadget/g1/UDC ]; then
                echo "" > /config/usb_gadget/g1/UDC 2>/dev/null
            fi
            if [ -e /config/usb_gadget/g2/UDC ]; then
                echo "" > /config/usb_gadget/g2/UDC 2>/dev/null
            fi
            
            sleep 0.1

            mkdir -p $CONFIGFS_HOME || exit 1
            cd $CONFIGFS_HOME
            
            echo 0x1d6b > idVendor
            echo 0x0104 > idProduct
            echo 0x0100 > bcdDevice
            echo 0x0200 > bcdUSB
            
            mkdir -p strings/0x409
            echo "deadbeaf" > strings/0x409/serialnumber
            echo "Deader89" > strings/0x409/manufacturer
            echo "DriveTool HID" > strings/0x409/product
            
            mkdir -p configs/c.1/strings/0x409
            echo "Config 1: HID Only" > configs/c.1/strings/0x409/configuration
            echo 250 > configs/c.1/bmAttributes
            
            # Tastatur
            mkdir -p functions/hid.usb0
            echo 1 > functions/hid.usb0/protocol
            echo 1 > functions/hid.usb0/subclass
            echo 8 > functions/hid.usb0/report_length
            echo -ne \\x05\\x01\\x09\\x06\\xa1\\x01\\x05\\x07\\x19\\xe0\\x29\\xe7\\x15\\x00\\x25\\x01\\x75\\x01\\x95\\x08\\x81\\x02\\x95\\x01\\x75\\x08\\x81\\x03\\x95\\x05\\x75\\x01\\x05\\x08\\x19\\x01\\x29\\x05\\x91\\x02\\x95\\x01\\x75\\x03\\x91\\x03\\x95\\x06\\x75\\x08\\x15\\x00\\x25\\x65\\x05\\x07\\x19\\x00\\x29\\x65\\x81\\x00\\xc0 > functions/hid.usb0/report_desc
            ln -s functions/hid.usb0 configs/c.1/
            
            # Maus
            mkdir -p functions/hid.usb1
            echo 2 > functions/hid.usb1/protocol
            echo 1 > functions/hid.usb1/subclass
            echo 4 > functions/hid.usb1/report_length
            echo -ne \\x05\\x01\\x09\\x02\\xa1\\x01\\x09\\x01\\xa1\\x00\\x05\\x09\\x19\\x01\\x29\\x03\\x15\\x00\\x25\\x01\\x75\\x01\\x95\\x03\\x81\\x02\\x95\\x01\\x75\\x05\\x81\\x03\\x05\\x01\\x09\\x30\\x09\\x31\\x09\\x38\\x15\\x81\\x25\\x7f\\x75\\x08\\x95\\x03\\x81\\x06\\xc0\\xc0 > functions/hid.usb1/report_desc
            ln -s functions/hid.usb1 configs/c.1/
            
            # UDC binden (Dynamische Ermittlung des Controllers)
            UDC_DEV=$(ls /sys/class/udc | head -n 1)
            if [ ! -z "${'$'}UDC_DEV" ]; then
                echo "${'$'}UDC_DEV" > UDC 2>&1
                if [ "$(cat UDC)" != "${'$'}UDC_DEV" ]; then
                    echo "UDC Bind Error" 2>&1
                fi
            fi
            
            chmod 666 $HID_KEYBOARD $HID_MOUSE 2>/dev/null
            magiskpolicy --live "allow untrusted_app uhid_device chr_file { getattr open read write ioctl }" 2>/dev/null
            chcon u:object_r:uhid_device:s0 $HID_KEYBOARD $HID_MOUSE 2>/dev/null
            
            [ -c $HID_KEYBOARD ] || [ -c $HID_MOUSE ]
        """.trimIndent())

        val rootResult = RootUtils.execute(script.toString())
        if (rootResult.isFailure) {
            val msg = rootResult.exceptionOrNull()?.message ?: "Unknown error"
            Log.e(TAG, "Root-Setup für HID-Nodes fehlgeschlagen: $msg")
            return Result.failure(rootResult.exceptionOrNull() ?: Exception("HID root setup failed: $msg"))
        }

        return try {
            closeStreams()
            if (File(HID_KEYBOARD).exists()) keyboardStream = FileOutputStream(File(HID_KEYBOARD))
            if (File(HID_MOUSE).exists()) mouseStream = FileOutputStream(File(HID_MOUSE))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Erstellt ein reines Mass-Storage Gadget via ConfigFS.
     */
    fun setupHostingNodes(imagePath: String, asReadonly: Boolean = true): Result<Unit> {
        val script = StringBuilder()
        script.append("modprobe libcomposite 2>/dev/null\n")
        script.append("touch $DUMMY_IMG\n")
        
        script.append("""
            export PATH=/data/adb/magisk:/data/adb/busybox:/system/xbin:/sbin:${'$'}PATH
            setprop sys.usb.config none
            
            if [ -d $CONFIGFS_HOME ]; then
                echo "" > $CONFIGFS_HOME/UDC 2>/dev/null
                echo "" > $CONFIGFS_HOME/functions/mass_storage.0/lun.0/file 2>/dev/null
                sleep 0.1
                find $CONFIGFS_HOME -mindepth 1 -depth -type l -exec rm -f {} + 2>/dev/null
                find $CONFIGFS_HOME -mindepth 1 -depth -type d -exec rmdir {} + 2>/dev/null
            fi
            
            if [ -e /config/usb_gadget/g1/UDC ] && [ ! -z "$(cat /config/usb_gadget/g1/UDC 2>/dev/null)" ]; then
                echo "" > /config/usb_gadget/g1/UDC 2>/dev/null
            fi
            
            sleep 0.1
            mkdir -p $CONFIGFS_HOME/functions/mass_storage.0/lun.0
            cd $CONFIGFS_HOME
            
            echo 0x1d6b > idVendor
            echo 0x0104 > idProduct
            mkdir -p strings/0x409
            echo "deadbeaf" > strings/0x409/serialnumber
            echo "Deader89" > strings/0x409/manufacturer
            echo "DriveTool Storage" > strings/0x409/product
            
            mkdir -p configs/c.1/strings/0x409
            echo "Config 1: Storage" > configs/c.1/strings/0x409/configuration
            
            echo 1 > functions/mass_storage.0/lun.0/removable
            echo ${if (asReadonly) "1" else "0"} > functions/mass_storage.0/lun.0/ro
            echo ${if (asReadonly) "1" else "0"} > functions/mass_storage.0/lun.0/cdrom
            echo 1 > functions/mass_storage.0/lun.0/nofua
            echo "$imagePath" > functions/mass_storage.0/lun.0/file
            
            ln -s functions/mass_storage.0 configs/c.1/
            
            UDC_DEV=$(ls /sys/class/udc | head -n 1)
            [ ! -z "${'$'}UDC_DEV" ] && echo "${'$'}UDC_DEV" > UDC
            
            magiskpolicy --live "allow untrusted_app device chr_file { getattr open read write ioctl }" 2>/dev/null
            magiskpolicy --live "allow system_app media_rw_data_file file { read open }" 2>/dev/null
            magiskpolicy --live "allow kernel media_rw_data_file file { read open }" 2>/dev/null
            
            [ -f functions/mass_storage.0/lun.0/file ]
        """.trimIndent())
        
        return RootUtils.execute(script.toString()).map { }
    }

    fun mountImage(path: String): Result<Unit> {
        val cmd = "echo '$path' > $CONFIGFS_HOME/functions/mass_storage.0/lun.0/file"
        return RootUtils.execute(cmd).map { }
    }

    fun unmountImage(): Result<Unit> {
        val cmd = "echo '' > $CONFIGFS_HOME/functions/mass_storage.0/lun.0/file"
        return RootUtils.execute(cmd).map { }
    }

    fun sendKeyboardReport(modifiers: Byte, keyCodes: ByteArray): Result<Unit> {
        val stream = keyboardStream ?: return Result.failure(IllegalStateException("Not initialized"))
        return try {
            val bytes = ByteArray(8)
            bytes[0] = modifiers
            for (i in 0 until minOf(6, keyCodes.size)) {
                bytes[i + 2] = keyCodes[i]
            }
            stream.write(bytes)
            stream.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun sendMouseReport(buttons: Byte, dx: Int, dy: Int, wheel: Int = 0): Result<Unit> {
        val stream = mouseStream ?: return Result.failure(IllegalStateException("Not initialized"))
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
            Result.failure(e)
        }
    }

    fun releaseAllKeys(): Result<Unit> {
        return sendKeyboardReport(0, ByteArray(6))
    }

    fun typeKey(keyCode: Byte, modifiers: Byte = 0): Result<Unit> {
        val res = sendKeyboardReport(modifiers, byteArrayOf(keyCode))
        if (res.isFailure) return res
        try { Thread.sleep(10) } catch (_: InterruptedException) {}
        return releaseAllKeys()
    }

    fun typeChar(c: Char): Result<Unit> {
        val mapping = charToHid(c) ?: return Result.failure(IllegalArgumentException("Unsupported char: $c"))
        return typeKey(mapping.first, if (mapping.second) 0x02.toByte() else 0)
    }

    private fun charToHid(c: Char): Pair<Byte, Boolean>? {
        return when (c) {
            in 'a'..'z' -> (0x04 + (c - 'a')).toByte() to false
            in 'A'..'Z' -> (0x04 + (c - 'A')).toByte() to true
            in '1'..'9' -> (0x1E + (c - '1')).toByte() to false
            '0' -> 0x27.toByte() to false
            ' ' -> 0x2C.toByte() to false
            '\n', '\r' -> 0x28.toByte() to false
            '\t' -> 0x2B.toByte() to false
            '!' -> 0x1E.toByte() to true
            '@' -> 0x1F.toByte() to true
            '#' -> 0x20.toByte() to true
            '$' -> 0x21.toByte() to true
            '%' -> 0x22.toByte() to true
            '^' -> 0x23.toByte() to true
            '&' -> 0x24.toByte() to true
            '*' -> 0x25.toByte() to true
            '(' -> 0x26.toByte() to true
            ')' -> 0x27.toByte() to true
            '-' -> 0x2D.toByte() to false
            '_' -> 0x2D.toByte() to true
            '=' -> 0x2E.toByte() to false
            '+' -> 0x2E.toByte() to true
            '[' -> 0x2F.toByte() to false
            '{' -> 0x2F.toByte() to true
            ']' -> 0x30.toByte() to false
            '}' -> 0x30.toByte() to true
            '\\' -> 0x31.toByte() to false
            '|' -> 0x31.toByte() to true
            ';' -> 0x33.toByte() to false
            ':' -> 0x33.toByte() to true
            '\'' -> 0x34.toByte() to false
            '"' -> 0x34.toByte() to true
            ',' -> 0x36.toByte() to false
            '<' -> 0x36.toByte() to true
            '.' -> 0x37.toByte() to false
            '>' -> 0x37.toByte() to true
            '/' -> 0x38.toByte() to false
            '?' -> 0x38.toByte() to true
            else -> null
        }
    }

    fun closeStreams() {
        try {
            keyboardStream?.close()
            mouseStream?.close()
        } catch (_: Exception) {
            // Silence close errors
        } finally {
            keyboardStream = null
            mouseStream = null
        }
    }

    fun teardown(): Result<Unit> {
        closeStreams()
        val script = """
            CONFIGFS_HOME=$CONFIGFS_HOME
            setprop sys.usb.config none
            setprop sys.usb.configfs 1
            start adbd 2>/dev/null
            sleep 0.1
            
            if [ -d ${'$'}CONFIGFS_HOME ]; then
                echo "" > ${'$'}CONFIGFS_HOME/UDC 2>/dev/null
                echo "" > ${'$'}CONFIGFS_HOME/functions/mass_storage.0/lun.0/file 2>/dev/null
                sleep 0.1
                # Rekursiver "Inside-Out" Teardown
                find ${'$'}CONFIGFS_HOME -mindepth 1 -depth -type l -exec rm -f {} + 2>/dev/null
                find ${'$'}CONFIGFS_HOME -mindepth 1 -depth -type d -exec rmdir {} + 2>/dev/null
                rmdir ${'$'}CONFIGFS_HOME 2>/dev/null
            fi
            
            # Symmetrischer Reset des System-Gadgets (g1)
            if [ -e /config/usb_gadget/g1/UDC ] && [ -z "$(cat /config/usb_gadget/g1/UDC 2>/dev/null)" ]; then
                UDC_DEV=$(ls /sys/class/udc | head -n 1)
                [ ! -z "${'$'}UDC_DEV" ] && echo "${'$'}UDC_DEV" > /config/usb_gadget/g1/UDC 2>/dev/null
            fi
            
            sleep 0.2
            setprop sys.usb.config mtp,adb
        """.trimIndent()
        return RootUtils.execute(script).map { }
    }
}
