package me.bestvibes.exiftoolwrapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * User-saved exiftool command templates. Stored as a JSON array in
 * SharedPreferences — small, sync-safe for the size of this list, and a
 * dependency-free alternative to Room for what amounts to a key-value blob.
 *
 * Saved presets behave exactly like built-ins from [Presets]: their
 * [Saved.templateText] is parsed by [CommandParser] on demand, so they share
 * the same slot model, write-back gating, and preview pipeline.
 */
class UserPresets(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Saved(
        val id: String,
        val name: String,
        val templateText: String,
    ) {
        val command: Command by lazy {
            when (val r = CommandParser.parse(templateText)) {
                is CommandParser.Result.Ok -> r.command
                is CommandParser.Result.Rejected ->
                    error("Saved preset \"$name\" failed to parse: ${r.reason}")
            }
        }
    }

    fun list(): List<Saved> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Saved(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    templateText = o.getString("template"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(name: String, templateText: String): Saved {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty()) { "Preset name cannot be empty" }
        val current = list().toMutableList()
        // Same name = overwrite. Case-insensitive so "MyPreset" and "mypreset"
        // don't both stick around.
        current.removeAll { it.name.equals(cleaned, ignoreCase = true) }
        val saved = Saved(
            id = "user_${UUID.randomUUID()}",
            name = cleaned,
            templateText = templateText,
        )
        current += saved
        write(current)
        return saved
    }

    fun delete(id: String) {
        val current = list().toMutableList()
        current.removeAll { it.id == id }
        write(current)
    }

    fun byId(id: String): Saved? = list().firstOrNull { it.id == id }

    private fun write(items: List<Saved>) {
        val arr = JSONArray()
        for (s in items) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("template", s.templateText)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "user_presets"
        private const val KEY = "presets_v1"
    }
}
