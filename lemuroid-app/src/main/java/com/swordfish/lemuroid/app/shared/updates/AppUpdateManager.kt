package com.swordfish.lemuroid.app.shared.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.lib.ssl.ConscryptOkHttpHelper.applyConscryptTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Atualizacao in-app: consulta o version.json publicado ao lado do APK,
 * baixa a versao nova e instala.
 *
 * O version.json e gerado e publicado pelo build-and-upload.ps1 na mesma
 * pasta do R2 que serve os APKs, entao ele nunca fica dessincronizado do
 * binario que esta no ar. Antes disso o app consultava uma rota que respondia
 * 404 e ninguem jamais era avisado de versao nova.
 *
 * O SHA-256 vindo do version.json e conferido antes de instalar: download
 * truncado em rede movel viraria "o pacote parece ser invalido" na cara do
 * usuario. Hash errado = apaga e tenta de novo, o instalador nunca ve um APK
 * corrompido.
 */
class AppUpdateManager(private val context: Context) {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val channel: String,
        /** SHA-256 esperado do APK; vazio se o servidor nao publicou. */
        val sha256: String = "",
        /** Tamanho esperado em bytes; 0 se o servidor nao publicou. */
        val size: Long = 0L,
    )

    companion object {
        val VERSION_ENDPOINT: String = BuildConfig.APP_UPDATE_ENDPOINT

        private const val MAX_RETRIES = 3
        private const val BUFFER_SIZE = 256 * 1024

        /** 206 Partial Content: o servidor honrou o header Range. */
        private const val HTTP_PARTIAL = 206

        private const val PREFS = "app_update_prefs"
        private const val PREF_LAST_CHECK_MS = "update_last_check_ms"
        private const val PREF_SKIPPED_CODE = "update_skipped_version_code"

        /** Nao incomoda o usuario mais de uma vez a cada 12h. */
        private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyConscryptTls()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ------------------------------------------------------------------ check

    /**
     * True se ja passou tempo suficiente desde a ultima checagem. A checagem em
     * si e barata, mas isso evita reabrir o dialogo a cada abertura do app.
     */
    fun shouldCheckNow(): Boolean =
        System.currentTimeMillis() - prefs.getLong(PREF_LAST_CHECK_MS, 0L) >= CHECK_INTERVAL_MS

    private fun markChecked() {
        prefs.edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply()
    }

    /** Usuario escolheu "Agora nao": nao perguntar de novo por essa versao. */
    fun skipVersion(versionCode: Int) {
        prefs.edit().putInt(PREF_SKIPPED_CODE, versionCode).apply()
    }

    fun isVersionSkipped(versionCode: Int): Boolean =
        prefs.getInt(PREF_SKIPPED_CODE, -1) == versionCode

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = fetchVersionInfo()
        markChecked()
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

    private fun fetchVersionInfo(): UpdateInfo {
        val request = Request.Builder()
            .url(VERSION_ENDPOINT)
            .header("User-Agent", userAgent())
            // O version.json e servido pelo mesmo CDN do APK; sem isso um cache
            // intermediario pode devolver a versao anterior por horas.
            .header("Cache-Control", "no-cache")
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
            // Splits por ABI: "apkUrls" mapeia ABI -> URL, com "apkSha256"/"apkSizes"
            // paralelos. SUPPORTED_ABIS vem em ordem de preferencia do device (arm64
            // antes de armv7). Fallback nos campos legados "apkUrl"/"sha256"/"size"
            // para servidores que ainda publicam um APK unico.
            val apkUrls = json.optJSONObject("apkUrls")
            val abi = apkUrls?.let { urls ->
                Build.SUPPORTED_ABIS.firstOrNull { urls.optString(it).isNotEmpty() }
            }
            val abiUrl = abi?.let { apkUrls?.optString(it) }?.takeIf { it.isNotEmpty() }
            val abiSha = abi?.let { json.optJSONObject("apkSha256")?.optString(it) }
                ?.takeIf { it.isNotEmpty() }
            val abiSize = abi?.let { json.optJSONObject("apkSizes")?.optLong(it) }
                ?.takeIf { it > 0L }

            return UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = abiUrl ?: json.getString("apkUrl"),
                channel = channel,
                // Os campos legados so servem quando NAO achamos a URL por ABI:
                // misturar a URL de uma ABI com o hash da outra so daria "arquivo
                // corrompido" em loop no armv7.
                sha256 = if (abiUrl != null) abiSha.orEmpty() else json.optString("sha256", ""),
                size = if (abiUrl != null) (abiSize ?: 0L) else json.optLong("size", 0L),
            )
        }
    }

    // --------------------------------------------------------- download+install

    /**
     * True se o usuario ja autorizou este app a instalar APKs. Sem isso o
     * Android 8+ recusa a instalacao, entao checamos antes de baixar 100 MB a toa.
     */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Abre a tela do sistema onde o usuario libera "instalar apps desconhecidos". */
    fun buildAllowInstallIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    suspend fun downloadAndInstall(
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val destFile = File(context.cacheDir, "updates/lemuroid-update.apk")
        destFile.parentFile?.mkdirs()

        Timber.d("Downloading update ${info.versionName} from ${info.apkUrl}")
        try {
            downloadWithRetries(info, destFile, onProgress)
        } catch (e: Exception) {
            destFile.delete()
            throw e
        }
        Timber.d("APK ready: ${destFile.absolutePath} (${destFile.length()} bytes)")

        installApk(destFile)
    }

    private suspend fun downloadWithRetries(
        info: UpdateInfo,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        var last: IOException? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                downloadApk(info, dest, onProgress)
                verifyApk(info, dest)
                return
            } catch (e: IOException) {
                last = e
                // Hash/tamanho errado significa que o .part esta envenenado -
                // retomar so propagaria o lixo, entao recomeca do zero.
                dest.delete()
                partFile(dest).delete()
                if (attempt >= MAX_RETRIES) break
                Timber.w("Update download attempt $attempt failed (${e.message}), retrying")
                delay(1000L * attempt)
            }
        }
        throw last ?: IOException("Download failed")
    }

    private fun partFile(dest: File) = File("${dest.absolutePath}.part")

    private fun downloadApk(
        info: UpdateInfo,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val part = partFile(dest)
        val existing = if (part.exists()) part.length() else 0L

        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", userAgent())
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
            val body = response.body ?: throw IOException("Empty response body")

            // 206 = servidor honrou o Range e manda so o que falta. 200 = ignorou
            // e manda tudo de novo, entao o .part anterior precisa ser descartado.
            val resuming = response.code == HTTP_PARTIAL
            if (!resuming && part.exists()) part.delete()

            var downloaded = if (resuming) existing else 0L
            val total = when {
                body.contentLength() > 0 -> downloaded + body.contentLength()
                info.size > 0 -> info.size
                else -> 0L
            }

            FileOutputStream(part, resuming).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }

        if (dest.exists() && !dest.delete()) {
            throw IOException("Could not replace ${dest.name}")
        }
        if (!part.renameTo(dest)) {
            throw IOException("Could not finalize download")
        }
    }

    /**
     * Confere tamanho e SHA-256 contra o que o servidor anunciou. E o que
     * garante que um download truncado nunca chega ao instalador.
     */
    private fun verifyApk(info: UpdateInfo, apk: File) {
        if (info.size > 0 && apk.length() != info.size) {
            throw IOException("Incomplete download: ${apk.length()} bytes, expected ${info.size}")
        }
        if (info.sha256.isEmpty()) {
            Timber.w("version.json has no sha256 - integrity not verified")
            return
        }
        val actual = sha256Of(apk)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            throw IOException("Corrupted file (SHA-256 mismatch)")
        }
        Timber.d("SHA-256 matches: $actual")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- install

    private fun installApk(apkFile: File) {
        try {
            installViaPackageInstaller(apkFile)
        } catch (e: Exception) {
            Timber.w(e, "PackageInstaller failed, falling back to install Intent")
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

    private fun userAgent() =
        "LemuroidApp/${BuildConfig.VERSION_NAME} (${BuildConfig.APP_UPDATE_CHANNEL})"
}
