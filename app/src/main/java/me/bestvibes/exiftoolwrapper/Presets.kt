package me.bestvibes.exiftoolwrapper

/**
 * Built-in presets — common exiftool workflows expressed as [Command] objects.
 *
 * Each preset is a thin wrapper over a hard-coded template string parsed by
 * [CommandParser]; this guarantees presets and the custom-command field share
 * identical semantics (same slot model, same write-back gating, same preview).
 *
 * Add a new preset by:
 *   1. Adding a [Preset] entry to [ALL].
 *   2. Adding strings to res/values/strings.xml for label + description.
 */
data class Preset(
    val id: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val templateText: String,
) {
    /** Parsed once; presets are built from trusted strings so this never rejects. */
    val command: Command by lazy {
        when (val r = CommandParser.parse(templateText)) {
            is CommandParser.Result.Ok -> r.command
            is CommandParser.Result.Rejected ->
                error("Preset $id failed to parse: ${r.reason}")
        }
    }
}

object Presets {
    val SHOW_METADATA = Preset(
        id = "show_metadata",
        labelRes = R.string.preset_show_metadata,
        descriptionRes = R.string.preset_show_metadata_desc,
        templateText = "",
    )

    val CHECK_DATE = Preset(
        id = "check_date",
        labelRes = R.string.preset_check_date,
        descriptionRes = R.string.preset_check_date_desc,
        templateText = "-DateTimeOriginal",
    )

    val DATE_FROM_FILENAME = Preset(
        id = "date_from_filename",
        labelRes = R.string.preset_date_from_filename,
        descriptionRes = R.string.preset_date_from_filename_desc,
        templateText = "-DateTimeOriginal<Filename -overwrite_original",
    )

    val DATE_FROM_MODIFIED = Preset(
        id = "date_from_modified",
        labelRes = R.string.preset_date_from_modified,
        descriptionRes = R.string.preset_date_from_modified_desc,
        templateText = "-DateTimeOriginal<FileModifyDate -overwrite_original",
    )

    /**
     * Stefan's request: replace ALL metadata in a destination file with metadata
     * copied from a source file. The `-all=` clears every tag in the destination
     * first; `-tagsFromFile {source} -all:all` then re-copies every tag from
     * the source.
     */
    val COPY_METADATA = Preset(
        id = "copy_metadata",
        labelRes = R.string.preset_copy_metadata,
        descriptionRes = R.string.preset_copy_metadata_desc,
        templateText = "-all= -tagsFromFile {source} -all:all -overwrite_original {target}",
    )

    val ALL: List<Preset> = listOf(
        SHOW_METADATA,
        CHECK_DATE,
        DATE_FROM_FILENAME,
        DATE_FROM_MODIFIED,
        COPY_METADATA,
    )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
