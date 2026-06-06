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
        // Standard Pfade prüfen
        for (path in LUN_PATHS) {
            val result = RootUtils.execute("test -f $path")
            if (result.isSuccess) return path
        }

        // Suche via find als Fallback
        val findResult = RootUtils.execute("find /sys -name 'lun*' -path '*/f_mass_storage/*' 2>/dev/null | grep '/file' | head -n 1")
        val path = findResult.getOrNull()?.trim()
        if (!path.isNullOrEmpty()) return path

        // Allgemeine ConfigFS Suche
        val configFsResult = RootUtils.execute("find /config/usb_gadget -name 'file' -path '*/mass_storage.*/lun.*/file' 2>/dev/null | head -n 1")
        return configFsResult.getOrNull()?.trim()?.ifEmpty { null }
    }

    /**
     * Startet Hosting im Basis-Modus (Mass Storage Only).
     * Nutzt HidManager für das ConfigFS-Setup um Crashes zu vermeiden.
     */
    fun hostImage(imagePath: String, asReadonly: Boolean = true): Result<Unit> {
        Log.d(TAG, "Starting hostImage: $imagePath")
        
        // 1. Zuerst versuchen wir das spezialisierte ConfigFS Setup
        val configFsRes = HidManager.setupHostingNodes(imagePath, asReadonly)
        if (configFsRes.isSuccess) {
            Log.d(TAG, "Hosting started via custom ConfigFS gadget.")
            return Result.success(Unit)
        }

        // 2. Fallback: Altes System (android_usb oder g1)
        val lunPath = findLunPath() ?: return Result.failure(Exception("No LUN path found."))
        val lunDir = lunPath.substringBeforeLast("/")

        RootUtils.execute("setprop sys.usb.config none")
        Thread.sleep(200)

        // Flags
        val value = if (asReadonly) "1" else "0"
        RootUtils.execute("echo 1 > '$lunDir/removable' 2>/dev/null")
        RootUtils.execute("echo $value > '$lunDir/ro' 2>/dev/null")
        RootUtils.execute("echo $value > '$lunDir/cdrom' 2>/dev/null")

        // Image einhängen
        val mountResult = RootUtils.execute("echo '$imagePath' > '$lunPath'")
        if (mountResult.isFailure) {
            return Result.failure(Exception("Mount failed: ${mountResult.exceptionOrNull()?.message}"))
        }

        RootUtils.execute("setprop sys.usb.config mass_storage,adb")
        return Result.success(Unit)
    }

    fun stopHosting(): Result<Unit> {
        // Sicherer Teardown via HidManager
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
        if (RootUtils.execute(createCommand).isFailure) return Result.failure(Exception("Creation failed"))

        if (fs != null) {
            val bb = busyboxPath
            val formatCommand = if ((fs == FileSystem.FAT32) && (bb != null)) {
                "$bb mkfs.vfat -F 32 '$path' || ${fs.commandPrefix} '$path' || toybox mkfs.vfat -F 32 '$path'"
            } else {
                "${fs.commandPrefix} '$path'"
            }
            if (RootUtils.execute(formatCommand).isFailure) return Result.failure(Exception("Formatting failed"))
        }
        return Result.success(Unit)
    }

    fun getAppContext(): Context? = appContext
}
