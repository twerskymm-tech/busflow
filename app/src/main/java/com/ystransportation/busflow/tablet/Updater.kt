package com.ystransportation.busflow.tablet

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Update app": checks the newest BusFlow Tablet on GitHub, downloads it and opens the installer.
 * If GitHub can't be read without signing in (private repository), it opens the download page instead.
 */
object Updater {
    private const val REPO = "twerskymm-tech/busflow"
    const val DOWNLOAD_PAGE = "https://github.com/$REPO/releases/latest/download/busflow-tablet.apk"

    private fun versionParts(v: String): List<Int> = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(v)?.groupValues?.drop(1)?.map { it.toInt() } ?: emptyList()
    private fun newer(a: String, b: String): Boolean {
        val x = versionParts(a); val y = versionParts(b)
        if (x.size != 3 || y.size != 3) return false
        for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    fun run(activity: Activity, scope: LifecycleCoroutineScope) {
        Toast.makeText(activity, "Checking for a new version…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                try {
                    val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 20000
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText())
                } catch (e: Exception) { null }
            }
            if (latest == null) {
                // Can't check (no signal, or the download needs a GitHub sign-in): open the download in the browser
                AlertDialog.Builder(activity)
                    .setTitle("Update app")
                    .setMessage("Open the download page? (This is version ${BuildConfig.VERSION_NAME}.)")
                    .setPositiveButton("Open") { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE))) }
                    .setNegativeButton("Cancel", null).show()
                return@launch
            }
            val name = latest.optString("name")
            val assets = latest.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
            }
            if (!newer(name, BuildConfig.VERSION_NAME) || url == null) {
                Toast.makeText(activity, "You have the newest version (${BuildConfig.VERSION_NAME})", Toast.LENGTH_LONG).show()
                return@launch
            }
            AlertDialog.Builder(activity)
                .setTitle("New version ${versionParts(name).joinToString(".")}")
                .setMessage("Download and install it now? (This is ${BuildConfig.VERSION_NAME}.)")
                .setPositiveButton("Update") { _, _ -> download(activity, scope, url!!) }
                .setNegativeButton("Later", null).show()
        }
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow BusFlow Tablet to install updates, then tap Update app again", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val dir = File(activity.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val file = File(dir, "busflow-tablet.apk")
        if (file.exists()) file.delete()
        val dm = activity.getSystemService(Activity.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle("BusFlow Tablet update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(activity, null, "updates/busflow-tablet.apk"),
        )
        Toast.makeText(activity, "Downloading the update…", Toast.LENGTH_LONG).show()
        scope.launch {
            while (true) {
                delay(1500)
                val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: break
                val status = c.use { if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1 }
                if (status == DownloadManager.STATUS_SUCCESSFUL) { install(activity, file); break }
                if (status == DownloadManager.STATUS_FAILED || status == -1) {
                    Toast.makeText(activity, "Download failed — opening the download page", Toast.LENGTH_LONG).show()
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE)))
                    break
                }
            }
        }
    }

    private fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
