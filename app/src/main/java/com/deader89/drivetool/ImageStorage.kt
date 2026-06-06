package com.deader89.drivetool

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

object ImageStorage {
    private const val PREFS_NAME = "DriveToolPrefs"
    private const val KEY_BASE_DIR = "base_directory"
    private const val KEY_BASE_URI = "base_uri"
    private const val KEY_STOP_ON_CLOSE = "stop_on_close"
    private const val KEY_WEBDAV_DIR = "webdav_directory"
    private const val KEY_STOP_WEBDAV_ON_CLOSE = "stop_webdav_on_close"
    private const val KEY_WEBDAV_USER = "webdav_username"
    private const val KEY_WEBDAV_PASS = "webdav_password"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private inline fun SharedPreferences.update(action: SharedPreferences.Editor.() -> Unit) {
        val editor = edit()
        action(editor)
        editor.apply()
    }

    fun setBaseDir(context: Context, path: String, uri: String) {
        getPrefs(context).update {
            putString(KEY_BASE_DIR, path)
            putString(KEY_BASE_URI, uri)
        }
    }

    fun getBaseDir(context: Context): String? {
        return getPrefs(context).getString(KEY_BASE_DIR, null)
    }

    fun getBaseUri(context: Context): String? {
        return getPrefs(context).getString(KEY_BASE_URI, null)
    }

    fun setStopOnClose(context: Context, stop: Boolean) {
        getPrefs(context).update { putBoolean(KEY_STOP_ON_CLOSE, stop) }
    }

    fun getStopOnClose(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STOP_ON_CLOSE, true)
    }

    fun setWebdavDir(context: Context, uri: String) {
        getPrefs(context).update { putString(KEY_WEBDAV_DIR, uri) }
    }

    fun getWebdavDir(context: Context): String? {
        return getPrefs(context).getString(KEY_WEBDAV_DIR, null)
    }

    fun setStopWebdavOnClose(context: Context, stop: Boolean) {
        getPrefs(context).update { putBoolean(KEY_STOP_WEBDAV_ON_CLOSE, stop) }
    }

    fun getStopWebdavOnClose(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STOP_WEBDAV_ON_CLOSE, true)
    }

    fun setWebdavAuth(context: Context, user: String, pass: String) {
        getPrefs(context).update {
            putString(KEY_WEBDAV_USER, user)
            putString(KEY_WEBDAV_PASS, pass)
        }
    }

    fun getWebdavUser(context: Context): String {
        return getPrefs(context).getString(KEY_WEBDAV_USER, "admin") ?: "admin"
    }

    fun getWebdavPass(context: Context): String {
        return getPrefs(context).getString(KEY_WEBDAV_PASS, "admin") ?: "admin"
    }

    fun getImagesInBaseDir(context: Context): List<String> {
        val baseUri = getBaseUri(context) ?: return emptyList()
        val baseDir = getBaseDir(context) ?: ""
        
        Log.d("ImageStorage", "Scanning directory via SAF: $baseUri")
        
        val treeUri = Uri.parse(baseUri)
        if (treeUri.scheme != "content") {
            Log.e("ImageStorage", "Base URI is not a content URI: $baseUri")
            return emptyList()
        }
        
        val rootDoc = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            Log.e("ImageStorage", "Failed to get DocumentFile from tree URI", e)
            null
        } ?: return emptyList()
        
        return rootDoc.listFiles()
            .asSequence()
            .filter { it.isFile && ((it.name?.lowercase()?.endsWith(".iso") == true) || (it.name?.lowercase()?.endsWith(".img") == true)) }
            .map { if (baseDir.endsWith("/")) "$baseDir${it.name}" else "$baseDir/${it.name}" }
            .sorted()
            .toList()
    }
}
