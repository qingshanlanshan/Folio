package com.qshlab.folio.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The newest release, as published by site/deploy.sh in https://folio.qshlab.com/version.json. */
data class UpdateInfo(
    val versionCode: Long,
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val notes: String?,
) {
    val url get() = "${Updates.SITE}/$file"
}

/**
 * Checks the download site for a newer release, downloads it (verifying its SHA-256) and hands it
 * to Android's installer. Only version.json is read; nothing about the user is sent.
 */
object Updates {
    const val SITE = "https://folio.qshlab.com"
    private const val PREFS = "updates"
    private const val DAY = 24 * 60 * 60 * 1000L

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    /** A release newer than this app, once a check found one. */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoCheck(context: Context) = prefs(context).getBoolean("auto", true)

    fun setAutoCheck(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto", on).apply()

    /** Fetches version.json; null when offline or unreadable. */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$SITE/version.json").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            val j = try {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
            UpdateInfo(
                versionCode = j.optLong("versionCode", 0),
                version = j.getString("version"),
                file = j.getString("file"),
                size = j.optLong("size"),
                sha256 = j.optString("sha256"),
                notes = j.optString("notes").ifBlank { null },
            )
        }.getOrNull()
    }

    /**
     * Checks for a newer release: always when [force], otherwise at most once a day and only
     * if automatic checks are on. Returns the newer release, or null.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? {
        val p = prefs(context)
        if (!force && (!autoCheck(context) || System.currentTimeMillis() - p.getLong("last", 0) < DAY)) return _available.value
        val latest = fetchLatest() ?: return _available.value
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        val newer = latest.takeIf { it.versionCode > installedVersionCode(context) }
        _available.value = newer
        return newer
    }

    /** Downloads the APK into the cache, checking its size and SHA-256. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, info.file)
        val conn = URL(info.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256.isNotBlank() && !sha.equals(info.sha256, ignoreCase = true)) {
            out.delete()
            throw IllegalStateException("checksum mismatch")
        }
        out
    }

    /** Opens Android's installer (it asks once to allow installs from this app). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
