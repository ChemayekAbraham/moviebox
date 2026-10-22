package com.moviebox.webview

import android.app.DownloadManager
import android.content.Context
import android.net.Uri

/**
 * Keeps track of every video the app has downloaded through Android's DownloadManager,
 * so the "Me" tab can show progress and play finished files offline.
 */
object DownloadTracker {

    private const val PREFS = "moviebox_downloads"
    private const val KEY_IDS = "ids"

    data class Item(
        val id: Long,
        val title: String,
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val mimeType: String?
    ) {
        val isComplete get() = status == DownloadManager.STATUS_SUCCESSFUL
        val isFailed get() = status == DownloadManager.STATUS_FAILED
        val isRunning get() = status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PENDING ||
                status == DownloadManager.STATUS_PAUSED
        val progress: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ids(ctx: Context): Set<Long> =
        prefs(ctx).getStringSet(KEY_IDS, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun track(ctx: Context, id: Long, title: String) {
        val updated = ids(ctx).map { it.toString() }.toMutableSet().apply { add(id.toString()) }
        prefs(ctx).edit()
            .putStringSet(KEY_IDS, updated)
            .putString("title_$id", title)
            .apply()
    }

    private fun forget(ctx: Context, id: Long) {
        val updated = ids(ctx).filter { it != id }.map { it.toString() }.toSet()
        prefs(ctx).edit().putStringSet(KEY_IDS, updated).remove("title_$id").apply()
    }

    /** Cancels (if running) or deletes (if finished) the download and its file. */
    fun delete(ctx: Context, id: Long) {
        dm(ctx).remove(id)
        forget(ctx, id)
    }

    fun list(ctx: Context): List<Item> {
        val tracked = ids(ctx)
        if (tracked.isEmpty()) return emptyList()

        val result = mutableListOf<Item>()
        val query = DownloadManager.Query().setFilterById(*tracked.toLongArray())
        dm(ctx).query(query)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val soFarCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val mimeCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += Item(
                    id = id,
                    title = prefs(ctx).getString("title_$id", null) ?: c.getString(titleCol) ?: "Video",
                    status = c.getInt(statusCol),
                    downloadedBytes = c.getLong(soFarCol),
                    totalBytes = c.getLong(totalCol),
                    mimeType = c.getString(mimeCol)
                )
            }
        }

        // Forget downloads that were removed outside the app
        val found = result.map { it.id }.toSet()
        tracked.filter { it !in found }.forEach { forget(ctx, it) }

        return result.sortedByDescending { it.id }
    }

    fun playableUri(ctx: Context, id: Long): Uri? =
        try { dm(ctx).getUriForDownloadedFile(id) } catch (e: Exception) { null }

    private fun dm(ctx: Context) =
        ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
}
