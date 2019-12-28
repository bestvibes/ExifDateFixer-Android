package me.bestvibes.exifdatefixer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.IOException


class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private val PERMS_RETURN: Int = 12345
    private val FILE_PICK_INTENT: Int = 12346

    private lateinit var fileListText: TextView
    private lateinit var statusText: TextView
    private lateinit var commandText: TextView
    private lateinit var outputText: TextView
    private lateinit var commandOptions: RadioGroup
    private lateinit var filePickerButton: Button
    private lateinit var runButton: Button

    private var perlCommand: ArrayList<String> = arrayListOf()
    private var selectedFiles: ArrayList<String> = arrayListOf()

    private val implementedOptions = listOf(
        R.id.command_option_info,
        R.id.command_option_check_date,
        R.id.command_option_filename,
        R.id.command_option_modified)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fileListText = findViewById(R.id.file_list_text)
        statusText = findViewById(R.id.status_text)
        commandText = findViewById(R.id.command_text)
        outputText = findViewById(R.id.output_text)
        commandOptions = findViewById(R.id.command_options)
        filePickerButton = findViewById(R.id.file_picker_button)
        runButton = findViewById(R.id.run_button)

        fileListText.movementMethod = ScrollingMovementMethod()
        statusText.movementMethod = ScrollingMovementMethod()
        commandText.movementMethod = ScrollingMovementMethod()
        outputText.movementMethod = ScrollingMovementMethod()

        updateRunButtonVisibility()

        filePickerButton.setOnClickListener {
            startActivityForResult(FileUtils.makeImagePickerIntent(), FILE_PICK_INTENT)
        }

        runButton.setOnClickListener {
            launch {
                lockControls()
                runExifTool()
                unlockControls()
            }
        }

        Log.d("MainActivity", "Files: ${FileUtils.getFilesInstallDir(this)}")
        Log.d("MainActivity", "ABI: ${PerlUtils.getAbiToUse()}")
        Log.d("MainActivity", "External: ${Environment.getExternalStorageDirectory()}")
    }

    override fun onResume() {
        super.onResume()
        requestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    PERMS_RETURN)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMS_RETURN) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, "File permissions denied!", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this, "File permissions granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK_INTENT) {
            if (resultCode == Activity.RESULT_OK) {
                val uris: ArrayList<Uri> = arrayListOf()

                data?.clipData?.let {
                    for (i in 0 until it.itemCount) {
                        uris.add(it.getItemAt(i).uri)
                    }
                }

                updateSelectedFiles(FileUtils.convertUrisToFilePaths(contentResolver, uris))
            } else {
                updateSelectedFiles(arrayListOf())
            }
        }
    }

    private fun refreshUI() {
        updateStatus()
        updateRunButtonVisibility()
        outputText.text = ""
    }

    private fun lockControls() {
        filePickerButton.isEnabled = false
        runButton.isEnabled = false
        commandOptions.isEnabled = false
    }

    private fun unlockControls() {
        filePickerButton.isEnabled = true
        runButton.isEnabled = true
        commandOptions.isEnabled = true
    }

    private fun updateRunButtonVisibility() {
        val filesSelected = selectedFiles.size > 0
        val optionSelected = commandOptions.checkedRadioButtonId != -1
        val validOptionSelected = commandOptions.checkedRadioButtonId in implementedOptions

        val runButtonVisibility = if (!filesSelected || !optionSelected || !validOptionSelected) {
            View.GONE
        } else {
            View.VISIBLE
        }

        runButton.visibility = runButtonVisibility
    }

    private fun updateStatus() {
        val filesSelected = selectedFiles.size > 0
        val optionSelected = commandOptions.checkedRadioButtonId != -1
        val validOptionSelected = commandOptions.checkedRadioButtonId in implementedOptions

        val statusStringId = if (!filesSelected) {
            R.string.status_no_files
        } else if (!optionSelected){
            R.string.status_select_option
        } else if (!validOptionSelected) {
            R.string.status_not_implemented
        } else {
            R.string.status_ready
        }

        statusText.setText(statusStringId)
    }

    private fun updateSelectedFiles(newFiles: ArrayList<String>) {
        selectedFiles = newFiles
        fileListText.text = selectedFiles.joinToString("\n")
        refreshUI()
        generateAndShowPerlCommand()
    }

    private fun generateAndShowPerlCommand() {
        if (selectedFiles.size == 0) {
            return
        }

        val exiftoolArgs = arrayListOf<String>()

        val flags = when (commandOptions.checkedRadioButtonId) {
            R.id.command_option_info -> ""
            R.id.command_option_check_date -> "-datetimeoriginal"
            R.id.command_option_filename -> "-datetimeoriginal<filename -overwrite_original"
            R.id.command_option_modified -> "-datetimeoriginal<filemodifydate -overwrite_original"
            else -> null
        }

        flags?.let {
            if (it.isNotBlank()) {
                exiftoolArgs.addAll(it.split(" "))
            }
        } ?: run {
            commandText.text = ""
            return
        }

        val displayExiftoolArgs = exiftoolArgs.toList()
        exiftoolArgs.addAll(selectedFiles)

        perlCommand = PerlUtils.buildExiftoolCommand(this, exiftoolArgs)
        Log.d("MainActivity", "New command: $perlCommand")
        val exiftoolArgsString = displayExiftoolArgs.joinToString(" ")
        commandText.text = resources.getString(R.string.command_template, exiftoolArgsString)
    }

    fun onCommandOptionSelected(@Suppress("UNUSED_PARAMETER") v: View) {
        refreshUI()
        generateAndShowPerlCommand()
    }

    private suspend fun runExifTool() {
        withContext(Dispatchers.Main) {
            if (selectedFiles.size == 0) {
                return@withContext
            }

            if (!PerlUtils.isPerlInstalled(applicationContext) || !PerlUtils.isExiftoolInstalled(applicationContext)) {
                statusText.setText(R.string.status_installing)
                try {
                    PerlUtils.installPerlAndExifTool(applicationContext)
                } catch (e: IOException) {
                    statusText.setText(R.string.status_error)
                    outputText.text = e.toString()
                    return@withContext
                }
            }

            var error = false
            statusText.setText(R.string.status_running)
            val commandOutput = try {
                ProcessUtils.runCommand(perlCommand)
            } catch (e: Exception) {
                error = true
                e.toString()
            }

            statusText.setText(if (!error) R.string.status_succeeded else R.string.status_error)
            outputText.text = if (commandOutput.isNotBlank()) commandOutput else "No output!"
        }
    }
}
