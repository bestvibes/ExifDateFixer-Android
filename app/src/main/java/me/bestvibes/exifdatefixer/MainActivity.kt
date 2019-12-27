package me.bestvibes.exifdatefixer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*


class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private val PERMS_RETURN: Int = 12345
    private val FILE_PICK_INTENT: Int = 12346

    private lateinit var fileListText: TextView
    private lateinit var outputText: TextView
    private lateinit var filePickerButton: Button
    private lateinit var runButton: Button

    private var selectedFiles: ArrayList<String> = arrayListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fileListText = findViewById(R.id.file_list_text)
        outputText = findViewById(R.id.output_text)
        filePickerButton = findViewById(R.id.file_picker_button)
        runButton = findViewById(R.id.run_button)

        filePickerButton.setOnClickListener {
            startActivityForResult(FileUtils.makeImagePickerIntent(), FILE_PICK_INTENT)
        }

        runButton.setOnClickListener {
            launch {
                runExifTool()
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
            selectedFiles.clear()
            if (resultCode == Activity.RESULT_OK) {
                val uris: ArrayList<Uri> = arrayListOf()

                data?.clipData?.let {
                    for (i in 0 until it.itemCount) {
                        uris.add(it.getItemAt(i).uri)
                    }
                }

                selectedFiles = FileUtils.convertUrisToFilePaths(contentResolver, uris)

                fileListText.text = selectedFiles.joinToString("\n", "Selected files:\n")
            }
        } else {
            Toast.makeText(this, "File picker failed!", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun runExifTool() {
        if (selectedFiles.size == 0) {
            Toast.makeText(this, "No files selected!", Toast.LENGTH_SHORT).show()
            return
        }

        PerlUtils.installPerlAndExifTool(this)

        val command = StringBuilder()
        command.append(selectedFiles.joinToString(" ", " "))

        val commandOutput = try {
            PerlUtils.runExiftoolCommand(this, command.toString())
//            PerlUtils.runPerlCommand(this, "--version")
        } catch (e: Exception) {
            e.toString()
        }

        withContext(Dispatchers.Main) {
            outputText.text = "Output:\n${commandOutput}"
        }
    }
}
