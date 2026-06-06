package com.deader89.drivetool

import android.content.Context
import android.util.Log

object DriveManager {
    private const val TAG = "DriveManager"
    private var busyboxPath: String? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext

        val whichBb = RootUtils.execute("which busybox").getOrNull()?.trim()
        if (!whichBb.isNullOrEmpty()) {
            busyboxPath = whichBb
            Log.d(TAG, "Using Magisk Busybox at: $busyboxPath")
        } else {
            busyboxPath = "/system/xbin/busybox"
            Log.w(TAG, "Busybox not found via 'which', using fallback: $busyboxPath")
        }
    }

    private val LUN_PATHS = arrayOf(
        "/sys/class/android_usb/android0/f_mass_storage/lun/file",
        "/sys/class/android_usb/android0/f_mass_storage/lun0/file",
        "/sys/devices/virtual/android_usb/android0/f_mass_storage/lun0/file",
        "/sys/devices/virtual/android_usb/android0/f_mass_storage/lun/file",
        "/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file",
        "/sys/devices/platform/s3c-usbgadget/gadget/lun0/file",
        "/sys/devices/platform/musb_hdrc/gadget/lun0/file",
        "/sys/devices/platform/mt_usb/musb-hdrc.0.auto/gadget/lun0/file"
    )

    fun findLunPath(): String? {
        for (path in LUN_PATHS) {
            val result = RootUtils.execute("test -f $path")
            if (result.isSuccess) return path
        }

        val findResult = RootUtils.execute("find /sys -name 'lun*' -path '*/f_mass_storage/*' 2>/dev/null | grep '/file' | head -n 1")
        val path = findResult.getOrNull()?.trim()
        if (!path.isNullOrEmpty()) return path

        val configFsResult = RootUtils.execute("find /config/usb_gadget -name 'file' -path '*/mass_storage.*/lun.*/file' 2>/dev/null | head -n 1")
        return configFsResult.getOrNull()?.trim()?.ifEmpty { null }
    }

    fun hostImage(imagePath: String, asReadonly: Boolean): Result<Unit> {
        Log.d(TAG, "Starting hostImage: $imagePath")
        
        // 1. Zuerst ConfigFS Methode versuchen (Härtung)
        val configFsRes = HidManager.setupHostingNodes(imagePath, asReadonly)
        if (configFsRes.isSuccess) return Result.success(Unit)

        // 2. Fallback: Legacy SysFS / android_usb
        val lunPath = findLunPath() ?: return Result.failure(Exception("No LUN"))
        val lunDir = lunPath.substringBeforeLast("/")

        RootUtils.execute("setprop sys.usb.config none")
        Thread.sleep(100)

        val value = if (asReadonly) "1" else "0"
        RootUtils.execute("echo 1 > '$lunDir/removable' 2>/dev/null")
        RootUtils.execute("echo $value > '$lunDir/ro' 2>/dev/null")
        RootUtils.execute("echo $value > '$lunDir/cdrom' 2>/dev/null")

        val mountResult = RootUtils.execute("echo '$imagePath' > '$lunPath'")
        if (mountResult.isFailure) return Result.failure(Exception("Mount fail"))

        RootUtils.execute("setprop sys.usb.config mass_storage,adb")
        return Result.success(Unit)
    }

    fun stopHosting(): Result<Unit> {
        // 1. SysFS leeren (Fallback-Ebene)
        val lunPath = findLunPath()
        if (lunPath != null) {
            RootUtils.execute("echo '' > '$lunPath' 2>/dev/null")
        }

        // 2. Robuster Teardown via HidManager (Main-Ebene)
        return HidManager.teardown()
    }

    fun isHosting(): Boolean {
        val path = findLunPath() ?: return false
        return RootUtils.execute("cat '$path'").getOrNull()?.trim()?.isNotEmpty() == true
    }

    enum class FileSystem(val displayName: String, val commandPrefix: String) {
        FAT32("FAT32", "mkfs.vfat -F 32"),
        EXT4("EXT4", "mkfs.ext4 -F"),
        NTFS("NTFS", "mkfs.ntfs -F"),
        EXFAT("exFAT", "mkfs.exfat")
    }

    fun createEmptyIso(path: String, sizeGb: Int, fs: FileSystem? = null): Result<Unit> {
        val createCommand = "truncate -s ${sizeGb}G '$path' || dd if=/dev/zero of='$path' bs=1G count=0 seek=$sizeGb"
        if (RootUtils.execute(createCommand).isFailure) return Result.failure(Exception("Create fail"))

        if (fs != null) {
            val bb = busyboxPath
            val formatCommand = if ((fs == FileSystem.FAT32) && (bb != null)) {
                "$bb mkfs.vfat -F 32 '$path' || ${fs.commandPrefix} '$path' || toybox mkfs.vfat -F 32 '$path'"
            } else {
                "${fs.commandPrefix} '$path'"
            }
            if (RootUtils.execute(formatCommand).isFailure) return Result.failure(Exception("Format fail"))
        }
        return Result.success(Unit)
    }

    fun getAppContext(): Context? = appContext
}
