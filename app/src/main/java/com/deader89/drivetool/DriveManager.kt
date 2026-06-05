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
        "/sys/devices/platform/musb_hdrc/gadget/lun0/file"
    )

    fun findLunPath(): String? {
        for (path in LUN_PATHS) {
            val result = RootUtils.execute("test -e $path")
            if (result.isSuccess) return path
        }
        return null
    }

    fun hostImage(imagePath: String, asReadonly: Boolean = false): Result<Unit> {
        Log.d(TAG, "Hosting image: $imagePath")
        val lunPath = findLunPath() ?: return Result.failure(Exception("No LUN path found."))
        val lunDir = lunPath.substringBeforeLast("/")

        RootUtils.execute("setprop sys.usb.config adb")
        Thread.sleep(200)

        val flags = arrayOf("ro", "cdrom", "read_only", "removable")
        for (flag in flags) {
            val flagPath = "$lunDir/$flag"
            val value = if (flag == "removable") "1" else (if (asReadonly) "1" else "0")
            RootUtils.execute("[ -e '$flagPath' ] && echo $value > '$flagPath'")
        }

        RootUtils.execute("echo '' > '$lunPath'")
        val mountResult = RootUtils.execute("echo '$imagePath' > '$lunPath'")
        if (mountResult.isFailure) {
            return Result.failure(Exception("Mount failed: ${mountResult.exceptionOrNull()?.message}"))
        }

        val finalResult = RootUtils.execute("setprop sys.usb.config mass_storage,adb")
        if (finalResult.isFailure) {
            RootUtils.execute("setprop sys.usb.config mass_storage")
        }

        return Result.success(Unit)
    }

    fun stopHosting(): Result<Unit> {
        val lunPath = findLunPath() ?: return Result.failure(Exception("No LUN path found."))
        RootUtils.execute("echo '' > '$lunPath'")
        return RootUtils.execute("setprop sys.usb.config none && sleep 1 && setprop sys.usb.config mtp,adb").map { Unit }
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
            val formatCommand = if (fs == FileSystem.FAT32 && bb != null) {
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
