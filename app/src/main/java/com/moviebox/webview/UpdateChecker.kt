package com.moviebox.webview

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks GitHub Releases for a newer version of the app and installs it.
 *
 * Publishing an update = push a tag like `v1.4` (GitHub Actions builds and attaches the APK),
 * or create a Release by hand and attach the signed APK. Users then see an "Update available"
 * prompt the next time they open the app. Android always asks the user to confirm the install.
 */
object UpdateChecker {

    /**
     * Where releases are published, in priority order. The code lives in a private repo;
     * only APKs + release notes are published to the public `moviebox_updates` repo.
     * The old repo is a harmless fallback (it returns 404 once it is private).
     */
    private val RELEASE_SOURCES = listOf(
        "https://api.github.com/repos/ChemayekAbraham/moviebox_updates/releases/latest",
        "https://api.github.com/repos/ChemayekAbraham/moviebox/releases/latest"
    )
    private const val PREFS = "updater"
    private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L // at most every 3 hours

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** APK waiting to be installed after the user grants "install unknown apps". */
    private var pendingApk: File? = null
    private var checking = false

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

    /** Call from onCreate. [force] ignores the interval (used by a manual "check for updates"). */
    fun check(activity: Activity, force: Boolean = false) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (checking || (!force && now - prefs.getLong("last_check", 0) < CHECK_INTERVAL_MS)) return
        checking = true
        val current = installedVersion(activity)

        io.execute {
            val release = try { fetchLatest() } catch (e: Exception) { null }
            main.post {
                checking = false
                if (activity.isFinishing || activity.isDestroyed) return@post
                prefs.edit().putLong("last_check", now).apply()
                when {
                    release == null -> if (force) toast(activity, "Couldn't check for updates")
                    compareVersions(release.version, current) > 0 -> showUpdateDialog(activity, release, current)
                    force -> toast(activity, "You're on the latest version ($current)")
                }
            }
        }
    }

    /** Call from onResume: finishes an install that was waiting for the permission screen. */
    fun onResume(activity: Activity) {
        val apk = pendingApk ?: return
        if (canInstall(activity)) {
            pendingApk = null
            launchInstaller(activity, apk)
        }
    }

    // ---------------------------------------------------------------- GitHub

    /** Newest release across all sources (null if none reachable). */
    private fun fetchLatest(): Release? =
        RELEASE_SOURCES.mapNotNull { url -> try { fetchFrom(url) } catch (e: Exception) { null } }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }

    private fun fetchFrom(apiUrl: String): Release? {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MovieBoxApp-Updater")
        }
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        version = json.optString("tag_name").removePrefix("v").trim(),
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        apkSize = a.optLong("size")
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun showUpdateDialog(activity: Activity, r: Release, current: String) {
        val size = if (r.apkSize > 0) " (${android.text.format.Formatter.formatShortFileSize(activity, r.apkSize)})" else ""
        val notes = r.notes.ifBlank { "Bug fixes and improvements." }.take(1200)
        AlertDialog.Builder(activity)
            .setTitle("\uD83D\uDE80 Update ${r.version} available")
            .setMessage("You have $current.\n\nWhat's new:\n$notes\n\nDownload size$size")
            .setPositiveButton("Update now") { _, _ -> download(activity, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(activity: Activity, r: Release) {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        dir.listFiles { f -> f.name.startsWith("update-") && f.name.endsWith(".apk") }?.forEach { it.delete() }
        val fileName = "update-${r.version}.apk"
        val target = File(dir, fileName)

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = try {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(r.apkUrl))
                    .setTitle("MovieBox update ${r.version}")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
            )
        } catch (e: Exception) {
            toast(activity, "Update download failed: ${e.message}")
            return
        }

        // Small progress dialog
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply { text = "Starting download…"; gravity = Gravity.CENTER }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(bar); addView(label)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading update ${r.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> dm.remove(id) }
            .show()

        val poll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (!c.moveToFirst()) { dialog.dismiss(); return }
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            dialog.dismiss()
                            install(activity, target)
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            dialog.dismiss()
                            toast(activity, "Update download failed. Try again later.")
                            return
                        }
                        else -> if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            bar.progress = pct
                            label.text = "$pct%  •  " +
                                android.text.format.Formatter.formatShortFileSize(activity, done) + " / " +
                                android.text.format.Formatter.formatShortFileSize(activity, total)
                        }
                    }
                }
                main.postDelayed(this, 500)
            }
        }
        main.post(poll)
    }

    // ---------------------------------------------------------------- install

    private fun install(activity: Activity, apk: File) {
        if (!apk.exists()) { toast(activity, "Update file missing. Try again."); return }
        if (canInstall(activity)) {
            launchInstaller(activity, apk)
        } else {
            pendingApk = apk
            AlertDialog.Builder(activity)
                .setTitle("One more step")
                .setMessage("To install updates, allow MovieBox to install apps. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("Cancel") { _, _ -> pendingApk = null }
                .show()
        }
    }

    private fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    private fun launchInstaller(activity: Activity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast(activity, "Couldn't open installer: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** Numeric compare: "1.10" > "1.9", "1.3.1" > "1.3". */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
