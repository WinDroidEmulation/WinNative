package com.winlator.cmod.google

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.games.PlayGames
import com.google.android.gms.tasks.Tasks
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.epic.service.EpicCloudSavesManager
import com.winlator.cmod.gog.service.GOGService
import com.winlator.cmod.steam.service.SteamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/**
 * Manages backup and restore of individual game cloud saves to/from Google Drive.
 *
 * Files are stored in a "WinNative" folder on the user's Google Drive as zip files.
 *
 * Flow:
 *   Backup:  Download cloud save from provider (Steam/Epic/GOG) → zip → upload to Google Drive
 *   Restore: Download from Google Drive → unzip → upload back to provider
 */
object GameSaveBackupManager {

    private const val TAG = "GameSaveBackup"
    private const val DRIVE_FOLDER_NAME = "WinNative"
    private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val PREFS_NAME = "google_store_login_sync"
    private const val KEY_GOOGLE_SYNC_ENABLED = "google_sync_enabled"
    private const val AUTH_SESSION_RETRY_COUNT = 5
    private const val AUTH_SESSION_RETRY_DELAY_MS = 750L

    const val REQUEST_CODE_DRIVE_AUTH = 9002

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    enum class GameSource { STEAM, EPIC, GOG }

    data class BackupResult(
        val success: Boolean,
        val message: String,
    )

    // Pending operation to resume after Drive consent
    private var pendingOperation: (suspend (String) -> BackupResult)? = null
    private var pendingCallback: ((BackupResult) -> Unit)? = null

    /**
     * Backup a game's cloud save to Google Drive.
     */
    suspend fun backupToGoogle(
        activity: Activity,
        gameSource: GameSource,
        gameId: String,
        gameName: String,
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val context = activity.applicationContext

            // Auth check
            if (!isGoogleSyncEnabled(context)) {
                return@withContext BackupResult(false, "Google sync is not enabled. Enable it in Settings > Google first.")
            }
            if (!awaitAuthenticatedSession(activity)) {
                return@withContext BackupResult(false, "Not signed in to Google Play Games. Please sign in first.")
            }

            val accessToken = getDriveAccessToken(activity)
            if (accessToken == null) {
                return@withContext BackupResult(false, "Google Drive authorization required. Please try again after granting access.")
            }

            performBackup(activity, accessToken, gameSource, gameId, gameName)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Backup failed for $gameSource/$gameId")
            BackupResult(false, "Backup failed: ${e.message}")
        }
    }

    private suspend fun performBackup(
        activity: Activity,
        accessToken: String,
        gameSource: GameSource,
        gameId: String,
        gameName: String,
    ): BackupResult {
        val context = activity.applicationContext

        // Step 1: Ensure cloud saves are synced down to local
        val syncOk = syncDownFromProvider(context, gameSource, gameId)
        if (!syncOk) {
            return BackupResult(false, "Failed to download save from ${gameSource.name}. Saves may not exist or service is unavailable.")
        }

        // Step 2: Locate and zip local save files
        val saveDir = getLocalSaveDir(context, gameSource, gameId)
        if (saveDir == null || !saveDir.exists() || saveDir.listFiles().isNullOrEmpty()) {
            return BackupResult(false, "No local save files found for this game.")
        }

        val zipBytes = zipDirectory(saveDir)
        if (zipBytes.isEmpty()) {
            return BackupResult(false, "Save files are empty.")
        }

        // Step 3: Upload to Google Drive
        val fileName = buildDriveFileName(gameSource, gameId, gameName)
        val folderId = getOrCreateDriveFolder(accessToken)
        if (folderId == null) {
            return BackupResult(false, "Failed to create WinNative folder on Google Drive.")
        }

        val existingFileId = findDriveFile(accessToken, folderId, fileName)
        val uploaded = if (existingFileId != null) {
            updateDriveFile(accessToken, existingFileId, zipBytes)
        } else {
            createDriveFile(accessToken, folderId, fileName, zipBytes)
        }

        return if (uploaded) {
            Timber.tag(TAG).i("Drive upload complete: $fileName (${zipBytes.size} bytes)")
            BackupResult(true, "Cloud save backed up to Google Drive.")
        } else {
            BackupResult(false, "Failed to upload to Google Drive.")
        }
    }

    /**
     * Restore a game's cloud save from Google Drive.
     */
    suspend fun restoreFromGoogle(
        activity: Activity,
        gameSource: GameSource,
        gameId: String,
        gameName: String,
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val context = activity.applicationContext

            // Auth check
            if (!isGoogleSyncEnabled(context)) {
                return@withContext BackupResult(false, "Google sync is not enabled. Enable it in Settings > Google first.")
            }
            if (!awaitAuthenticatedSession(activity)) {
                return@withContext BackupResult(false, "Not signed in to Google Play Games. Please sign in first.")
            }

            val accessToken = getDriveAccessToken(activity)
            if (accessToken == null) {
                return@withContext BackupResult(false, "Google Drive authorization required. Please try again after granting access.")
            }

            performRestore(activity, accessToken, gameSource, gameId, gameName)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Restore failed for $gameSource/$gameId")
            BackupResult(false, "Restore failed: ${e.message}")
        }
    }

    private suspend fun performRestore(
        activity: Activity,
        accessToken: String,
        gameSource: GameSource,
        gameId: String,
        gameName: String,
    ): BackupResult {
        val context = activity.applicationContext
        val fileName = buildDriveFileName(gameSource, gameId, gameName)

        // Step 1: Find and download from Google Drive
        val folderId = getOrCreateDriveFolder(accessToken)
        if (folderId == null) {
            return BackupResult(false, "Failed to access WinNative folder on Google Drive.")
        }

        val fileId = findDriveFile(accessToken, folderId, fileName)
        if (fileId == null) {
            return BackupResult(false, "No backup found on Google Drive for this game.")
        }

        val zipBytes = downloadDriveFile(accessToken, fileId)
        if (zipBytes == null || zipBytes.isEmpty()) {
            return BackupResult(false, "Downloaded backup is empty.")
        }

        Timber.tag(TAG).i("Drive download complete: $fileName (${zipBytes.size} bytes)")

        // Step 2: Unzip to local save directory
        val saveDir = getLocalSaveDir(context, gameSource, gameId)
        if (saveDir == null) {
            return BackupResult(false, "Cannot determine save directory for this game.")
        }
        saveDir.mkdirs()
        unzipToDirectory(zipBytes, saveDir)

        // Step 3: Upload back to provider
        val uploadOk = syncUpToProvider(context, gameSource, gameId)
        if (!uploadOk) {
            return BackupResult(false, "Save files restored locally, but failed to upload to ${gameSource.name}. You can try syncing manually.")
        }

        return BackupResult(true, "Cloud save restored from Google Drive and uploaded to ${gameSource.name}.")
    }

    /**
     * Called from Activity's onActivityResult when Drive consent is completed.
     */
    fun onDriveAuthResult(activity: Activity, resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            Timber.tag(TAG).i("Drive authorization consent granted")
        } else {
            Timber.tag(TAG).w("Drive authorization consent denied (resultCode=%d)", resultCode)
            pendingOperation = null
            pendingCallback?.invoke(BackupResult(false, "Google Drive access was denied."))
            pendingCallback = null
        }
    }

    // ── Provider sync helpers ──

    private suspend fun syncDownFromProvider(context: Context, source: GameSource, gameId: String): Boolean {
        return try {
            when (source) {
                GameSource.STEAM -> {
                    val appId = gameId.toIntOrNull() ?: return false
                    SteamService.syncCloudSavesForBackup(context, appId, "download")
                }
                GameSource.EPIC -> {
                    val appId = gameId.toIntOrNull() ?: return false
                    EpicCloudSavesManager.syncCloudSaves(context, appId, "download")
                }
                GameSource.GOG -> {
                    GOGService.syncCloudSaves(context, "GOG_$gameId", "download")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "syncDownFromProvider failed for $source/$gameId")
            false
        }
    }

    private suspend fun syncUpToProvider(context: Context, source: GameSource, gameId: String): Boolean {
        return try {
            when (source) {
                GameSource.STEAM -> {
                    val appId = gameId.toIntOrNull() ?: return false
                    SteamService.syncCloudSavesForBackup(context, appId, "upload")
                }
                GameSource.EPIC -> {
                    val appId = gameId.toIntOrNull() ?: return false
                    EpicCloudSavesManager.syncCloudSaves(context, appId, "upload")
                }
                GameSource.GOG -> {
                    GOGService.syncCloudSaves(context, "GOG_$gameId", "upload")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "syncUpToProvider failed for $source/$gameId")
            false
        }
    }

    // ── Local save directory resolution ──

    private fun getLocalSaveDir(context: Context, source: GameSource, gameId: String): File? {
        return when (source) {
            GameSource.STEAM -> {
                val appId = gameId.toIntOrNull() ?: return null
                val appDir = SteamService.getAppDirPath(appId)
                val goldbergSaves = File(appDir, "steam_settings/saves")
                if (goldbergSaves.exists()) goldbergSaves else {
                    val cm = ContainerManager(context)
                    val shortcut = cm.loadShortcuts().find { it.getExtra("app_id") == gameId }
                    if (shortcut != null) {
                        File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser/Saved Games")
                    } else {
                        goldbergSaves
                    }
                }
            }
            GameSource.EPIC -> {
                val appId = gameId.toIntOrNull() ?: return null
                val cm = ContainerManager(context)
                val shortcut = cm.loadShortcuts().find {
                    it.getExtra("game_source") == "EPIC" && it.getExtra("app_id") == gameId
                }
                if (shortcut != null) {
                    File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser/Saved Games")
                } else null
            }
            GameSource.GOG -> {
                val cm = ContainerManager(context)
                val shortcut = cm.loadShortcuts().find {
                    it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gameId
                }
                if (shortcut != null) {
                    File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser/Saved Games")
                } else null
            }
        }
    }

    // ── Zip helpers ──

    private fun zipDirectory(dir: File): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zipDirRecursive(zos, dir, "")
        }
        return baos.toByteArray()
    }

    private fun zipDirRecursive(zos: ZipOutputStream, dir: File, baseName: String) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val entryName = if (baseName.isEmpty()) child.name else "$baseName/${child.name}"
            if (child.isDirectory) {
                zos.putNextEntry(ZipEntry("$entryName/"))
                zos.closeEntry()
                zipDirRecursive(zos, child, entryName)
            } else {
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(child).use { fis ->
                    val buf = ByteArray(8192)
                    var len: Int
                    while (fis.read(buf).also { len = it } > 0) {
                        zos.write(buf, 0, len)
                    }
                }
                zos.closeEntry()
            }
        }
    }

    private fun unzipToDirectory(zipBytes: ByteArray, destDir: File) {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val file = File(destDir, entry!!.name)
                if (!file.canonicalPath.startsWith(destDir.canonicalPath)) {
                    throw SecurityException("Zip entry tries to escape target directory")
                }
                if (entry!!.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (zis.read(buf).also { len = it } > 0) {
                            fos.write(buf, 0, len)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    // ── Drive file naming ──

    private fun buildDriveFileName(source: GameSource, gameId: String, gameName: String): String {
        val sanitizedName = gameName.replace(Regex("[^a-zA-Z0-9_ -]"), "").take(50).trim()
        val sanitizedId = gameId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "${source.name.lowercase()}_${sanitizedId}_${sanitizedName}.zip"
    }

    // ── Auth helpers ──

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isGoogleSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GOOGLE_SYNC_ENABLED, false)

    private suspend fun isAuthenticatedBlocking(activity: Activity): Boolean {
        return try {
            val task = PlayGames.getGamesSignInClient(activity).isAuthenticated
            val result = withContext(Dispatchers.IO) {
                try {
                    Tasks.await(task, 10, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    Timber.tag(TAG).e("Timeout waiting for Google authentication state")
                    null
                }
            }
            result?.isAuthenticated == true
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Failed to read Google authentication state")
            false
        }
    }

    private suspend fun awaitAuthenticatedSession(activity: Activity): Boolean {
        repeat(AUTH_SESSION_RETRY_COUNT) { attempt ->
            if (isAuthenticatedBlocking(activity)) {
                return true
            }
            if (attempt < AUTH_SESSION_RETRY_COUNT - 1) {
                delay(AUTH_SESSION_RETRY_DELAY_MS)
            }
        }
        return false
    }

    /**
     * Get an OAuth2 access token with Drive.file scope using AuthorizationClient.
     * If the user hasn't granted Drive access yet, launches the consent UI and returns null
     * (the caller should retry after consent is granted).
     */
    @Suppress("DEPRECATION")
    private suspend fun getDriveAccessToken(activity: Activity): String? = withContext(Dispatchers.IO) {
        try {
            val authRequest = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                .build()

            val authResult: AuthorizationResult = suspendCancellableCoroutine { cont ->
                Identity.getAuthorizationClient(activity)
                    .authorize(authRequest)
                    .addOnSuccessListener { result ->
                        cont.resume(result)
                    }
                    .addOnFailureListener { e ->
                        Timber.tag(TAG).e(e, "AuthorizationClient.authorize failed")
                        cont.resume(null)
                    }
            } ?: return@withContext null

            if (authResult.hasResolution()) {
                // User needs to grant consent — launch the consent UI
                Timber.tag(TAG).i("Drive authorization requires user consent, launching...")
                val pendingIntent = authResult.pendingIntent
                if (pendingIntent != null) {
                    activity.startIntentSenderForResult(
                        pendingIntent.intentSender,
                        REQUEST_CODE_DRIVE_AUTH,
                        null, 0, 0, 0
                    )
                }
                return@withContext null // Will retry after consent
            }

            val token = authResult.accessToken
            if (token != null) {
                Timber.tag(TAG).i("Got Drive access token via AuthorizationClient")
            } else {
                Timber.tag(TAG).e("AuthorizationResult has no access token")
            }
            token
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get Drive access token")
            null
        }
    }

    // ── Google Drive REST API helpers ──

    /**
     * Find or create the "WinNative" folder on Google Drive.
     */
    private fun getOrCreateDriveFolder(accessToken: String): String? {
        // Search for existing folder
        val query = "name='$DRIVE_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val searchRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(searchRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    val folderId = files.getJSONObject(0).getString("id")
                    Timber.tag(TAG).d("Found existing Drive folder: %s", folderId)
                    return folderId
                }
            }
        }

        // Create the folder
        val metadata = JSONObject().apply {
            put("name", DRIVE_FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
        }

        val createRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(createRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val folderId = json.getString("id")
                Timber.tag(TAG).i("Created Drive folder: %s", folderId)
                return folderId
            }
            Timber.tag(TAG).e("Failed to create Drive folder: %d %s", response.code, response.message)
        }
        return null
    }

    /**
     * Find a file by name inside a specific folder.
     */
    private fun findDriveFile(accessToken: String, folderId: String, fileName: String): String? {
        val query = "name='$fileName' and '$folderId' in parents and trashed=false"
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return files.getJSONObject(0).getString("id")
                }
            }
        }
        return null
    }

    /**
     * Create a new file on Google Drive inside the specified folder.
     */
    private fun createDriveFile(accessToken: String, folderId: String, fileName: String, data: ByteArray): Boolean {
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("parents", org.json.JSONArray().put(folderId))
        }

        val boundary = "winnative_boundary_${System.currentTimeMillis()}"
        val body = buildMultipartRelatedBody(boundary, metadata.toString(), data)

        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Timber.tag(TAG).i("Created Drive file: %s (%d bytes)", fileName, data.size)
                return true
            }
            Timber.tag(TAG).e("Failed to create Drive file: %d %s", response.code, response.message)
        }
        return false
    }

    private fun buildMultipartRelatedBody(boundary: String, jsonMetadata: String, fileData: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val crlf = "\r\n"
        fun write(s: String) = baos.write(s.toByteArray(Charsets.UTF_8))

        write("--$boundary$crlf")
        write("Content-Type: application/json; charset=UTF-8$crlf")
        write(crlf)
        write(jsonMetadata)
        write(crlf)
        write("--$boundary$crlf")
        write("Content-Type: application/zip$crlf")
        write(crlf)
        baos.write(fileData)
        write(crlf)
        write("--$boundary--")

        return baos.toByteArray()
    }

    /**
     * Update an existing file on Google Drive (overwrite contents).
     */
    private fun updateDriveFile(accessToken: String, fileId: String, data: ByteArray): Boolean {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .patch(data.toRequestBody("application/zip".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Timber.tag(TAG).i("Updated Drive file: %s (%d bytes)", fileId, data.size)
                return true
            }
            Timber.tag(TAG).e("Failed to update Drive file: %d %s", response.code, response.message)
        }
        return false
    }

    /**
     * Download a file's content from Google Drive.
     */
    private fun downloadDriveFile(accessToken: String, fileId: String): ByteArray? {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return response.body?.bytes()
            }
            Timber.tag(TAG).e("Failed to download Drive file: %d %s", response.code, response.message)
        }
        return null
    }
}
