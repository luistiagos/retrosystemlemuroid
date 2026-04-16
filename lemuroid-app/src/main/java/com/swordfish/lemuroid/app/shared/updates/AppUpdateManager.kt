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

/**
 * Manages app self-updates.
 *
 * Version metadata is fetched from [VERSION_ENDPOINT]:
 *   GET → { "versionCode": 232, "versionName": "1.18.0", "apkUrl": "https://…" }
 *
 * The APK is downloaded to cacheDir/updates/lemuroid-update.apk and installed via
 * PackageInstaller (API 21+). On API 31+ the install is silent; on older APIs the
 * system shows its standard install prompt.
 *
 * ROMs, saves and states live in getExternalFilesDir — they are untouched by an
 * install-replace operation.
 */
class AppUpdateManager(private val context: Context) {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
    )

    companion object {
        /**
         * JSON endpoint that returns the latest version manifest.
         * Expected response: {"versionCode":232,"versionName":"1.18.0","apkUrl":"https://…"}
         */
        const val VERSION_ENDPOINT = "https://emuladores.pythonanywhere.com/app_version"
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyConscryptTls()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Fetches version metadata from [VERSION_ENDPOINT].
     * Returns [UpdateInfo] when a newer version exists, null when already up-to-date or on error.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val info = fetchVersionInfo() ?: return@withContext null
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                Timber.d("App up-to-date (current=${BuildConfig.VERSION_CODE}, remote=${info.versionCode})")
                null
            } else {
                Timber.d("Update available: ${info.versionName} (code=${info.versionCode})")
                info
            }
        } catch (e: Exception) {
            Timber.w(e, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Downloads the APK for [info] and triggers system installation.
     * Reports overall progress 0.0–1.0 via [onProgress].
     */
    suspend fun downloadAndInstall(info: UpdateInfo, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val destFile = File(context.cacheDir, "updates/lemuroid-update.apk")
            destFile.parentFile?.mkdirs()

            Timber.d("Downloading update ${info.versionName} from ${info.apkUrl}")
            downloadApk(info.apkUrl, destFile, onProgress)
            Timber.d("APK ready: ${destFile.absolutePath} (${destFile.length()} bytes)")

            installApk(destFile)
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchVersionInfo(): UpdateInfo? {
        val request = Request.Builder()
            .url(VERSION_ENDPOINT)
            .header("User-Agent", "LemuroidApp/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Version check HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            return UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
            )
        }
    }

    private fun downloadApk(url: String, dest: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LemuroidApp/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
            val body = response.body ?: throw IOException("Empty response body")
            val total = body.contentLength().coerceAtLeast(1L)
            var downloaded = 0L
            FileOutputStream(dest, false).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
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
            apkFile.inputStream().use { src ->
                session.openWrite("lemuroid.apk", 0, apkFile.length()).use { dst ->
                    src.copyTo(dst)
                    session.fsync(dst)
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
