package com.deader89.drivetool

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object ImageStorage {
    private const val PREFS_NAME = "DriveToolPrefs"
    private const val KEY_BASE_DIR = "base_directory"
    private const val KEY_STOP_ON_CLOSE = "stop_on_close"
    private const val KEY_WEBDAV_DIR = "webdav_directory"
    private const val KEY_STOP_WEBDAV_ON_CLOSE = "stop_webdav_on_close"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setBaseDir(context: Context, path: String) {
        getPrefs(context).edit().putString(KEY_BASE_DIR, path).apply()
    }

    fun getBaseDir(context: Context): String? {
        return getPrefs(context).getString(KEY_BASE_DIR, null)
    }

    fun setStopOnClose(context: Context, stop: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STOP_ON_CLOSE, stop).apply()
    }

    fun getStopOnClose(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STOP_ON_CLOSE, true)
    }

    fun setWebdavDir(context: Context, uri: String) {
        getPrefs(context).edit().putString(KEY_WEBDAV_DIR, uri).apply()
    }

    fun getWebdavDir(context: Context): String? {
        return getPrefs(context).getString(KEY_WEBDAV_DIR, null)
    }

    fun setStopWebdavOnClose(context: Context, stop: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STOP_WEBDAV_ON_CLOSE, stop).apply()
    }

    fun getStopWebdavOnClose(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STOP_WEBDAV_ON_CLOSE, true)
    }

    fun getImagesInBaseDir(context: Context): List<String> {
        val baseDir = getBaseDir(context) ?: return emptyList()
        Log.d("ImageStorage", "Scanning directory with root: $baseDir")
        
        val command = "ls -1 '$baseDir'"
        val result = RootUtils.execute(command)
        
        if (result.isFailure) {
            Log.e("ImageStorage", "Root ls failed: ${result.exceptionOrNull()?.message}")
            return emptyList()
        }

        val output = result.getOrNull() ?: ""
        val files = output.lines()
            .map { it.trim() }
            .filter { it.lowercase().endsWith(".iso") || it.lowercase().endsWith(".img") }
            .map { if (baseDir.endsWith("/")) "$baseDir$it" else "$baseDir/$it" }
            .sorted()

        Log.d("ImageStorage", "Found ${files.size} images via root")
        return files
    }
}
