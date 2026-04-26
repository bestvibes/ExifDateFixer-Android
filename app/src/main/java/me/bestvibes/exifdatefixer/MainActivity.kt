package me.bestvibes.exifdatefixer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import kotlinx.coroutines.*
import java.io.IOException


class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private val PERMS_RETURN: Int = 12345
    private val FILE_PICK_INTENT: Int = 12346
    private val DIR_PICK_INTENT: Int = 12347

    private lateinit var fileListText: TextView
    private lateinit var statusText: TextView
    private lateinit var commandText: TextView
    private lateinit var outputText: TextView
    private lateinit var commandOptions: RadioGroup
    private lateinit var customCommandValue: EditText
    private lateinit var customCommandRadio: RadioButton
    private lateinit var filePickerButton: Button
    private lateinit var dirPickerButton: Button
    private lateinit var runButton: Button

    private lateinit var disclaimerPrefs: DisclaimerPrefs
    private lateinit var commandHistory: CommandHistory

    private data class SelectedFile(val uri: Uri, val displayName: String)

    private var baseUserArgs: List<String> = emptyList()
    private var selectedFiles: List<SelectedFile> = emptyList()
    private var dirSelected = false
    private var selectedDir: String = ""
    private var lastSanitizerError: String? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        disclaimerPrefs = DisclaimerPrefs(applicationContext)
        commandHistory = CommandHistory(applicationContext)

        fileListText = findViewById(R.id.file_list_text)
        statusText = findViewById(R.id.status_text)
        commandText = findViewById(R.id.command_text)
        outputText = findViewById(R.id.output_text)
        commandOptions = findViewById(R.id.command_options)
        customCommandValue = findViewById(R.id.command_option_custom_value)
        customCommandRadio = findViewById(R.id.command_option_custom)
        filePickerButton = findViewById(R.id.file_picker_button)
        dirPickerButton = findViewById(R.id.dir_picker_button)
        runButton = findViewById(R.id.run_button)

        applyAdvancedModeVisibility()
        refreshUI()

        customCommandValue.doOnTextChanged { _, _, _, _ -> onCommandOptionSelected(null) }

        customCommandRadio.setOnClickListener {
            if (!disclaimerPrefs.hasAcceptedAdvancedWarning) {
                customCommandRadio.isChecked = false
                showAdvancedWarning()
            } else {
                onCommandOptionSelected(it)
            }
        }

        filePickerButton.setOnClickListener {
            startActivityForResult(FileUtils.makeImagePickerIntent(), FILE_PICK_INTENT)
        }

        dirPickerButton.setOnClickListener {
            startActivityForResult(FileUtils.makeDirectoryPickerIntent(), DIR_PICK_INTENT)
        }

        runButton.setOnClickListener {
            launch {
                lockControls()
                runExifTool()
                unlockControls()
            }
        }

        Log.d("MainActivity", "Files: ${applicationContext.filesDir}")
        Log.d("MainActivity", "Native: ${applicationInfo.nativeLibraryDir}")
        Log.d("MainActivity", "External: ${Environment.getExternalStorageDirectory()}")

        // Sweep cache dirs from runs that didn't reach their finally block (force-stop, OOM).
        launch(Dispatchers.IO) {
            cacheDir.listFiles { f -> f.name.startsWith("run_") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!disclaimerPrefs.hasAcceptedDisclaimer) {
            showFirstRunDisclaimer()
        } else {
            requestPermissions()
        }
        // Eagerly extract perl5.tar and (re-)wire XS symlinks. On every install,
        // Android randomizes nativeLibraryDir so previous symlinks become dead.
        // This is idempotent and cheap; doing it on every onResume() ensures the
        // app is always ready to run exiftool without a first-run delay.
        launch(Dispatchers.IO) {
            try { AssetExtractor.ensureInstalled(applicationContext) }
            catch (e: Exception) { Log.e("MainActivity", "asset install failed", e) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    private fun showFirstRunDisclaimer() {
        // Plain-text version of DISCLAIMER.md, curated for AlertDialog (which
        // doesn't render markdown). The .md file is the authoritative repo-level
        // copy; this asset mirrors it in display-friendly prose.
        val text = assets.open("DISCLAIMER.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { d, _ ->
                disclaimerPrefs.hasAcceptedDisclaimer = true
                d.dismiss()
                requestPermissions()
            }
            .setNegativeButton(R.string.disclaimer_decline) { _, _ -> finish() }
            .show()
    }

    private fun showAdvancedWarning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.advanced_warning_title)
            .setMessage(R.string.advanced_warning_message)
            .setCancelable(false)
            .setPositiveButton(R.string.advanced_warning_accept) { d, _ ->
                disclaimerPrefs.hasAcceptedAdvancedWarning = true
                disclaimerPrefs.advancedModeEnabled = true
                applyAdvancedModeVisibility()
                customCommandRadio.isChecked = true
                onCommandOptionSelected(null)
                d.dismiss()
            }
            .setNegativeButton(R.string.advanced_warning_decline) { d, _ -> d.dismiss() }
            .show()
    }

    private fun applyAdvancedModeVisibility() {
        val visible = disclaimerPrefs.advancedModeEnabled
        // Always show the radio so users can opt in; gate the EditText below.
        customCommandRadio.visibility = View.VISIBLE
        customCommandValue.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun requestPermissions() {
        // READ_EXTERNAL_STORAGE only meaningful on API <= 32; on API 33+ media access
        // is via per-file SAF grants. WRITE_EXTERNAL_STORAGE is not requested anymore;
        // exiftool writes through the picker-granted file paths directly.
        if (Build.VERSION.SDK_INT > 32) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMS_RETURN
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMS_RETURN) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, "File permissions denied!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK_INTENT) {
            val uris = if (resultCode == Activity.RESULT_OK) extractUris(data) else emptyList()
            updateSelectedFiles(uris.map { SelectedFile(it, displayNameForUri(it)) })
        } else if (requestCode == DIR_PICK_INTENT) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                updateSelectedDir(FileUtils.getDirectoryPathFromUri(this, data.data!!))
            }
        }
    }

    private fun extractUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        data.clipData?.let { cd ->
            return (0 until cd.itemCount).map { cd.getItemAt(it).uri }
        }
        return listOfNotNull(data.data)
    }

    private fun displayNameForUri(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun readUriLastModified(uri: Uri): Long? {
        contentResolver.query(uri, arrayOf("last_modified", "date_modified"), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    for (col in arrayOf("last_modified", "date_modified")) {
                        val idx = c.getColumnIndex(col)
                        if (idx >= 0) {
                            val v = c.getLong(idx)
                            // MediaStore returns date_modified in seconds; SAF in millis.
                            return if (v > 0 && v < 4_000_000_000L) v * 1000L else v
                        }
                    }
                }
            }
        return null
    }

    private fun refreshUI() {
        updateStatus()
        updateRunButtonVisibility()
        customCommandValue.isEnabled =
            commandOptions.checkedRadioButtonId == R.id.command_option_custom
    }

    private fun lockControls() {
        filePickerButton.isEnabled = false
        dirPickerButton.isEnabled = false
        runButton.isEnabled = false
        for (i in 0 until commandOptions.childCount) {
            commandOptions.getChildAt(i).isEnabled = false
        }
        customCommandValue.isEnabled = false
    }

    private fun unlockControls() {
        filePickerButton.isEnabled = true
        dirPickerButton.isEnabled = true
        runButton.isEnabled = true
        for (i in 0 until commandOptions.childCount) {
            commandOptions.getChildAt(i).isEnabled = true
        }
        customCommandValue.isEnabled =
            commandOptions.checkedRadioButtonId == R.id.command_option_custom
    }

    private fun isTargetSelected(): Boolean =
        (!dirSelected && selectedFiles.isNotEmpty()) || (dirSelected && selectedDir.isNotBlank())

    private fun updateRunButtonVisibility() {
        val optionSelected = commandOptions.checkedRadioButtonId != -1
        runButton.visibility = if (!isTargetSelected() || !optionSelected || lastSanitizerError != null) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun updateStatus() {
        val optionSelected = commandOptions.checkedRadioButtonId != -1
        when {
            lastSanitizerError != null -> {
                statusText.text = lastSanitizerError
            }
            !isTargetSelected() -> statusText.setText(R.string.status_no_files)
            !optionSelected -> statusText.setText(R.string.status_select_option)
            else -> statusText.setText(R.string.status_ready)
        }
    }

    private fun updateSelectedFiles(newFiles: List<SelectedFile>) {
        dirSelected = false
        selectedFiles = newFiles
        fileListText.text = newFiles.joinToString(
            "\n", "${newFiles.size} FILE(S) SELECTED:\n"
        ) { it.displayName }
        refreshUI()
        generateAndShowPerlCommand()
    }

    private fun updateSelectedDir(newDir: String) {
        dirSelected = true
        selectedDir = newDir
        fileListText.text = selectedDir
        refreshUI()
        generateAndShowPerlCommand()
    }

    private fun generateAndShowPerlCommand() {
        lastSanitizerError = null
        if (!isTargetSelected()) {
            commandText.text = ""
            updateRunButtonVisibility()
            return
        }

        val presetArgs: List<String>? = when (commandOptions.checkedRadioButtonId) {
            R.id.command_option_info -> emptyList()
            R.id.command_option_check_date -> listOf("-datetimeoriginal")
            R.id.command_option_filename ->
                listOf("-datetimeoriginal<filename", "-overwrite_original")
            R.id.command_option_modified ->
                listOf("-datetimeoriginal<filemodifydate", "-overwrite_original")
            R.id.command_option_custom -> {
                when (val r = CommandSanitizer.sanitize(customCommandValue.text.toString())) {
                    is CommandSanitizer.Result.Ok -> r.tokens
                    is CommandSanitizer.Result.Rejected -> {
                        lastSanitizerError = r.reason
                        commandText.text = ""
                        updateStatus()
                        updateRunButtonVisibility()
                        return
                    }
                }
            }
            else -> null
        }
        if (presetArgs == null) {
            commandText.text = ""
            updateRunButtonVisibility()
            return
        }

        baseUserArgs = if (dirSelected) listOf("-r") + presetArgs else presetArgs
        commandText.text = resources.getString(R.string.command_template, baseUserArgs.joinToString(" "))
        updateStatus()
        updateRunButtonVisibility()
    }

    private fun isWriteMode(): Boolean = when (commandOptions.checkedRadioButtonId) {
        R.id.command_option_filename, R.id.command_option_modified -> true
        // Custom mode: trust the user's input; if they used -overwrite_original
        // the cache copy still gets written and we'll attempt write-back. If
        // not, the write-back is a no-op.
        R.id.command_option_custom -> baseUserArgs.any { it == "-overwrite_original" }
        else -> false
    }

    fun onCommandOptionSelected(@Suppress("UNUSED_PARAMETER") v: View?) {
        refreshUI()
        generateAndShowPerlCommand()
    }

    private suspend fun runExifTool() {
        withContext(Dispatchers.Main) {
            if (!isTargetSelected()) return@withContext
            if (lastSanitizerError != null) return@withContext

            if (!ExifToolRunner.isInstalled(applicationContext)) {
                statusText.setText(R.string.status_installing)
                try {
                    AssetExtractor.ensureInstalled(applicationContext)
                } catch (e: IOException) {
                    statusText.setText(R.string.status_error)
                    outputText.text = e.toString()
                    return@withContext
                }
            }

            statusText.setText(R.string.status_running)
            val writeMode = isWriteMode()

            // SAF picker URIs aren't filesystem paths the app can read/write
            // directly under modern Android scoped storage, and ProcessBuilder
            // closes fds > 2 before exec so /proc/self/fd/N passing doesn't
            // work either. So: materialize each URI as a file in cacheDir,
            // run exiftool, copy back to the URI for write modes.
            //
            // Cache files keep their original names (some exiftool modes parse
            // them, e.g. -datetimeoriginal<filename), and live in a per-run
            // subdir so concurrent runs don't collide.
            val runDir = File(cacheDir, "run_${System.nanoTime()}").apply { mkdirs() }
            val cachedFiles: MutableList<Pair<File, Uri>> = mutableListOf()
            val targets: List<String> = try {
                if (dirSelected) {
                    listOf(selectedDir)
                } else {
                    selectedFiles.map { sf ->
                        val cached = File(runDir, sf.displayName)
                        contentResolver.openInputStream(sf.uri).use { input ->
                            if (input == null) throw IOException("Could not open ${sf.uri}")
                            cached.outputStream().use { input.copyTo(it) }
                        }
                        readUriLastModified(sf.uri)?.let { cached.setLastModified(it) }
                        cachedFiles.add(cached to sf.uri)
                        cached.absolutePath
                    }
                }
            } catch (e: Exception) {
                runDir.deleteRecursively()
                statusText.setText(R.string.status_error)
                outputText.text = "Failed to copy file: ${e.message}"
                return@withContext
            }

            val realCommand = ExifToolRunner.buildCommand(applicationContext, baseUserArgs, targets)
            commandHistory.append(realCommand)
            Log.d("MainActivity", "Running: $realCommand")

            var error = false
            val commandOutput = try {
                ExifToolRunner.run(realCommand)
            } catch (e: Exception) {
                error = true
                Log.e("MainActivity", "exiftool exec failed", e)
                e.toString()
            } finally {
                if (writeMode && !error) {
                    for ((cached, uri) in cachedFiles) {
                        try {
                            contentResolver.openOutputStream(uri, "wt").use { out ->
                                if (out == null) throw IOException("openOutputStream returned null")
                                cached.inputStream().use { it.copyTo(out) }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "write-back failed for $uri", e)
                            error = true
                        }
                    }
                }
                runDir.deleteRecursively()
            }

            // Logcat truncates lines >4kB so chunk the output.
            commandOutput.chunked(3500).forEachIndexed { i, chunk ->
                Log.d("MainActivity", "exiftool output[$i]: $chunk")
            }

            statusText.setText(if (!error) R.string.status_succeeded else R.string.status_error)
            outputText.text = if (commandOutput.isNotBlank()) commandOutput else "No output!"
        }
    }
}
