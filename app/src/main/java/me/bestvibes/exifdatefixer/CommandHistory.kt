package me.bestvibes.exifdatefixer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only audit log of every exiftool invocation, capped at MAX_BYTES.
 * Lets the user see exactly what was sent to exiftool — the "you ran it,
 * here's what was sent" trail that makes the custom-command field defensible.
 */
class CommandHistory(context: Context) {
    private val file: File = File(context.filesDir, FILE_NAME)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun append(command: List<String>) {
        if (file.exists() && file.length() > MAX_BYTES) {
            // Drop the first half so we keep recent entries.
            val keep = file.readText().substringAfter('\n', "")
            file.writeText(keep)
        }
        val ts = timestampFormat.format(Date())
        // Render with shell-style quoting so a human reading the log can copy-paste it,
        // even though we never actually invoked a shell.
        val rendered = command.joinToString(" ") { quote(it) }
        file.appendText("$ts $rendered\n")
    }

    fun read(): String = if (file.exists()) file.readText() else ""

    private fun quote(token: String): String {
        if (token.isEmpty()) return "''"
        val safe = token.all { it.isLetterOrDigit() || it in "@%+=:,./-_" }
        if (safe) return token
        return "'" + token.replace("'", "'\\''") + "'"
    }

    companion object {
        private const val FILE_NAME = "command_history.txt"
        private const val MAX_BYTES = 1L * 1024 * 1024
    }
}
