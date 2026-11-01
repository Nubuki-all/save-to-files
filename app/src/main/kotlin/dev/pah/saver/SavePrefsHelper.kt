package dev.pah.saver

import android.content.Context
import android.net.Uri

object SavePrefsHelper {
    private const val PREF_NAME = "app_save_folder_prefs_v2"

    fun getMediaType(mimeType: String?): String {
        return when {
            mimeType == null -> "document"
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            mimeType.startsWith("audio/") -> "audio"
            else -> "document"
        }
    }

    fun saveRememberedFolder(context: Context, packageName: String, mediaType: String, folderUri: Uri) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("${packageName}_$mediaType", folderUri.toString()).apply()
    }

    fun getRememberedFolder(context: Context, packageName: String, mediaType: String): Uri? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString("${packageName}_$mediaType", null)
        return uriString?.let { Uri.parse(it) }
    }
}
