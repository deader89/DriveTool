package com.deader89.drivetool

object HidManager {
    private const val HID_KEYBOARD = "/dev/hidg0"
    private const val HID_MOUSE = "/dev/hidg1"

    fun isKeyboardAvailable(): Boolean {
        return RootUtils.execute("test -c $HID_KEYBOARD").isSuccess
    }

    fun isMouseAvailable(): Boolean {
        return RootUtils.execute("test -c $HID_MOUSE").isSuccess
    }

    /**
     * Tries to initialize HID nodes via ConfigFS.
     * This requires root and a kernel with CONFIG_USB_CONFIGFS_F_HID enabled.
     */
    fun setupHidNodes(): Result<Unit> {
        val script = """
            # 1. Mount configfs if not mounted
            if [ ! -d /config ]; then mkdir /config; fi
            mount -t configfs none /config 2>/dev/null
            
            # 2. Setup the gadget
            GADGET_DIR=/config/usb_gadget/g_hid
            if [ ! -d ${'$'}GADGET_DIR ]; then
                mkdir -p ${'$'}GADGET_DIR
                echo 0x1d6b > ${'$'}GADGET_DIR/idVendor  # Linux Foundation
                echo 0x0104 > ${'$'}GADGET_DIR/idProduct # Multifunction Composite Gadget
                echo 0x0100 > ${'$'}GADGET_DIR/bcdDevice
                echo 0x0200 > ${'$'}GADGET_DIR/bcdUSB
                
                mkdir -p ${'$'}GADGET_DIR/strings/0x409
                echo "dead0001" > ${'$'}GADGET_DIR/strings/0x409/serialnumber
                echo "Deader89" > ${'$'}GADGET_DIR/strings/0x409/manufacturer
                echo "DriveTool HID" > ${'$'}GADGET_DIR/strings/0x409/product
                
                mkdir -p ${'$'}GADGET_DIR/configs/c.1/strings/0x409
                echo "Config 1: HID" > ${'$'}GADGET_DIR/configs/c.1/strings/0x409/configuration
                echo 250 > ${'$'}GADGET_DIR/configs/c.1/MaxPower
            fi

            # 3. Setup Keyboard function
            if [ ! -d ${'$'}GADGET_DIR/functions/hid.usb0 ]; then
                mkdir -p ${'$'}GADGET_DIR/functions/hid.usb0
                echo 1 > ${'$'}GADGET_DIR/functions/hid.usb0/protocol
                echo 1 > ${'$'}GADGET_DIR/functions/hid.usb0/subclass
                echo 8 > ${'$'}GADGET_DIR/functions/hid.usb0/report_length
                # Report descriptor for Keyboard
                echo -ne '\x05\x01\x09\x06\xa1\x01\x05\x07\x19\xe0\x29\xe7\x15\x00\x25\x01\x75\x01\x95\x08\x81\x02\x95\x01\x75\x08\x81\x03\x95\x05\x75\x01\x05\x08\x19\x01\x29\x05\x91\x02\x95\x01\x75\x03\x91\x03\x95\x06\x75\x08\x15\x00\x25\x65\x05\x07\x19\x00\x29\x65\x81\x00\xc0' > ${'$'}GADGET_DIR/functions/hid.usb0/report_desc
                ln -s ${'$'}GADGET_DIR/functions/hid.usb0 ${'$'}GADGET_DIR/configs/c.1/
            fi

            # 4. Setup Mouse function
            if [ ! -d ${'$'}GADGET_DIR/functions/hid.usb1 ]; then
                mkdir -p ${'$'}GADGET_DIR/functions/hid.usb1
                echo 2 > ${'$'}GADGET_DIR/functions/hid.usb1/protocol
                echo 1 > ${'$'}GADGET_DIR/functions/hid.usb1/subclass
                echo 4 > ${'$'}GADGET_DIR/functions/hid.usb1/report_length
                # Report descriptor for Mouse
                echo -ne '\x05\x01\x09\x02\xa1\x01\x09\x01\xa1\x00\x05\x09\x19\x01\x29\x03\x15\x00\x25\x01\x95\x03\x75\x01\x81\x02\x95\x01\x75\x05\x81\x03\x05\x01\x09\x30\x09\x31\x09\x38\x15\x81\x25\x7f\x75\x08\x95\x03\x81\x06\xc0\xc0' > ${'$'}GADGET_DIR/functions/hid.usb1/report_desc
                ln -s ${'$'}GADGET_DIR/functions/hid.usb1 ${'$'}GADGET_DIR/configs/c.1/
            fi

            # 5. Bind to controller
            UDC_DEV=$(ls /sys/class/udc | head -n 1)
            echo "" > ${'$'}GADGET_DIR/UDC
            echo ${'$'}UDC_DEV > ${'$'}GADGET_DIR/UDC
            
            # 6. Ensure permissions
            chmod 666 /dev/hidg0 2>/dev/null
            chmod 666 /dev/hidg1 2>/dev/null
            
            test -c /dev/hidg0 && test -c /dev/hidg1
        """.trimIndent()
        
        return RootUtils.execute(script).map { Unit }
    }

    fun sendKeyboardReport(modifiers: Byte, keyCodes: ByteArray): Result<Unit> {
        val bytes = ByteArray(8)
        bytes[0] = modifiers
        bytes[1] = 0
        for (i in 0 until minOf(6, keyCodes.size)) {
            bytes[i + 2] = keyCodes[i]
        }
        return writeToHid(HID_KEYBOARD, bytes)
    }

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
        return RootUtils.execute(command).map { }
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
