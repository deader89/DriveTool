package com.deader89.drivetool

import android.util.Log

object HidManager {
    private const val TAG = "HidManager"
    private const val HID_KEYBOARD = "/dev/hidg0"
    private const val HID_MOUSE = "/dev/hidg1"

    fun isKeyboardAvailable(): Boolean {
        return RootUtils.execute("test -c $HID_KEYBOARD").isSuccess
    }

    fun isMouseAvailable(): Boolean {
        return RootUtils.execute("test -c $HID_MOUSE").isSuccess
    }

    /**
     * Sends a keyboard report.
     * Report format (8 bytes):
     * Byte 0: Modifiers (Shift, Ctrl, etc.)
     * Byte 1: Reserved
     * Byte 2-7: Key codes
     */
    fun sendKeyboardReport(modifiers: Byte, keyCodes: ByteArray): Result<Unit> {
        val bytes = ByteArray(8)
        bytes[0] = modifiers
        bytes[1] = 0
        for (i in 0 until minOf(6, keyCodes.size)) {
            bytes[i + 2] = keyCodes[i]
        }
        return writeToHid(HID_KEYBOARD, bytes)
    }

    /**
     * Sends a mouse report.
     * Report format (4 bytes):
     * Byte 0: Buttons (Bit 0: Left, Bit 1: Right, Bit 2: Middle)
     * Byte 1: X movement (-127 to 127)
     * Byte 2: Y movement (-127 to 127)
     * Byte 3: Wheel (-127 to 127)
     */
    fun sendMouseReport(buttons: Byte, dx: Int, dy: Int, wheel: Int = 0): Result<Unit> {
        val bytes = ByteArray(4)
        bytes[0] = buttons
        bytes[1] = dx.coerceIn(-127, 127).toByte()
        bytes[2] = dy.coerceIn(-127, 127).toByte()
        bytes[3] = wheel.coerceIn(-127, 127).toByte()
        return writeToHid(HID_MOUSE, bytes)
    }

    private fun writeToHid(device: String, bytes: ByteArray): Result<Unit> {
        val hexString = bytes.joinToString("") { String.format("\\x%02x", it) }
        val command = "echo -ne '$hexString' > $device"
        return RootUtils.execute(command).map { Unit }
    }

    fun releaseAllKeys(): Result<Unit> {
        return sendKeyboardReport(0, ByteArray(6))
    }

    fun typeKey(keyCode: Byte, modifiers: Byte = 0): Result<Unit> {
        val res = sendKeyboardReport(modifiers, byteArrayOf(keyCode))
        if (res.isFailure) return res
        Thread.sleep(10)
        return releaseAllKeys()
    }
}
