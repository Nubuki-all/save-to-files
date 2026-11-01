package dev.pah.saver

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SaveActivity : AppCompatActivity() {

    private var sourcePackage: String = "unknown_app"
    private var mediaType: String = "document"
    private val pendingUris = mutableListOf<Uri>()
    private var currentIndex = 0
    private var isWaitingForResult = false
    // Looking up the next item's name and type before the picker opens; not saved,
    // so a recreation in that window simply starts the lookup again.
    private var isPreparing = false
    private var warnedNotRemembered = false

    // Only the stock provider honors EXTRA_INITIAL_URI reliably.
    // External providers (e.g. MiXplorer) must implement path lookup themselves.
    private val rememberableAuthorities = setOf("com.android.externalstorage.documents")

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isWaitingForResult = false
        val dest = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || dest == null) {
            finishWithMessage(withProgress("Save cancelled"), Toast.LENGTH_SHORT)
            return@registerForActivityResult
        }
        if (currentIndex >= pendingUris.size) {
            finishAndRemoveTask()
            return@registerForActivityResult
        }

        val source = pendingUris[currentIndex]
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { copyContentToUri(source, dest) }
            if (bytes >= 0) {
                // Remember the destination folder only after a successful copy,
                // and only for providers that honor the initial-folder hint.
                if (dest.authority in rememberableAuthorities) {
                    parentFolderUri(dest)?.let {
                        SavePrefsHelper.saveRememberedFolder(
                            this@SaveActivity, sourcePackage, mediaType, it
                        )
                    }
                } else if (!warnedNotRemembered) {
                    warnedNotRemembered = true
                    Toast.makeText(
                        this@SaveActivity,
                        "Folder won't be remembered when saving via ${providerLabel(dest.authority)}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                deleteIfOwnCacheFile(source)
                currentIndex++
                processNextItem()
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        DocumentsContract.deleteDocument(contentResolver, dest)
                    } catch (_: Exception) {
                    }
                }
                finishWithMessage(withProgress("Failed to write file"), Toast.LENGTH_LONG)
            }
        }
    }

    /** Appends "(n of m saved)" when part of a multi-item batch already went through. */
    private fun withProgress(message: String): String =
        if (pendingUris.size > 1 && currentIndex > 0) {
            "$message ($currentIndex of ${pendingUris.size} saved)"
        } else {
            message
        }

    private fun finishWithMessage(message: String, duration: Int) {
        Toast.makeText(this, message, duration).show()
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            currentIndex = savedInstanceState.getInt("CURRENT_INDEX", 0)
            sourcePackage = savedInstanceState.getString("SOURCE_PACKAGE", "unknown_app")
            mediaType = savedInstanceState.getString("MEDIA_TYPE", "document")
            isWaitingForResult = savedInstanceState.getBoolean("WAITING_FOR_RESULT", false)
            pendingUris.clear()
            savedInstanceState.getStringArrayList("PENDING_URIS")?.forEach {
                pendingUris.add(Uri.parse(it))
            }
            // Recreated with no picker open (e.g. process death mid-copy):
            // resume instead of leaving an invisible activity over the screen.
            when {
                pendingUris.isEmpty() -> finishAndRemoveTask()
                !isWaitingForResult -> processNextItem()
            }
        } else {
            sourcePackage = extractCallingPackage()
            lifecycleScope.launch { startBatch() }
        }
    }

    override fun onDestroy() {
        // Shared-text cache files of a cancelled or failed batch
        if (isFinishing) pendingUris.forEach { deleteIfOwnCacheFile(it) }
        super.onDestroy()
    }

    private suspend fun startBatch() {
        // Shared text gets written to a cache file here, so keep it off the main thread
        val (uris, mimeType) = withContext(Dispatchers.IO) { extractIncomingUris(intent) }
        if (uris.isEmpty()) {
            finishWithMessage("No valid files found to save", Toast.LENGTH_SHORT)
            return
        }

        // The referrer is often the system chooser, which would make every
        // sending app share one key. Fall back to the URI authority instead.
        val genericCallers = setOf(
            "unknown_app",
            "android",
            "com.android.systemui",
            "com.android.intentresolver"
        )
        if (sourcePackage in genericCallers) {
            sourcePackage = uris.first().authority ?: "unknown_app"
        }

        pendingUris.clear()
        pendingUris.addAll(uris)
        currentIndex = 0
        mediaType = SavePrefsHelper.getMediaType(mimeType)
        Log.d("Saver", "sourceKey=$sourcePackage mediaType=$mediaType")

        processNextItem()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("CURRENT_INDEX", currentIndex)
        outState.putStringArrayList("PENDING_URIS", ArrayList(pendingUris.map { it.toString() }))
        outState.putString("SOURCE_PACKAGE", sourcePackage)
        outState.putString("MEDIA_TYPE", mediaType)
        outState.putBoolean("WAITING_FOR_RESULT", isWaitingForResult)
    }

    private fun extractCallingPackage(): String {
        val referrerUri: Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) referrer else null
        if (referrerUri != null && referrerUri.scheme == "android-app") {
            return referrerUri.authority ?: "unknown_app"
        }
        return intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
            ?: callingActivity?.packageName
            ?: "unknown_app"
    }

    private fun extractIncomingUris(intent: Intent): Pair<List<Uri>, String?> {
        val uris = mutableListOf<Uri>()
        var mimeType = intent.type

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    uris.add(uri)
                } else {
                    // Plain text shares (selected text, links) arrive in EXTRA_TEXT
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        val f = File(cacheDir, "shared_text_${System.currentTimeMillis()}.txt")
                        f.writeText(text)
                        uris.add(Uri.fromFile(f))
                        mimeType = "text/plain"
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list?.let { uris.addAll(it) }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
        }
        return Pair(uris, mimeType)
    }

    private fun processNextItem() {
        if (currentIndex >= pendingUris.size) {
            Toast.makeText(this, "All items saved successfully!", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        if (isWaitingForResult || isPreparing) return
        isPreparing = true

        val currentUri = pendingUris[currentIndex]
        lifecycleScope.launch {
            // Provider queries and the first prefs read can block, so do them off the main thread
            val saveIntent = withContext(Dispatchers.IO) { buildSaveIntent(currentUri) }
            isPreparing = false
            try {
                isWaitingForResult = true
                createDocumentLauncher.launch(saveIntent)
            } catch (e: Exception) {
                isWaitingForResult = false
                finishWithMessage(
                    withProgress("Error opening save dialog: ${e.message}"),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun buildSaveIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // The only file:// sources are this app's own shared-text cache files
            type = if (uri.scheme == "file") {
                "text/plain"
            } else {
                contentResolver.getType(uri) ?: "*/*"
            }
            putExtra(Intent.EXTRA_TITLE, getFileName(uri))

            val rememberedFolder =
                SavePrefsHelper.getRememberedFolder(this@SaveActivity, sourcePackage, mediaType)
            if (rememberedFolder != null
                && rememberedFolder.authority in rememberableAuthorities
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, rememberedFolder)
            }
        }

    /** Deletes [uri] only if it is one of this app's shared-text cache files. */
    private fun deleteIfOwnCacheFile(uri: Uri) {
        if (uri.scheme != "file") return
        uri.path?.let { File(it).canonicalFile }
            ?.takeIf { it.parentFile == cacheDir.canonicalFile }
            ?.delete()
    }

    private fun getFileName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "saved_file"

        var name = "saved_file"
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex) ?: "saved_file"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Saver", "getFileName failed for $uri", e)
        }
        return name
    }

    /** Human-readable name of the app that owns a document provider authority. */
    private fun providerLabel(authority: String?): String {
        if (authority == null) return "this location"
        return try {
            packageManager.resolveContentProvider(authority, 0)
                ?.applicationInfo?.loadLabel(packageManager)?.toString() ?: authority
        } catch (e: Exception) {
            authority
        }
    }

    private fun parentFolderUri(fileUri: Uri): Uri? {
        val authority = fileUri.authority ?: return null
        val id = try {
            DocumentsContract.getDocumentId(fileUri)
        } catch (e: Exception) {
            Log.e("Saver", "getDocumentId failed for $fileUri", e)
            return null
        }

        val parentId = when {
            '/' in id -> id.substringBeforeLast('/')      // "primary:Music/Sub/a.mp3" -> "primary:Music/Sub"
            ':' in id -> id.substringBefore(':') + ":"    // "primary:a.mp3" -> "primary:"
            else -> return null
        }
        Log.d("Saver", "parentId=$parentId")
        return DocumentsContract.buildDocumentUri(authority, parentId)
    }

    /** Copies source to destination. Returns bytes copied, or -1 on failure. */
    private fun copyContentToUri(source: Uri, destination: Uri): Long {
        return try {
            val input = contentResolver.openInputStream(source)
                ?: run {
                    Log.e("Saver", "openInputStream null for $source")
                    return -1
                }
            input.use { i ->
                // The document was just created empty, so plain "w" is enough;
                // some providers reject "wt".
                val output = contentResolver.openOutputStream(destination, "w")
                    ?: run {
                        Log.e("Saver", "openOutputStream null for $destination")
                        return -1
                    }
                output.use { o -> i.copyTo(o) }
            }
        } catch (e: Exception) {
            Log.e("Saver", "Copy failed: $source -> $destination", e)
            -1
        }
    }
}
