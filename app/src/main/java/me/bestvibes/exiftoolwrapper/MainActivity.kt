package me.bestvibes.exiftoolwrapper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    // -------- view bindings --------
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusBanner: TextView
    private lateinit var presetChipGroup: ChipGroup
    private lateinit var presetDescription: TextView
    private lateinit var customCommandSection: View
    private lateinit var customCommandEdit: TextInputEditText
    private lateinit var customCommandError: TextView
    private lateinit var insertSourceButton: MaterialButton
    private lateinit var insertTargetButton: MaterialButton
    private lateinit var insertSidecarButton: MaterialButton
    private lateinit var savePresetButton: MaterialButton
    private lateinit var slotChipGroup: ChipGroup
    private lateinit var footerWarning: TextView
    private lateinit var previewText: TextView
    private lateinit var outputText: TextView
    private lateinit var runButton: ExtendedFloatingActionButton

    // -------- state --------
    private lateinit var disclaimerPrefs: DisclaimerPrefs
    private lateinit var commandHistory: CommandHistory
    private lateinit var userPresets: UserPresets
    private lateinit var runner: CommandRunner

    private val customPresetId = "custom"
    private var selectedPresetId: String = Presets.SHOW_METADATA.id
    private var customCommandText: String = ""
    private var customParseError: String? = null
    private val bindings: MutableMap<SlotKey, CommandRunner.SlotBinding> = linkedMapOf()
    private var activePickKey: SlotKey? = null
    private var isRunning: Boolean = false

    // -------- launchers --------
    private lateinit var filePickLauncher: ActivityResultLauncher<Intent>

    // ----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setSupportActionBar(toolbar)

        disclaimerPrefs = DisclaimerPrefs(applicationContext)
        commandHistory = CommandHistory(applicationContext)
        userPresets = UserPresets(applicationContext)
        runner = CommandRunner(applicationContext)

        filePickLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleFilePickResult(result.resultCode, result.data)
        }

        wirePresetChips()
        wireCustomCommandToolbar()
        wireCustomEditText()
        wireRunButton()

        // Cleanup any orphan run dirs from a previous crash.
        launch(Dispatchers.IO) {
            cacheDir.listFiles { f -> f.name.startsWith("run_") }
                ?.forEach { it.deleteRecursively() }
        }

        applyActiveCommand()
        Log.d(TAG, "Files: ${applicationContext.filesDir}")
        Log.d(TAG, "Native: ${applicationInfo.nativeLibraryDir}")
    }

    override fun onResume() {
        super.onResume()
        if (!disclaimerPrefs.hasAcceptedDisclaimer) {
            showFirstRunDisclaimer()
        } else {
            requestPermissions()
        }
        launch(Dispatchers.IO) {
            try { AssetExtractor.ensureInstalled(applicationContext) }
            catch (e: Exception) { Log.e(TAG, "asset install failed", e) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_show_advanced)?.isChecked = disclaimerPrefs.advancedModeEnabled
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_show_advanced -> {
                if (!disclaimerPrefs.advancedModeEnabled) {
                    showAdvancedWarning()
                } else {
                    disclaimerPrefs.advancedModeEnabled = false
                    if (selectedPresetId == customPresetId) {
                        selectedPresetId = Presets.SHOW_METADATA.id
                        rebuildPresetChips()
                    }
                    invalidateOptionsMenu()
                    applyActiveCommand()
                }
                true
            }
            R.id.menu_command_history -> {
                showCommandHistory()
                true
            }
            R.id.menu_about -> {
                showAbout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ----------------------------------------------------------------------
    // view setup
    // ----------------------------------------------------------------------

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        statusBanner = findViewById(R.id.status_banner)
        presetChipGroup = findViewById(R.id.preset_chip_group)
        presetDescription = findViewById(R.id.preset_description)
        customCommandSection = findViewById(R.id.custom_command_section)
        customCommandEdit = findViewById(R.id.custom_command_edit)
        customCommandError = findViewById(R.id.custom_command_error)
        insertSourceButton = findViewById(R.id.insert_source_button)
        insertTargetButton = findViewById(R.id.insert_target_button)
        insertSidecarButton = findViewById(R.id.insert_sidecar_button)
        savePresetButton = findViewById(R.id.save_preset_button)
        slotChipGroup = findViewById(R.id.slot_chip_group)
        footerWarning = findViewById(R.id.footer_warning)
        previewText = findViewById(R.id.preview_text)
        outputText = findViewById(R.id.output_text)
        runButton = findViewById(R.id.run_button)
    }

    private fun wirePresetChips() {
        rebuildPresetChips()
    }

    private fun rebuildPresetChips() {
        presetChipGroup.removeAllViews()
        for (preset in Presets.ALL) {
            val chip = makeSelectableChip(
                label = getString(preset.labelRes),
                checked = preset.id == selectedPresetId,
            ) {
                if (selectedPresetId != preset.id) {
                    selectedPresetId = preset.id
                    applyActiveCommand()
                }
            }
            presetChipGroup.addView(chip)
        }
        for (saved in userPresets.list()) {
            val chip = makeSelectableChip(
                label = saved.name,
                checked = saved.id == selectedPresetId,
            ) {
                if (selectedPresetId != saved.id) {
                    selectedPresetId = saved.id
                    applyActiveCommand()
                }
            }
            chip.setOnLongClickListener {
                confirmDeleteSavedPreset(saved)
                true
            }
            presetChipGroup.addView(chip)
        }
        val customChip = if (disclaimerPrefs.advancedModeEnabled) {
            makeSelectableChip(
                label = getString(R.string.preset_custom),
                checked = selectedPresetId == customPresetId,
            ) { selectCustomPreset() }
        } else {
            // Discoverability: show the Custom chip even when advanced is off,
            // but route through the warning.
            makeSelectableChip(
                label = getString(R.string.preset_custom),
                checked = false,
            ) {
                showAdvancedWarning { selectCustomPreset() }
            }
        }
        presetChipGroup.addView(customChip)
    }

    /**
     * Switch into Custom mode and, on first entry with an empty buffer, seed
     * the field with `{target...}` (cursor at 0) so the user immediately sees
     * the trailing Files chip and can type flags ahead of it. The chip
     * mirrors the parser's implicit trailing-target desugar — making it
     * explicit means the saved-preset template captures it verbatim.
     */
    private fun selectCustomPreset() {
        if (selectedPresetId == customPresetId) return
        selectedPresetId = customPresetId
        if (customCommandText.isEmpty()) {
            // Leading space so flags typed at cursor=0 land as
            // "-foo {target...}" rather than "-foo{target...}", which would
            // smush the variadic into a non-standalone token and trip the
            // parser. Cheap, predictable; smarter auto-spacing isn't worth
            // the complexity here.
            customCommandEdit.setText(" {target...}")
            customCommandEdit.requestFocus()
            // EditText snaps the cursor back to the end on focus gain, so
            // defer the start-of-text selection until after focus has
            // settled — otherwise typed characters land *after* the chip.
            customCommandEdit.post { customCommandEdit.setSelection(0) }
            // Pop the soft keyboard so the user can start typing flags
            // immediately. requestFocus alone doesn't reliably do that.
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(customCommandEdit, InputMethodManager.SHOW_IMPLICIT)
            // setText fires the TextWatcher which triggers applyActiveCommand.
        } else {
            applyActiveCommand()
        }
    }

    /** Compact, single-select chip with the project-wide density tweaks applied. */
    private fun makeSelectableChip(
        label: String,
        checked: Boolean,
        onSelected: () -> Unit,
    ): Chip {
        val chip = Chip(this)
        chip.text = label
        chip.isCheckable = true
        chip.isChecked = checked
        chip.isClickable = true
        chip.isCheckedIconVisible = false
        val density = resources.displayMetrics.density
        chip.chipMinHeight = 32f * density
        chip.chipStartPadding = 4f * density
        chip.chipEndPadding = 4f * density
        chip.textStartPadding = 6f * density
        chip.textEndPadding = 6f * density
        // Material Chip reserves space for icons / close-icon even when they're
        // invisible (closeIconEndPadding defaults to 2dp). Zero them out so the
        // text sits symmetrically between chipStartPadding and chipEndPadding.
        chip.iconStartPadding = 0f
        chip.iconEndPadding = 0f
        chip.closeIconStartPadding = 0f
        chip.closeIconEndPadding = 0f
        chip.textSize = 13f
        chip.includeFontPadding = false
        chip.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        chip.setEnsureMinTouchTargetSize(false)
        chip.setOnClickListener { onSelected() }
        return chip
    }

    private fun wireCustomCommandToolbar() {
        insertSourceButton.setOnClickListener { insertSlotToken(SlotRole.SOURCE, null) }
        insertTargetButton.setOnClickListener { insertSlotToken(SlotRole.TARGET, null) }
        insertSidecarButton.setOnClickListener { showSidecarTypePicker() }
        savePresetButton.setOnClickListener { showSavePresetDialog() }
    }

    private fun showSidecarTypePicker() {
        val types = arrayOf("csv", "json", "xmp", "gpx")
        AlertDialog.Builder(this)
            .setTitle("Sidecar type")
            .setItems(types) { _, which -> insertSlotToken(SlotRole.SIDECAR, types[which]) }
            .show()
    }

    private fun insertSlotToken(role: SlotRole, variant: String?) {
        val baseKey = SlotKey(role, variant)
        val text = customCommandEdit.text?.toString() ?: ""
        // If the role/variant already exists, auto-suffix (e.g. {source} → {source:2}).
        val key = if (text.contains(baseKey.tokenText())) {
            // find next free integer suffix
            var n = 2
            while (text.contains(SlotKey(role, n.toString()).tokenText())) n++
            SlotKey(role, n.toString())
        } else baseKey

        val toInsert = key.tokenText()
        val cursor = customCommandEdit.selectionStart.coerceAtLeast(0)
        val needsLeadingSpace = cursor > 0 && text.getOrNull(cursor - 1)?.isWhitespace() == false
        val needsTrailingSpace = cursor < text.length && text.getOrNull(cursor)?.isWhitespace() == false
        val full = buildString {
            if (needsLeadingSpace) append(' ')
            append(toInsert)
            if (needsTrailingSpace) append(' ')
        }
        customCommandEdit.text?.insert(cursor, full)
    }

    private fun wireCustomEditText() {
        installSlotChipDecoration(
            customCommandEdit,
            chipBackground = ContextCompat.getColor(this, R.color.chip_slot_background),
            chipText = ContextCompat.getColor(this, R.color.colorPrimaryDark),
            chipStroke = ContextCompat.getColor(this, R.color.chip_slot_stroke),
            labelFor = ::contextAwareSlotLabel,
            onTextChanged = { newText ->
                customCommandText = newText
                if (selectedPresetId == customPresetId) applyActiveCommand()
            },
        )
    }

    private fun wireRunButton() {
        runButton.setOnClickListener {
            // Defensive double-tap guard.
            if (isRunning) return@setOnClickListener
            attemptRun()
        }
    }

    // ----------------------------------------------------------------------
    // command resolution
    // ----------------------------------------------------------------------

    /** Returns the currently-active Command, or null if the custom field has a parse error. */
    private fun resolveActiveCommand(): Command? {
        if (selectedPresetId == customPresetId) {
            return when (val r = CommandParser.parse(customCommandText)) {
                is CommandParser.Result.Ok -> {
                    customParseError = null
                    r.command
                }
                is CommandParser.Result.Rejected -> {
                    customParseError = r.reason
                    null
                }
            }
        }
        customParseError = null
        Presets.byId(selectedPresetId)?.let { return it.command }
        userPresets.byId(selectedPresetId)?.let { return it.command }
        // Fallback: unknown id — drop back to the default preset.
        return Presets.SHOW_METADATA.command
    }

    /**
     * Recompute the active command, prune dropped bindings, reflow slot chips,
     * update preview, and refresh status. Called whenever preset/custom-text/binding
     * state changes.
     */
    private fun applyActiveCommand() {
        val cmd = resolveActiveCommand()

        // Show/hide custom section and parse-error label.
        val isCustom = selectedPresetId == customPresetId
        customCommandSection.visibility = if (isCustom) View.VISIBLE else View.GONE

        if (customParseError != null && isCustom) {
            customCommandError.text = customParseError
            customCommandError.visibility = View.VISIBLE
        } else {
            customCommandError.visibility = View.GONE
        }
        // Save button is only meaningful when the field is non-empty and parses.
        savePresetButton.isEnabled = isCustom &&
            customCommandText.trim().isNotEmpty() &&
            customParseError == null

        // Preset description. For saved presets, append a discoverability hint
        // for the long-press-to-delete gesture so the user doesn't have to
        // stumble onto it.
        val builtIn = Presets.byId(selectedPresetId)
        val saved = if (!isCustom && builtIn == null) userPresets.byId(selectedPresetId) else null
        presetDescription.text = when {
            isCustom -> getString(R.string.preset_custom_desc)
            builtIn != null -> getString(builtIn.descriptionRes)
            saved != null -> "exiftool ${saved.templateText}".trim() +
                "\n" + getString(R.string.saved_preset_hint)
            else -> ""
        }

        if (cmd == null) {
            // Parse error: keep last-known slot chips empty, clear preview.
            slotChipGroup.removeAllViews()
            previewText.text = getString(R.string.preview_empty)
            footerWarning.text = ""
            updateStatus(cmd = null)
            updateRunButton(cmd = null)
            return
        }

        // Prune bindings for slots that no longer exist; keep compatible ones.
        val activeKeys = cmd.slots.map { it.key }.toSet()
        val drop = bindings.keys.filter { it !in activeKeys }.toList()
        for (k in drop) bindings.remove(k)
        // Trim binding URIs if the slot is now COUNT=ONE but had multiple URIs.
        for (slot in cmd.slots) {
            val b = bindings[slot.key] ?: continue
            if (slot.count == SlotCount.ONE && b.uris.size > 1) {
                bindings[slot.key] = b.copy(uris = b.uris.take(1))
            }
        }

        rebuildSlotChips(cmd)
        updatePreview(cmd)
        updateFooterWarning(cmd)
        updateStatus(cmd)
        updateRunButton(cmd)
    }

    // ----------------------------------------------------------------------
    // slot chips
    // ----------------------------------------------------------------------

    private fun rebuildSlotChips(cmd: Command) {
        slotChipGroup.removeAllViews()
        for (slot in cmd.slots) {
            val b = bindings[slot.key]
            val chip = Chip(this).apply {
                isCheckable = false
                isCloseIconVisible = b != null && b.uris.isNotEmpty()
                text = chipText(slot, b)
                if (b != null && b.uris.isNotEmpty()) {
                    chipBackgroundColor =
                        ContextCompat.getColorStateList(this@MainActivity, R.color.chip_slot_background_bound)
                    chipStrokeColor =
                        ContextCompat.getColorStateList(this@MainActivity, R.color.chip_slot_stroke_bound)
                    chipStrokeWidth = 2f
                } else {
                    chipBackgroundColor =
                        ContextCompat.getColorStateList(this@MainActivity, R.color.chip_slot_background)
                    chipStrokeColor =
                        ContextCompat.getColorStateList(this@MainActivity, R.color.chip_slot_stroke)
                    chipStrokeWidth = 2f
                }
                setOnClickListener {
                    activePickKey = slot.key
                    filePickLauncher.launch(makeFilePickerIntent(slot))
                }
                setOnCloseIconClickListener {
                    bindings.remove(slot.key)
                    applyActiveCommand()
                }
            }
            slotChipGroup.addView(chip)
        }
    }

    private fun chipText(slot: FileSlot, b: CommandRunner.SlotBinding?): String {
        val label = displayLabel(slot)
        if (b == null || b.uris.isEmpty()) {
            return getString(R.string.slot_chip_empty, label)
        }
        return when {
            b.uris.size == 1 -> "$label: ${uriDisplayName(b.uris.first())}"
            else -> "$label: ${b.uris.size} files"
        }
    }

    /**
     * Context-aware display label for a slot in the *current* command. Thin
     * wrapper around [contextAwareSlotLabel] that pulls the slot list from
     * the parsed active command.
     */
    private fun displayLabel(slot: FileSlot): String {
        val cmd = resolveActiveCommand() ?: return slot.label()
        return contextAwareSlotLabel(slot, cmd.slots)
    }

    private fun makeFilePickerIntent(slot: FileSlot): Intent =
        FileUtils.makeImagePickerIntent().apply {
            // Single-select for SlotCount.ONE, multi-select otherwise.
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, slot.count == SlotCount.MANY)
            // Sidecars are not images.
            if (slot.key.role == SlotRole.SIDECAR) {
                type = "*/*"
            }
        }

    private fun handleFilePickResult(resultCode: Int, data: Intent?) {
        val key = activePickKey ?: return
        activePickKey = null
        if (resultCode != Activity.RESULT_OK) return
        val uris = extractUris(data)
        if (uris.isEmpty()) return
        val slot = currentSlot(key) ?: return
        val take = if (slot.count == SlotCount.ONE) uris.take(1) else uris
        bindings[key] = CommandRunner.SlotBinding(slot, take)
        // Persist permissions so a re-resolve later doesn't lose access.
        for (u in take) tryTakePersistableReadWrite(u)
        applyActiveCommand()
    }

    private fun tryTakePersistableReadWrite(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers don't support persistable permissions; non-fatal.
        }
    }

    private fun currentSlot(key: SlotKey): FileSlot? =
        resolveActiveCommand()?.slots?.firstOrNull { it.key == key }

    private fun extractUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        data.clipData?.let { cd ->
            return (0 until cd.itemCount).map { cd.getItemAt(it).uri }
        }
        return listOfNotNull(data.data)
    }

    private fun uriDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    // ----------------------------------------------------------------------
    // preview / status / footer
    // ----------------------------------------------------------------------

    private fun updatePreview(cmd: Command) {
        val preview = runner.preview(cmd, bindings)
        val sb = SpannableStringBuilder("exiftool")
        for (tok in preview.argv) {
            sb.append(' ')
            when (tok) {
                is CommandRunner.PreviewToken.Bound -> sb.append(tok.value)
                is CommandRunner.PreviewToken.Unbound -> {
                    val slot = cmd.slots.first { it.key == tok.key }
                    appendUnboundSpan(sb, "«${displayLabel(slot)}»")
                }
                is CommandRunner.PreviewToken.CompositeUnbound -> {
                    val start = sb.length
                    for (part in tok.parts) {
                        when (part) {
                            is CommandRunner.PreviewToken.CompositeUnbound.Either.Text ->
                                sb.append(part.value)
                            is CommandRunner.PreviewToken.CompositeUnbound.Either.Slot -> {
                                val slot = cmd.slots.first { it.key == part.key }
                                sb.append("«${displayLabel(slot)}»")
                            }
                        }
                    }
                    sb.setSpan(
                        ForegroundColorSpan(Color.parseColor("#B71C1C")),
                        start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(
                        StyleSpan(android.graphics.Typeface.BOLD),
                        start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        previewText.text = sb
    }

    private fun appendUnboundSpan(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(
            ForegroundColorSpan(Color.parseColor("#B71C1C")),
            start, start + text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            start, start + text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun updateFooterWarning(cmd: Command) {
        val mutates = cmd.mayMutateTargets()
        footerWarning.text = if (mutates) {
            getString(R.string.footer_writeback_warning)
        } else {
            getString(R.string.footer_readonly_only)
        }
        footerWarning.setTextColor(
            ContextCompat.getColor(
                this,
                if (mutates) R.color.status_error else R.color.status_success
            )
        )
    }

    private fun updateStatus(cmd: Command?) {
        if (isRunning) return
        val (text, color) = when {
            cmd == null && customParseError != null ->
                customParseError!! to R.color.status_error
            cmd == null ->
                getString(R.string.status_pick_command) to R.color.status_idle
            else -> {
                val unbound = runner.unboundSlots(cmd, bindings)
                if (unbound.isEmpty())
                    getString(R.string.status_ready) to R.color.status_success
                else
                    getString(R.string.status_pick_files, displayLabel(unbound.first())) to R.color.status_idle
            }
        }
        statusBanner.text = text
        statusBanner.setBackgroundColor(ContextCompat.getColor(this, color))
    }

    private fun updateRunButton(cmd: Command?) {
        val ready = cmd != null && runner.unboundSlots(cmd, bindings).isEmpty() && !isRunning
        runButton.isEnabled = ready
        runButton.alpha = if (ready) 1f else 0.5f
    }

    // ----------------------------------------------------------------------
    // run pipeline
    // ----------------------------------------------------------------------

    private fun attemptRun() {
        val cmd = resolveActiveCommand() ?: return
        if (runner.unboundSlots(cmd, bindings).isNotEmpty()) return

        // Confirm before destructive runs. Same predicate the footer uses, so
        // every command that's flagged red in the UI also surfaces a confirm.
        if (cmd.mayMutateTargets()) {
            val targets = cmd.slots.filter { it.key.role == SlotRole.TARGET }
                .flatMap { bindings[it.key]?.uris ?: emptyList() }
                .joinToString("\n") { uriDisplayName(it) }
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_run_title)
                .setMessage(getString(R.string.confirm_run_overwrite_message, targets))
                .setPositiveButton(R.string.confirm_run_continue) { d, _ ->
                    d.dismiss()
                    doRun(cmd)
                }
                .setNegativeButton(R.string.confirm_run_cancel) { d, _ -> d.dismiss() }
                .show()
        } else {
            doRun(cmd)
        }
    }

    private fun doRun(cmd: Command) {
        isRunning = true
        runButton.isEnabled = false
        runButton.alpha = 0.5f
        statusBanner.text = getString(R.string.status_running)
        statusBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.status_running))
        outputText.text = "…"

        launch {
            try {
                if (!ExifToolRunner.isInstalled(applicationContext)) {
                    statusBanner.text = getString(R.string.status_installing)
                    AssetExtractor.ensureInstalled(applicationContext)
                }

                val outcome = runner.run(cmd, bindings)
                when (outcome) {
                    is CommandRunner.Outcome.Success -> {
                        commandHistory.append(outcome.argv)
                        statusBanner.text = getString(R.string.status_succeeded)
                        statusBanner.setBackgroundColor(
                            ContextCompat.getColor(applicationContext, R.color.status_success)
                        )
                        outputText.text = if (outcome.exitOutput.isNotBlank())
                            outcome.exitOutput else "(no output)"
                    }
                    is CommandRunner.Outcome.Failure -> {
                        outcome.argv?.let { commandHistory.append(it) }
                        statusBanner.text = getString(R.string.status_error)
                        statusBanner.setBackgroundColor(
                            ContextCompat.getColor(applicationContext, R.color.status_error)
                        )
                        outputText.text = buildString {
                            append(outcome.message)
                            if (!outcome.exitOutput.isNullOrBlank()) {
                                append("\n\n")
                                append(outcome.exitOutput)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "run failed", e)
                statusBanner.text = getString(R.string.status_error)
                statusBanner.setBackgroundColor(
                    ContextCompat.getColor(applicationContext, R.color.status_error)
                )
                outputText.text = e.toString()
            } finally {
                isRunning = false
                applyActiveCommand()
            }
        }
    }

    // ----------------------------------------------------------------------
    // disclaimers + perms
    // ----------------------------------------------------------------------

    private fun showFirstRunDisclaimer() {
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

    private fun showAdvancedWarning(onAccepted: () -> Unit = {}) {
        AlertDialog.Builder(this)
            .setTitle(R.string.advanced_warning_title)
            .setMessage(R.string.advanced_warning_message)
            .setCancelable(false)
            .setPositiveButton(R.string.advanced_warning_accept) { d, _ ->
                disclaimerPrefs.hasAcceptedAdvancedWarning = true
                disclaimerPrefs.advancedModeEnabled = true
                rebuildPresetChips()
                invalidateOptionsMenu()
                onAccepted()
                d.dismiss()
            }
            .setNegativeButton(R.string.advanced_warning_decline) { d, _ -> d.dismiss() }
            .show()
    }

    private fun showSavePresetDialog(prefillName: String = "") {
        val text = customCommandText.trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.save_preset_empty_text, Toast.LENGTH_SHORT).show()
            return
        }
        if (customParseError != null) {
            Toast.makeText(this, R.string.save_preset_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.save_preset_hint)
            setSingleLine(true)
            setText(prefillName)
            setSelection(prefillName.length)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.save_preset_title)
            .setMessage(R.string.save_preset_message)
            .setView(container)
            .setPositiveButton(R.string.save_preset_save, null)
            .setNegativeButton(R.string.save_preset_cancel) { d, _ -> d.dismiss() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    input.error = getString(R.string.save_preset_hint)
                    return@setOnClickListener
                }
                val existing = userPresets.list().firstOrNull { it.name.equals(name, ignoreCase = true) }
                val commitSave = {
                    val saved = userPresets.save(name, text)
                    selectedPresetId = saved.id
                    rebuildPresetChips()
                    applyActiveCommand()
                    Toast.makeText(
                        this,
                        getString(R.string.saved_preset_toast_fmt, saved.name),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                if (existing != null) {
                    // Dismiss the Save dialog before showing Replace so the
                    // confirm isn't stacked on top. If the user cancels Replace,
                    // re-open Save with the typed name preserved.
                    dialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.save_preset_overwrite_fmt, existing.name))
                        .setPositiveButton(R.string.save_preset_save) { d, _ ->
                            commitSave(); d.dismiss()
                        }
                        .setNegativeButton(R.string.save_preset_cancel) { d, _ ->
                            d.dismiss()
                            showSavePresetDialog(prefillName = name)
                        }
                        .show()
                } else {
                    commitSave()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun confirmDeleteSavedPreset(saved: UserPresets.Saved) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_preset_title_fmt, saved.name))
            .setMessage(R.string.delete_preset_message)
            .setPositiveButton(R.string.delete_preset_confirm) { d, _ ->
                userPresets.delete(saved.id)
                if (selectedPresetId == saved.id) {
                    selectedPresetId = Presets.SHOW_METADATA.id
                }
                rebuildPresetChips()
                applyActiveCommand()
                d.dismiss()
            }
            .setNegativeButton(R.string.save_preset_cancel) { d, _ -> d.dismiss() }
            .show()
    }

    private fun showCommandHistory() {
        val text = commandHistory.read().ifBlank { "(empty)" }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_command_history)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_about)
            .setMessage(
                "ExifToolWrapper\n\n" +
                "Wraps Phil Harvey's exiftool, bundled as a perl5 native binary.\n\n" +
                "https://github.com/bestvibes/exiftoolwrapper-android"
            )
            .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
            .show()
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        // READ_EXTERNAL_STORAGE is only meaningful (and only declared) on API <= 32.
        if (Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        // ACCESS_MEDIA_LOCATION is a runtime permission on API 29+. Without it, some
        // OEMs (notably Samsung) redact GPS EXIF even when the file is read through
        // SAF, so exiftool sees a file without GPSLatitude/GPSLongitude. Requesting
        // it is best-effort; if denied, the app still works for non-location reads.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_MEDIA_LOCATION
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMS_RETURN)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMS_RETURN) return
        // Only the storage-read denial is user-visible — the app can't pick files
        // without it. ACCESS_MEDIA_LOCATION denial just means GPS EXIF may be
        // redacted on some OEMs; we don't want to nag the user for it.
        for (i in permissions.indices) {
            if (permissions[i] == Manifest.permission.READ_EXTERNAL_STORAGE &&
                grantResults.getOrNull(i) == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, "File permissions denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMS_RETURN = 12345
    }
}

/**
 * The data model's [FileSlot.label] is correct in isolation, but a bare
 * `{target}` reads as "Files" by default — wrong when there's a Source in the
 * same command (the target there is conceptually a Destination). This picks
 * the right wording given the full set of slots in the same command.
 */
fun contextAwareSlotLabel(slot: FileSlot, allSlots: List<FileSlot>): String {
    if (slot.key.role == SlotRole.TARGET && slot.key.variant == null) {
        val hasSource = allSlots.any { it.key.role == SlotRole.SOURCE }
        if (hasSource) {
            return if (slot.count == SlotCount.ONE) "Destination" else "Destinations"
        }
    }
    return slot.label()
}
