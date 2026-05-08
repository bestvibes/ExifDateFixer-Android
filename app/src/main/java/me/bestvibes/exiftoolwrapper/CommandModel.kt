package me.bestvibes.exiftoolwrapper

/**
 * Internal representation of an exiftool invocation as a typed template with
 * named file slots, replacing the older "flat flag string + homogenous file
 * list" model.
 *
 * A [Command] is two things:
 *   1. A [template] of [Token]s — literal flag strings interleaved with
 *      [Token.SlotRef]s that point into [slots] by [SlotKey].
 *   2. A [slots] list declaring each file slot's role, kind, and multiplicity.
 *
 * At run time:
 *   - Every slot is bound to one or more URIs by the user (via SAF picker).
 *   - Bound URIs are materialized into a per-run cache directory.
 *   - The template is walked, [Token.SlotRef]s replaced with the cached path(s).
 *   - Only [SlotRole.TARGET] cached files are written back to their URIs;
 *     read-only slots (Source/Sidecar/OutputDir) are never written back.
 */
data class Command(
    val template: List<Token>,
    val slots: List<FileSlot>,
)

/**
 * A single argv element. Two forms:
 *   - [Standalone] — one whitespace-delimited input piece, either a literal
 *     flag/value or a single bare slot reference.
 *   - [Composite] — a single argv element built from mixed text + slot parts,
 *     e.g. `-csv={sidecar:csv}` resolves to one argv `-csv=/cache/file.csv`.
 *
 * Multi-file slots ([SlotCount.MANY]) are only permitted in [Standalone] form,
 * because expanding many files into a Composite is ambiguous.
 */
sealed class Token {
    sealed class Standalone : Token()
    data class Literal(val value: String) : Standalone()
    data class SlotRef(val key: SlotKey) : Standalone()
    data class Composite(val parts: List<Part>) : Token() {
        sealed class Part {
            data class Text(val value: String) : Part()
            data class Slot(val key: SlotKey) : Part()
        }
    }
}

/**
 * Identifies a slot uniquely within a [Command]. The [variant] disambiguates
 * multiple slots of the same role:
 *
 *   {source}        → SlotKey(SOURCE, null)        — labeled "Source"
 *   {source:2}      → SlotKey(SOURCE, "2")         — labeled "Source 2"
 *   {sidecar:csv}   → SlotKey(SIDECAR, "csv")      — labeled "Sidecar (csv)"
 *   {target}        → SlotKey(TARGET, null)        — labeled "Files" (default)
 */
data class SlotKey(val role: SlotRole, val variant: String?) {
    /** The exact text that, when typed in the custom field, parses back to this key. */
    fun tokenText(): String = when (variant) {
        null -> "{${role.tokenName}}"
        else -> "{${role.tokenName}:$variant}"
    }
}

enum class SlotRole(
    val tokenName: String,
    val defaultLabel: String,
    /**
     * Whether files bound to this slot get written back to their URIs after
     * exiftool finishes. Source and Sidecar are read-only inputs; only Target
     * cached files are written back.
     */
    val isWriteBack: Boolean,
) {
    SOURCE("source", "Source", isWriteBack = false),
    TARGET("target", "Files", isWriteBack = true),
    SIDECAR("sidecar", "Sidecar", isWriteBack = false),
    OUTDIR("outdir", "Output folder", isWriteBack = false);

    companion object {
        fun fromTokenName(name: String): SlotRole? =
            values().firstOrNull { it.tokenName.equals(name, ignoreCase = true) }
    }
}

enum class SlotKind { FILE, DIR }

enum class SlotCount { ONE, MANY }

data class FileSlot(
    val key: SlotKey,
    val kind: SlotKind = SlotKind.FILE,
    val count: SlotCount = SlotCount.ONE,
) {
    /** Human-readable label for picker chips and error messages. */
    fun label(): String = when {
        key.variant == null -> key.role.defaultLabel
        key.role == SlotRole.SIDECAR -> "${key.role.defaultLabel} (${key.variant})"
        else -> "${key.role.defaultLabel} ${key.variant}"
    }
}
