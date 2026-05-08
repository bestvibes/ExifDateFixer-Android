package me.bestvibes.exiftoolwrapper

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Owns the resolve→cache→exec→writeback pipeline for a [Command] and a set
 * of [SlotBinding]s. Pure orchestration with no UI dependencies — MainActivity
 * is the orchestrator, this is the engine.
 *
 * Safety invariants:
 *   - Only [SlotRole.TARGET] bindings are written back to their URIs.
 *   - Read-only slots (Source, Sidecar, OutputDir) materialize to cache for
 *     exiftool to read but never get written back.
 *   - The per-run cache directory is always cleaned up, even on exception
 *     (try/finally around the entire pipeline).
 *   - Failures during materialize/exec/writeback are reported with the slot
 *     label so users know which file caused the problem.
 */
class CommandRunner(private val context: Context) {

    /** A single slot's user-supplied bindings — one URI per file picked. */
    data class SlotBinding(
        val slot: FileSlot,
        val uris: List<Uri>,
        /**
         * If true, the user picked this slot as a directory tree (SAF tree
         * URI) rather than individual files. When any Target slot is bound
         * as a folder, exiftool gets `-r` to recurse.
         */
        val isFolder: Boolean = false,
    )

    sealed class Outcome {
        data class Success(val exitOutput: String, val argv: List<String>) : Outcome()
        data class Failure(val message: String, val argv: List<String>?, val exitOutput: String?) : Outcome()
    }

    /** Snapshot of the resolved argv with placeholder spans for unbound slots. */
    data class Preview(val argv: List<PreviewToken>)

    sealed class PreviewToken {
        data class Bound(val value: String) : PreviewToken()
        /** Unbound slot — caller resolves [key] to a label for display. */
        data class Unbound(val key: SlotKey) : PreviewToken()
        /** Composite token containing one or more unbound slots, rendered with the caller's label. */
        data class CompositeUnbound(
            val parts: List<Either>,
        ) : PreviewToken() {
            sealed class Either {
                data class Text(val value: String) : Either()
                data class Slot(val key: SlotKey) : Either()
            }
        }
    }

    /**
     * Build a structured preview of the argv with placeholders for any
     * unbound slots. Callers resolve [PreviewToken.Unbound.key] to a display
     * label via their own logic (e.g. context-aware "Destination" vs "Files").
     */
    fun preview(command: Command, bindings: Map<SlotKey, SlotBinding>): Preview {
        val tokens = mutableListOf<PreviewToken>()
        val needsRecurse = command.slots
            .filter { it.key.role == SlotRole.TARGET }
            .any { bindings[it.key]?.isFolder == true }
        if (needsRecurse) tokens += PreviewToken.Bound("-r")

        for (tok in command.template) {
            when (tok) {
                is Token.Literal -> tokens += PreviewToken.Bound(tok.value)
                is Token.SlotRef -> {
                    val b = bindings[tok.key]
                    if (b == null || b.uris.isEmpty()) {
                        tokens += PreviewToken.Unbound(tok.key)
                    } else {
                        for (uri in b.uris) {
                            tokens += PreviewToken.Bound(displayName(uri))
                        }
                    }
                }
                is Token.Composite -> {
                    val parts = mutableListOf<PreviewToken.CompositeUnbound.Either>()
                    var allBound = true
                    val sb = StringBuilder()
                    for (part in tok.parts) {
                        when (part) {
                            is Token.Composite.Part.Text -> {
                                parts += PreviewToken.CompositeUnbound.Either.Text(part.value)
                                sb.append(part.value)
                            }
                            is Token.Composite.Part.Slot -> {
                                val b = bindings[part.key]
                                if (b == null || b.uris.isEmpty()) {
                                    allBound = false
                                    parts += PreviewToken.CompositeUnbound.Either.Slot(part.key)
                                } else {
                                    val resolved = displayName(b.uris.first())
                                    parts += PreviewToken.CompositeUnbound.Either.Text(resolved)
                                    sb.append(resolved)
                                }
                            }
                        }
                    }
                    tokens += if (allBound) PreviewToken.Bound(sb.toString())
                              else PreviewToken.CompositeUnbound(parts)
                }
            }
        }
        return Preview(tokens)
    }

    /**
     * The slots in [command] that have no URIs bound. Empty list = ready to run.
     */
    fun unboundSlots(command: Command, bindings: Map<SlotKey, SlotBinding>): List<FileSlot> =
        command.slots
            .filter { (bindings[it.key]?.uris ?: emptyList()).isEmpty() }

    /**
     * The full pipeline: materialize bound URIs to cache, build argv, exec
     * exiftool via [ExifToolRunner], write back Target caches to their URIs,
     * clean up. Returns an [Outcome] describing what happened.
     */
    suspend fun run(
        command: Command,
        bindings: Map<SlotKey, SlotBinding>,
    ): Outcome = withContext(Dispatchers.IO) {
        val unbound = unboundSlots(command, bindings)
        if (unbound.isNotEmpty()) {
            return@withContext Outcome.Failure(
                "Slot(s) not bound: ${unbound.joinToString(", ") { it.label() }}", null, null,
            )
        }

        val runDir = File(context.cacheDir, "run_${System.nanoTime()}").apply { mkdirs() }
        // Maps SlotKey → list of (cache File, original URI). Cache files keep
        // their display names because some exiftool flags read the filename
        // (e.g. -datetimeoriginal<filename).
        val cachedBySlot = mutableMapOf<SlotKey, List<Pair<File, Uri>>>()

        try {
            for (slot in command.slots) {
                val b = bindings.getValue(slot.key)
                val cacheList = mutableListOf<Pair<File, Uri>>()
                if (b.isFolder) {
                    // Folder bindings: pass the tree-URI document path directly
                    // to exiftool. Today's FileUtils.getDirectoryPathFromUri
                    // resolves the local-fs path. No materialize needed.
                    // For the runner, we synthesize a single "fake" file pair
                    // whose `cacheFile` is the resolved directory path; no
                    // writeback happens for folders since exiftool writes
                    // in-place inside the dir.
                    val path = FileUtils.getDirectoryPathFromUri(context, b.uris.first())
                    cacheList += File(path) to b.uris.first()
                } else {
                    for (uri in b.uris) {
                        val name = sanitizeFileName(displayName(uri))
                        val cached = File(runDir, "${slot.key.role.tokenName}_${cacheList.size}_$name")
                        try {
                            context.contentResolver.openInputStream(uri).use { input ->
                                if (input == null) throw IOException("Could not open ${b.slot.label()}")
                                cached.outputStream().use { input.copyTo(it) }
                            }
                            readUriLastModified(context.contentResolver, uri)?.let {
                                cached.setLastModified(it)
                            }
                        } catch (e: Exception) {
                            return@withContext Outcome.Failure(
                                "Failed to copy ${slot.label()}: ${e.message}", null, null,
                            )
                        }
                        cacheList += cached to uri
                    }
                }
                cachedBySlot[slot.key] = cacheList
            }

            // Build argv. -r prefix if any Target slot was bound as a folder.
            val recurse = command.slots
                .filter { it.key.role == SlotRole.TARGET }
                .any { bindings[it.key]?.isFolder == true }

            val userArgs = mutableListOf<String>()
            if (recurse) userArgs += "-r"

            for (tok in command.template) {
                when (tok) {
                    is Token.Literal -> userArgs += tok.value
                    is Token.SlotRef -> {
                        val files = cachedBySlot.getValue(tok.key)
                        for ((f, _) in files) userArgs += f.absolutePath
                    }
                    is Token.Composite -> {
                        val sb = StringBuilder()
                        for (part in tok.parts) {
                            when (part) {
                                is Token.Composite.Part.Text -> sb.append(part.value)
                                is Token.Composite.Part.Slot -> {
                                    val files = cachedBySlot.getValue(part.key)
                                    // Composite + Many is rejected at parse time, so
                                    // it's safe to take the first file.
                                    sb.append(files.first().first.absolutePath)
                                }
                            }
                        }
                        userArgs += sb.toString()
                    }
                }
            }

            val argv = ExifToolRunner.buildBaseCommand(context, userArgs)
            Log.d(TAG, "Running: $argv")

            val output = try {
                ExifToolRunner.run(argv)
            } catch (e: Exception) {
                Log.e(TAG, "exiftool exec failed", e)
                return@withContext Outcome.Failure("exiftool failed: ${e.message}", argv, null)
            }

            // Write-back: only Target slots, and only if not folder (folders
            // are operated on in place via the resolved fs path).
            val writebackErrors = mutableListOf<String>()
            for ((slotKey, files) in cachedBySlot) {
                val slot = command.slots.first { it.key == slotKey }
                if (!slot.key.role.isWriteBack) continue
                val binding = bindings.getValue(slotKey)
                if (binding.isFolder) continue
                for ((cached, uri) in files) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt").use { out ->
                            if (out == null) throw IOException("openOutputStream returned null")
                            cached.inputStream().use { it.copyTo(out) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "writeback failed for $uri", e)
                        writebackErrors += "${slot.label()} (${displayName(uri)}): ${e.message}"
                    }
                }
            }

            if (writebackErrors.isNotEmpty()) {
                return@withContext Outcome.Failure(
                    "Write-back failed:\n${writebackErrors.joinToString("\n")}",
                    argv, output,
                )
            }
            return@withContext Outcome.Success(output, argv)
        } finally {
            runDir.deleteRecursively()
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun sanitizeFileName(name: String): String =
        // Keep the name on a single fs-safe path component. Replace anything that
        // could be a separator or shell-funky char with underscore. We never
        // shell out, but keeping cache filenames simple avoids surprises in
        // exiftool error messages.
        name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(120)

    private fun readUriLastModified(resolver: ContentResolver, uri: Uri): Long? {
        resolver.query(uri, arrayOf("last_modified", "date_modified"), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    for (col in arrayOf("last_modified", "date_modified")) {
                        val idx = c.getColumnIndex(col)
                        if (idx >= 0) {
                            val v = c.getLong(idx)
                            return if (v in 1 until 4_000_000_000L) v * 1000L else v
                        }
                    }
                }
            }
        return null
    }

    companion object {
        private const val TAG = "CommandRunner"
    }
}
