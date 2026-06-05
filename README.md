# DriveTool 🛠️📱

DriveTool is a powerful Android utility that turns your rooted smartphone into a versatile USB gadget and network file server. It combines USB Mass Storage (UMS) hosting with a high-performance WebDAV server, making it the perfect tool for IT professionals and enthusiasts.

## Features ✨

### 💾 USB Hosting (Root required)
- **ISO/IMG Hosting**: Mount disk images directly to your PC via USB. Your phone acts like a real USB thumb drive or CD-ROM.
- **CD-ROM Mode**: Emulate an optical drive (Read-Only) for OS installations.
- **USB-RW Mode**: Emulate a standard writable USB stick.
- **Custom ISO Creation**: Create empty disk images (FAT32, EXT4, NTFS, exFAT) directly on your phone with sizes up to **32GB**.

### 🌐 Network Share (WebDAV)
- **High Performance**: Optimized for Windows 11 compatibility with bulk metadata fetching.
- **Background Support**: Runs as a Foreground Service with WakeLocks and WifiLocks to ensure stability even when the screen is off.
- **Interactive Notification**: Control the server status directly from the Android notification shade.
- **Zero Configuration**: No login required for quick guest access in your local network.

### 📥 OS Downloads
- **Curated Links**: Quick access to official download pages for:
  - Windows 11 & Ghost Spectre
  - Popular Linux Distros (Ubuntu, Debian, Kali, Fedora, Arch, Mint)
  - NAS & VM OS (OpenMediaVault, TrueNAS, Proxmox)
  - Gaming OS (RetroPie, Lakka)

## Prerequisites 🔑
- **Root Access**: Required for USB LUN manipulation and raw disk access.
- **Kernel Support**: Your device's kernel must support **USB Mass Storage (UMS)**.
  - Most older kernels use `/sys/class/android_usb/android0/f_mass_storage/lun/file`.
  - Modern kernels (Android 10+) usually require **ConfigFS** support (`/config/usb_gadget/`).
  - Required kernel configs: `CONFIG_USB_G_ANDROID`, `CONFIG_USB_CONFIGFS_F_MASS_STORAGE`, or `CONFIG_USB_MASS_STORAGE`.
- **Magisk/Busybox**: Recommended for full filesystem formatting support (mkfs.vfat, etc.).
- **Android 10+**: Optimized for modern Android versions and Scoped Storage.

## Installation 🚀
1. Clone the repository.
2. Open in Android Studio.
3. Build and install the APK on your rooted device.
4. Grant Root permissions when prompted.

## Usage 📖
1. **Hosting**: Select an ISO/IMG file from your storage and click "Start Hosting". Connect your phone to a PC.
2. **Network**: Go to the "Network" tab, select a folder, and click "Start Share". Map the provided IP as a network drive on your PC.
3. **Settings**: Customize background behavior and select your primary ISO directory.

## Technical Details 🛠️
- **Backend**: Built with Kotlin and Ktor (CIO engine).
- **UI**: Modern Jetpack Compose interface.
- **System**: Uses Android's `/sys/class/android_usb` LUN interface for hardware emulation.

---
*Developed for IT professionals who need a swiss-army-knife for disk management on the go.*

## Developer Notes & Disclaimer ⚠️
- **Safety First**: This tool requires root access and manipulates system LUN files. While tested on several devices, use it at your own risk. The developer is not responsible for data loss or hardware damage.
- **USB Config**: The app changes the `sys.usb.config` property. If your USB connection drops, the app attempts to restore it to `mtp,adb` upon stopping the host.
- **Contributions**: Contributions are welcome! If you find a new LUN path for a specific device, please open a Pull Request or Issue to include it in `DriveManager.kt`.

## License 📄
This project is licensed under the **GNU General Public License v3 (GPL v3)**.

The GPL v3 ensures that the software remains free and open-source, requiring any derivative works to also be licensed under the same terms. This protects your freedom to share and change all versions of the program--to make sure it remains free software for all its users.
