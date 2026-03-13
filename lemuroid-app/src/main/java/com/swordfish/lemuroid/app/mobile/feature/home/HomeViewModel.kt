package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.common.coroutines.combine
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.util.Log
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.content.SharedPreferences
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class HomeViewModel(
    appContext: Context,
    retrogradeDb: RetrogradeDatabase,
    private val coresSelection: CoresSelection,
) : ViewModel() {
    companion object {
        const val CAROUSEL_MAX_ITEMS = 10
        const val DEBOUNCE_TIME = 100L
        private const val PREFS_NAME = "home_download_prefs"
        private const val PREF_DOWNLOAD_DONE = "download_done"
    }

    class Factory(
        val appContext: Context,
        val retrogradeDb: RetrogradeDatabase,
        val coresSelection: CoresSelection,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(appContext, retrogradeDb, coresSelection) as T
        }
    }

    data class UIState(
        val favoritesGames: List<Game> = emptyList(),
        val recentGames: List<Game> = emptyList(),
        val discoveryGames: List<Game> = emptyList(),
        val indexInProgress: Boolean = true,
        val showNoNotificationPermissionCard: Boolean = false,
        val showNoMicrophonePermissionCard: Boolean = false,
        val showNoGamesCard: Boolean = false,
        val showDesmumeDeprecatedCard: Boolean = false,
    )

    sealed class DownloadRomsState {
        object Idle : DownloadRomsState()
        data class Downloading(val progress: Float) : DownloadRomsState()
        data class Extracting(val progress: Float) : DownloadRomsState()
        object Done : DownloadRomsState()
        data class Error(val message: String) : DownloadRomsState()
    }

    private val microphonePermissionEnabledState = MutableStateFlow(true)
    private val notificationsPermissionEnabledState = MutableStateFlow(true)
    private val uiStates = MutableStateFlow(UIState())
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val initialDownloadState: DownloadRomsState =
        if (prefs.getBoolean(PREF_DOWNLOAD_DONE, false)) DownloadRomsState.Done else DownloadRomsState.Idle
    private val _downloadRomsState = MutableStateFlow<DownloadRomsState>(initialDownloadState)

    fun getViewStates(): Flow<UIState> {
        return uiStates
    }

    fun changeLocalStorageFolder(context: Context) {
        StorageFrameworkPickerLauncher.pickFolder(context)
    }

    fun getDownloadRomsState(): Flow<DownloadRomsState> = _downloadRomsState

    fun downloadAndExtractRoms(context: Context) {
        val currentState = _downloadRomsState.value
        if (currentState is DownloadRomsState.Downloading || currentState is DownloadRomsState.Extracting) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Log.d("RomsDownload", "Step 1: getting roms dir")
                    val romsDir = DirectoriesManager(context.applicationContext).getInternalRomsDirectory()
                    Log.d("RomsDownload", "Step 2: romsDir = ${romsDir.absolutePath}")
                    romsDir.mkdirs()
                    Log.d("RomsDownload", "Step 3: mkdirs ok, writable=${romsDir.canWrite()}")
                    val baseUrl = "https://huggingface.co/datasets/luistiagos/roms2/resolve/main/roms.7z."
                    val parts = listOf("001", "002", "003", "004", "005")
                    val partSizes = longArrayOf(104857600, 104857600, 104857600, 104857600, 97254021)
                    val totalDownloadSize = partSizes.sum().toFloat()
                    val combinedFile = File(romsDir, "roms_combined.7z")

                    // Clean up any previous partial downloads
                    parts.forEach { File(romsDir, "roms.7z.$it").delete() }
                    combinedFile.delete()

                    _downloadRomsState.value = DownloadRomsState.Downloading(0f)
                    Log.d("RomsDownload", "Step 4: starting multi-part download, total=${totalDownloadSize.toLong()} bytes")

                    var cumulativeOffset = 0L
                    val partFiles = mutableListOf<File>()
                    parts.forEachIndexed { index, part ->
                        val partOffset = cumulativeOffset.toFloat()
                        val partSize = partSizes[index].toFloat()
                        val partFile = File(romsDir, "roms.7z.$part")
                        Log.d("RomsDownload", "Downloading part $part")
                        downloadFile("${baseUrl}${part}?download=true", partFile) { partProgress ->
                            val totalProgress = (partOffset + partProgress * partSize) / totalDownloadSize
                            _downloadRomsState.value = DownloadRomsState.Downloading(totalProgress)
                        }
                        cumulativeOffset += partSizes[index]
                        partFiles.add(partFile)
                    }

                    Log.d("RomsDownload", "Step 5: concatenating parts into single 7z")
                    _downloadRomsState.value = DownloadRomsState.Extracting(0f)
                    FileOutputStream(combinedFile).use { output ->
                        partFiles.forEach { partFile ->
                            partFile.inputStream().use { it.copyTo(output) }
                            partFile.delete()
                        }
                    }
                    Log.d("RomsDownload", "Step 6: combinedFile size=${combinedFile.length()}, extracting")

                    extractSevenZ(combinedFile, romsDir) { progress ->
                        _downloadRomsState.value = DownloadRomsState.Extracting(progress)
                    }

                    combinedFile.delete()
                    LibraryIndexScheduler.scheduleLibrarySync(context.applicationContext)
                    prefs.edit().putBoolean(PREF_DOWNLOAD_DONE, true).apply()
                    _downloadRomsState.value = DownloadRomsState.Done
                } catch (e: Throwable) {
                    Log.e("RomsDownload", "Download/extract failed", e)
                    _downloadRomsState.value = DownloadRomsState.Error("${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
                }
            }
        }
    }

    private suspend fun downloadFile(url: String, destination: File, onProgress: (Float) -> Unit) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) LemuroidApp/1.0")
            .build()
        Log.d("RomsDownload", "Executing HTTP request to: $url")
        val response = client.newCall(request).execute()
        Log.d("RomsDownload", "HTTP response code: ${response.code}")
        if (!response.isSuccessful) {
            throw IOException("HTTP error ${response.code}: ${response.message}")
        }
        val body = response.body ?: throw IOException("Empty response body")
        val contentLength = body.contentLength()
        Log.d("RomsDownload", "Content-Length: $contentLength, writing to: ${destination.absolutePath}")
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { outputStream ->
            body.byteStream().use { inputStream ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead = 0L
                var n: Int
                while (inputStream.read(buffer).also { n = it } != -1) {
                    outputStream.write(buffer, 0, n)
                    bytesRead += n
                    if (contentLength > 0) onProgress(bytesRead.toFloat() / contentLength)
                }
            }
        }
        Log.d("RomsDownload", "Download complete. File size: ${destination.length()}")
    }

    private fun extractSevenZ(archiveFile: File, destDir: File, onProgress: (Float) -> Unit) {
        val canonicalDest = destDir.canonicalPath
        Log.d("RomsDownload", "Opening 7z: ${archiveFile.absolutePath}")
        SevenZFile.builder().setFile(archiveFile).get().use { sevenZFile ->
            var totalSize = 0L
            for (entry in sevenZFile.entries) {
                if (!entry.isDirectory) totalSize += entry.size
            }
            Log.d("RomsDownload", "7z total uncompressed size: $totalSize bytes")
            var extractedBytes = 0L
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryPath = entry.name.replace('\\', '/')
                    val outFile = File(destDir, entryPath)
                    if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                        Log.w("RomsDownload", "Skipping path traversal: ${entry.name}")
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            val buf = ByteArray(32 * 1024)
                            var n: Int
                            while (sevenZFile.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                extractedBytes += n
                                if (totalSize > 0) onProgress((extractedBytes.toFloat() / totalSize).coerceAtMost(0.99f))
                            }
                        }
                    }
                }
                entry = sevenZFile.nextEntry
            }
        }
        onProgress(1f)
    }

    fun updatePermissions(context: Context) {
        notificationsPermissionEnabledState.value = isNotificationsPermissionGranted(context)
        microphonePermissionEnabledState.value = isMicrophonePermissionGranted(context)
    }

    private fun isNotificationsPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        val permissionResult =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )

        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun isMicrophonePermissionGranted(context: Context): Boolean {
        val permissionResult =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            )

        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun buildViewState(
        favoritesGames: List<Game>,
        recentGames: List<Game>,
        discoveryGames: List<Game>,
        indexInProgress: Boolean,
        notificationsPermissionEnabled: Boolean,
        showMicrophoneCard: Boolean,
        showDesmumeWarning: Boolean,
    ): UIState {
        val noGames = recentGames.isEmpty() && favoritesGames.isEmpty() && discoveryGames.isEmpty()

        return UIState(
            favoritesGames = favoritesGames,
            recentGames = recentGames,
            discoveryGames = discoveryGames,
            indexInProgress = indexInProgress,
            showNoNotificationPermissionCard = !notificationsPermissionEnabled,
            showNoMicrophonePermissionCard = showMicrophoneCard,
            showNoGamesCard = noGames,
            showDesmumeDeprecatedCard = showDesmumeWarning,
        )
    }

    init {
        viewModelScope.launch {
            val uiStatesFlow =
                combine(
                    favoritesGames(retrogradeDb),
                    recentGames(retrogradeDb),
                    discoveryGames(retrogradeDb),
                    indexingInProgress(appContext),
                    notificationsPermissionEnabledState,
                    microphoneNotification(retrogradeDb),
                    desmumeWarningNotification(),
                    ::buildViewState,
                )

            uiStatesFlow
                .debounce(DEBOUNCE_TIME)
                .flowOn(Dispatchers.IO)
                .collect { uiStates.value = it }
        }
    }

    private fun indexingInProgress(appContext: Context) =
        PendingOperationsMonitor(appContext).anyLibraryOperationInProgress()

    private fun discoveryGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao().selectFirstNotPlayed(CAROUSEL_MAX_ITEMS)

    private fun recentGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao().selectFirstUnfavoriteRecents(CAROUSEL_MAX_ITEMS)

    private fun favoritesGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao().selectFirstFavorites(CAROUSEL_MAX_ITEMS)

    private fun dsGamesCount(retrogradeDb: RetrogradeDatabase): Flow<Int> {
        return retrogradeDb.gameDao().selectSystemsWithCount()
            .map { systems ->
                systems
                    .firstOrNull { it.systemId == SystemID.NDS.dbname }
                    ?.count
                    ?: 0
            }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun microphoneNotification(db: RetrogradeDatabase): Flow<Boolean> {
        return microphonePermissionEnabledState
            .flatMapLatest { isMicrophoneEnabled ->
                if (isMicrophoneEnabled) {
                    flowOf(false)
                } else {
                    combine(
                        coresSelection.getSelectedCores(),
                        dsGamesCount(db),
                    ) { cores, dsCount ->
                        cores.any { it.coreConfig.supportsMicrophone } &&
                            dsCount > 0
                    }
                }
                    .distinctUntilChanged()
            }
    }

    private fun desmumeWarningNotification(): Flow<Boolean> {
        return coresSelection.getSelectedCores()
            .map { cores -> cores.any { it.coreConfig.coreID == CoreID.DESMUME } }
            .distinctUntilChanged()
    }
}
