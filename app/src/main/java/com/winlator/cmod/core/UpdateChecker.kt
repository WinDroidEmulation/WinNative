package com.winlator.cmod.core

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object UpdateChecker {

    private const val BASE_URL = "https://winnative.dev/Downloads/"
    private const val STANDARD_APK = "standard.apk"
    private const val LUDASHI_APK = "ludashi.apk"
    private const val RELEASE_NOTES_URL = "${BASE_URL}release.txt"

    private const val PREF_CHECK_FOR_UPDATES = "check_for_updates"
    private const val PREF_INSTALL_TIMESTAMP = "app_install_timestamp"
    private const val PREF_LAST_UPDATE_CHECK = "last_update_check_time"

    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L // 1 hour

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Returns true if the user has the "Check for Updates" toggle enabled (default: true).
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_CHECK_FOR_UPDATES, true)
    }

    /**
     * Records the current time as the app install timestamp if not already set.
     * Should be called on first launch or after an update install.
     */
    fun recordInstallTimestamp(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.contains(PREF_INSTALL_TIMESTAMP)) {
            prefs.edit().putLong(PREF_INSTALL_TIMESTAMP, System.currentTimeMillis()).apply()
        }
    }

    /**
     * Returns true if enough time has passed since the last check.
     */
    fun isDueForCheck(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lastCheck = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS
    }

    /**
     * Gets the APK filename for the current build flavor.
     */
    private fun getApkFilename(context: Context): String {
        val packageName = context.packageName
        return if (packageName == "com.ludashi.benchmark") LUDASHI_APK else STANDARD_APK
    }

    /**
     * Perform an update check in the background.
     * If an update is available, shows a dialog on the main thread.
     * @param force If true, skips the interval check (used for app startup).
     */
    fun checkForUpdate(context: Context, force: Boolean = false) {
        if (!isEnabled(context)) return
        if (!force && !isDueForCheck(context)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = fetchUpdateInfo(context)
                if (result != null) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(context, result)
                    }
                }
                // Record that we checked
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis())
                    .apply()
            } catch (e: Exception) {
                Timber.e(e, "Update check failed")
            }
        }
    }

    /**
     * Resets the last check time so the next check will run immediately.
     * Called when exiting a game/container to trigger a deferred check.
     */
    fun resetCheckTimer(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_LAST_UPDATE_CHECK, 0L)
            .apply()
    }

    data class UpdateInfo(
        val serverModified: Date,
        val serverModifiedFormatted: String,
        val serverVersionName: String?,
        val downloadUrl: String,
        val releaseNotes: String?
    )

    private fun fetchUpdateInfo(context: Context): UpdateInfo? {
        val apkFilename = getApkFilename(context)
        val apkUrl = "$BASE_URL$apkFilename"

        // HEAD request to get Last-Modified header
        val headRequest = Request.Builder()
            .url(apkUrl)
            .head()
            .build()

        val headResponse = client.newCall(headRequest).execute()
        if (!headResponse.isSuccessful) {
            Timber.w("Update check HEAD request failed: ${headResponse.code}")
            return null
        }

        val lastModifiedHeader = headResponse.header("Last-Modified") ?: return null

        // Parse server last-modified time
        val serverDate = parseHttpDate(lastModifiedHeader) ?: return null

        // Get install timestamp
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val installTimestamp = prefs.getLong(PREF_INSTALL_TIMESTAMP, System.currentTimeMillis())

        // Also get the currently installed version name for comparison
        val currentVersionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        // Try to extract version from the directory listing page
        val serverVersionName = fetchServerVersionName(apkFilename)

        // Determine if update is available:
        // 1. Server file is newer than when app was installed
        // 2. OR server version name is different from installed version
        val isNewer = serverDate.time > installTimestamp
        val versionDiffers = serverVersionName != null && currentVersionName != null &&
                serverVersionName != currentVersionName

        if (!isNewer && !versionDiffers) {
            return null // No update
        }

        // Fetch release notes
        val releaseNotes = fetchReleaseNotes()

        val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.US)
        dateFormat.timeZone = TimeZone.getDefault()

        return UpdateInfo(
            serverModified = serverDate,
            serverModifiedFormatted = dateFormat.format(serverDate),
            serverVersionName = serverVersionName,
            downloadUrl = apkUrl,
            releaseNotes = releaseNotes
        )
    }

    /**
     * Fetches the directory listing page to try to extract version info.
     * The server may show file details in the HTML listing.
     */
    private fun fetchServerVersionName(apkFilename: String): String? {
        return try {
            val request = Request.Builder()
                .url(BASE_URL)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null

            // Look for version patterns near the APK filename in the listing
            // Common pattern: version like "7.1.4x-cmod" or similar
            val versionPattern = Pattern.compile(
                """$apkFilename.*?(\d+\.\d+\.\d+\w*(?:-\w+)?)""",
                Pattern.DOTALL
            )
            val matcher = versionPattern.matcher(body)
            if (matcher.find()) matcher.group(1) else null
        } catch (e: Exception) {
            Timber.d(e, "Could not fetch server version name")
            null
        }
    }

    private fun fetchReleaseNotes(): String? {
        return try {
            val request = Request.Builder()
                .url(RELEASE_NOTES_URL)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.d(e, "Could not fetch release notes")
            null
        }
    }

    private fun parseHttpDate(dateStr: String): Date? {
        val formats = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss z",   // RFC 1123
            "EEEE, dd-MMM-yy HH:mm:ss z",    // RFC 1036
            "EEE MMM d HH:mm:ss yyyy"         // ANSI C asctime()
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("GMT")
                return sdf.parse(dateStr)
            } catch (_: Exception) { }
        }
        return null
    }

    private fun showUpdateDialog(context: Context, info: UpdateInfo) {
        if (context is android.app.Activity && context.isFinishing) return

        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val smallPad = (8 * context.resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // Released date
        val releasedLabel = TextView(context).apply {
            text = "Released: ${info.serverModifiedFormatted}"
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 14f
        }
        container.addView(releasedLabel)

        // Version (if available)
        if (info.serverVersionName != null) {
            val versionLabel = TextView(context).apply {
                text = "Version: ${info.serverVersionName}"
                setTextColor(0xFFB0B0B0.toInt())
                textSize = 14f
                setPadding(0, smallPad, 0, 0)
            }
            container.addView(versionLabel)
        }

        // Release notes
        if (!info.releaseNotes.isNullOrBlank()) {
            val divider = android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    topMargin = padding
                    bottomMargin = smallPad
                }
                setBackgroundColor(0xFF444444.toInt())
            }
            container.addView(divider)

            val notesHeader = TextView(context).apply {
                text = "Release Notes"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, smallPad, 0, smallPad)
            }
            container.addView(notesHeader)

            val notesBody = TextView(context).apply {
                text = info.releaseNotes
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                movementMethod = ScrollingMovementMethod.getInstance()
                maxLines = 12
                isVerticalScrollBarEnabled = true
            }

            val scrollView = ScrollView(context).apply {
                val maxHeight = (200 * context.resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                }
                addView(notesBody)
            }
            container.addView(scrollView)
        }

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Update Available")
            .setView(container)
            .setPositiveButton("Download") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                context.startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .create()

        dialog.show()
    }

    /**
     * Updates the install timestamp to now. Call after the user installs an update,
     * or on each app start to keep it accurate.
     */
    fun refreshInstallTimestamp(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installTime = pInfo.lastUpdateTime.coerceAtLeast(pInfo.firstInstallTime)
            prefs.edit().putLong(PREF_INSTALL_TIMESTAMP, installTime).apply()
        } catch (e: PackageManager.NameNotFoundException) {
            // If we can't get package info, record now
            if (!prefs.contains(PREF_INSTALL_TIMESTAMP)) {
                prefs.edit().putLong(PREF_INSTALL_TIMESTAMP, System.currentTimeMillis()).apply()
            }
        }
    }
}
