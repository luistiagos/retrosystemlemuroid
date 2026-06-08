package com.swordfish.lemuroid.app.shared.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.FileProvider
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.lib.ssl.ConscryptOkHttpHelper.applyConscryptTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppUpdateManager(private val context: Context) {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val channel: String,
    )

    companion object {
        val VERSION_ENDPOINT: String = BuildConfig.APP_UPDATE_ENDPOINT
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyConscryptTls()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = fetchVersionInfo()
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            Timber.d(
                "App up-to-date " +
                    "(channel=${BuildConfig.APP_UPDATE_CHANNEL}, current=${BuildConfig.VERSION_CODE}, remote=${info.versionCode})",
            )
            null
        } else {
            Timber.d("Update available: ${info.versionName} (channel=${info.channel}, code=${info.versionCode})")
            info
        }
    }

    suspend fun downloadAndInstall(
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val destFile = File(context.cacheDir, "updates/lemuroid-update.apk")
        destFile.parentFile?.mkdirs()

        Timber.d("Downloading update ${info.versionName} from ${info.apkUrl}")
        downloadApk(info.apkUrl, destFile, onProgress)
        Timber.d("APK ready: ${destFile.absolutePath} (${destFile.length()} bytes)")

        installApk(destFile)
    }

    private fun fetchVersionInfo(): UpdateInfo {
        val request = Request.Builder()
            .url(VERSION_ENDPOINT)
            .header("User-Agent", "LemuroidApp/${BuildConfig.VERSION_NAME} (${BuildConfig.APP_UPDATE_CHANNEL})")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Version check failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Version check failed: empty response body")
            val json = JSONObject(body)
            val channel = json.optString("channel", BuildConfig.APP_UPDATE_CHANNEL)
            if (!channel.equals(BuildConfig.APP_UPDATE_CHANNEL, ignoreCase = true)) {
                throw IOException(
                    "Update channel mismatch: expected ${BuildConfig.APP_UPDATE_CHANNEL}, got $channel",
                )
            }
            return UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                channel = channel,
            )
        }
    }

    private fun downloadApk(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LemuroidApp/${BuildConfig.VERSION_NAME} (${BuildConfig.APP_UPDATE_CHANNEL})")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
            val body = response.body ?: throw IOException("Empty response body")
            val total = body.contentLength().coerceAtLeast(1L)
            var downloaded = 0L
            FileOutputStream(dest, false).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress((downloaded.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            installViaPackageInstaller(apkFile)
        } else {
            installViaIntent(apkFile)
        }
    }

    private fun installViaPackageInstaller(apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apkFile.inputStream().use { source ->
                session.openWrite("lemuroid.apk", 0, apkFile.length()).use { target ->
                    source.copyTo(target)
                    session.fsync(target)
                }
            }
            val callbackIntent = Intent(context, UpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        } finally {
            session.close()
        }
        Timber.d("PackageInstaller session committed (id=$sessionId)")
    }

    private fun installViaIntent(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.update_provider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
