package jp.local.forgedesk

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun stableJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + stableJson(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { stableJson(value.opt(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

internal fun draftDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** One atomic preferences entry per endpoint, with independent text and image forms. */
internal class GenerationDrafts(
    private val read: (String) -> String?,
    private val write: (String, String, Boolean) -> Unit
) {
    private var key = ""
    private var state = JSONObject()
    private var lastWritten = ""
    fun open(origin: String) {
        key = "v1-" + draftDigest(origin)
        val saved = read(key).orEmpty()
        state = runCatching { JSONObject(saved) }.getOrNull()?.takeIf { it.optInt("version") == 1 }
            ?: JSONObject().put("version", 1)
        lastWritten = state.toString()
    }
    private fun mode(image: Boolean): JSONObject {
        val name = if (image) "img2img" else "txt2img"
        return state.optJSONObject(name) ?: JSONObject().also { state.put(name, it) }
    }
    var lastImageMode: Boolean
        get() = state.optBoolean("lastImageMode")
        set(value) { state.put("lastImageMode",value) }
    var pcProfiles: JSONObject?
        get() = state.optJSONObject("pc")
        set(value) { state.put("pc", value) }
    fun draft(image: Boolean): JSONObject? = mode(image).optJSONObject("draft")
    fun defaults(image: Boolean): JSONObject? = mode(image).optJSONObject("defaults")
    fun selected(image: Boolean): JSONObject? = draft(image) ?: defaults(image)
    fun setDraft(image: Boolean, value: JSONObject?) { mode(image).put("draft", value) }
    fun setDefaults(image: Boolean, value: JSONObject?) { mode(image).put("defaults", value) }
    fun persist(sync: Boolean = false) {
        val next = state.toString()
        if (sync || next != lastWritten) {
            write(key, next, sync)
            lastWritten = next
        }
    }
}

/** A late answer from the PC can fill untouched fields while preserving edits made during the request. */
internal fun mergeFetchedProfile(base: JSONObject, before: JSONObject, current: JSONObject): JSONObject {
    val merged = JSONObject(base.toString())
    for (key in current.keys()) {
        if (stableJson(current.opt(key)) != stableJson(before.opt(key))) merged.put(key, current.get(key))
    }
    return merged
}
