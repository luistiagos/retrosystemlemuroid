package com.swordfish.lemuroid.app.shared.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.utils.android.displayErrorDialog
import com.swordfish.lemuroid.lib.android.RetrogradeActivity
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class StorageFrameworkPickerLauncher : RetrogradeActivity() {
    @Inject
    lateinit var directoriesManager: DirectoriesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    this.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    this.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    this.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    this.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        this.putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:"
                            )
                        )
                    }
                }
            try {
                startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER)
            } catch (e: Exception) {
                showStorageAccessFrameworkNotSupportedDialog()
            }
        }
    }

    private fun showStorageAccessFrameworkNotSupportedDialog() {
        val message = getString(R.string.dialog_saf_not_found, directoriesManager.getInternalRomsDirectory())
        val actionLabel = getString(R.string.ok)
        displayErrorDialog(message, actionLabel) { finish() }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        resultData: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (requestCode != REQUEST_CODE_PICK_FOLDER || resultCode != Activity.RESULT_OK) {
            finish()
            return
        }

        val sharedPreferences = SharedPreferencesHelper.getLegacySharedPreferences(this)
        val preferenceKey = getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder)
        val currentValue: String? = sharedPreferences.getString(preferenceKey, null)
        val newValue = resultData?.data

        if (newValue == null || newValue.toString() == currentValue) {
            startLibraryIndexWork()
            finish()
            return
        }

        updatePersistableUris(newValue)

        val romsDir = directoriesManager.getInternalRomsDirectory()
        val romsFiles = romsDir.walkTopDown().filter { it.isFile }.toList()

        if (romsFiles.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_move_roms_title))
                .setMessage(getString(R.string.settings_move_roms_message, romsFiles.size))
                .setPositiveButton(getString(R.string.settings_move_roms_yes)) { _, _ ->
                    moveRomsAndFinish(romsDir, romsFiles, newValue, sharedPreferences, preferenceKey)
                }
                .setNegativeButton(getString(R.string.settings_move_roms_cancel_operation)) { _, _ ->
                    // User cancelled — do not change directory, just close.
                    finish()
                }
                .setCancelable(false)
                .show()
        } else {
            sharedPreferences.edit().putString(preferenceKey, newValue.toString()).apply()
            startLibraryIndexWork()
            finish()
        }
    }

    private fun moveRomsAndFinish(
        sourceDir: File,
        romsFiles: List<File>,
        destUri: Uri,
        prefs: SharedPreferences,
        prefKey: String,
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_transferring_roms_title))
            .setMessage(getString(R.string.settings_transferring_roms_progress, 0))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val destTree = checkNotNull(DocumentFile.fromTreeUri(this@StorageFrameworkPickerLauncher, destUri))
                val total = romsFiles.size
                romsFiles.forEachIndexed { index, file ->
                    withContext(Dispatchers.IO) {
                        copyFileToSaf(file, sourceDir, destTree)
                    }
                    val pct = ((index + 1) * 100) / total
                    dialog.setMessage(getString(R.string.settings_transferring_roms_progress, pct))
                }
                withContext(Dispatchers.IO) { sourceDir.deleteRecursively() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to move ROMs")
            }
            dialog.dismiss()
            prefs.edit().putString(prefKey, destUri.toString()).apply()
            startLibraryIndexWork()
            finish()
        }
    }

    private fun copyFileToSaf(file: File, sourceRoot: File, destTree: DocumentFile) {
        val parts = file.relativeTo(sourceRoot).path.split(File.separator).filter { it.isNotEmpty() }
        var currentDoc = destTree
        parts.dropLast(1).forEach { dirName ->
            currentDoc = currentDoc.findFile(dirName) ?: currentDoc.createDirectory(dirName) ?: return
        }
        val destFile = currentDoc.createFile("application/octet-stream", file.name) ?: return
        contentResolver.openOutputStream(destFile.uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
    }

    private fun updatePersistableUris(uri: Uri) {
        contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .filter { it.uri != uri }
            .forEach {
                contentResolver.releasePersistableUriPermission(
                    it.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }

        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    private fun startLibraryIndexWork() {
        LibraryIndexScheduler.scheduleLibrarySync(applicationContext)
    }

    companion object {
        private const val TAG = "StoragePicker"
        private const val REQUEST_CODE_PICK_FOLDER = 1

        fun pickFolder(context: Context) {
            context.startActivity(Intent(context, StorageFrameworkPickerLauncher::class.java))
        }
    }
}
