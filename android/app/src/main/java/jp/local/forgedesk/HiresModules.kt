package jp.local.forgedesk

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val MODULES = "hr_additional_modules"
private const val INHERIT = "Use same choices"
private const val CHOICES = "hr_additional_module_choices"
private val infoField = Regex("""(?:^|,\s*)([\w /-]+?):\s*("(?:\\.|[^"\\])*"|[^,]*)""")
private val numberedModule = Regex("""Hires Module (\d+)""")
private class NeedModuleList: IllegalStateException()

private fun pngHiresModules(infotext: String): List<String> {
    val tail = infotext.trimEnd().substringAfterLast('\n')
    if (!tail.startsWith("Steps:")) return emptyList()
    return infoField.findAll(tail).mapNotNull { match ->
        val key = numberedModule.matchEntire(match.groupValues[1].trim()) ?: return@mapNotNull null
        val n = key.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val raw = match.groupValues[2].trim()
        val value = if (raw.startsWith('"')) JSONTokener(raw).nextValue().toString() else raw
        n to value
    }.sortedBy { it.first }.map { it.second }.toList()
}

private fun basename(value: String) = value.substringAfterLast('/').substringAfterLast('\\')

/** Populate only the missing Hires module setting. An explicit empty array means built-in VAE/TE. */
internal fun normalizeForgeHighResModules(request: JSONObject, profile: JSONObject? = null) {
    if (!request.optBoolean("enable_hr") || !request.isNull(MODULES)) return
    val recorded = pngHiresModules(request.optString("infotext"))
    val modules = when {
        recorded.isEmpty() -> profile?.optJSONArray(MODULES) ?: JSONArray().put(INHERIT)
        recorded.first() == "Built-in" -> JSONArray()
        recorded.first() == INHERIT -> JSONArray().put(INHERIT)
        else -> {
            val available = profile?.optJSONArray(CHOICES) ?: throw NeedModuleList()
            val names = (0 until available.length()).map { available.getString(it) }.distinct()
            JSONArray().apply {
                for (name in recorded) {
                    val exact = names.filter { basename(it) == basename(name) }
                    val candidates = exact.ifEmpty { names.filter { basename(it).substringBeforeLast('.') == basename(name) } }
                    require(candidates.size == 1) { "PNG Info の Hires モジュールをPC上で特定できません（$name）" }
                    put(candidates.single())
                }
            }
        }
    }
    request.put(MODULES, JSONArray(modules.toString()))
}

/** Older queue entries may only contain the module stems in the original PNG infotext. */
internal suspend fun prepareForgeHighResModules(request: JSONObject, client: ForgeClient, profile: JSONObject? = null) {
    try {
        normalizeForgeHighResModules(request, profile)
    } catch (_: NeedModuleList) {
        val list = JSONArray(client.get("/sdapi/v1/sd-modules").toString(Charsets.UTF_8))
        val choices = JSONArray()
        for (i in 0 until list.length()) {
            list.optJSONObject(i)?.optString("model_name")?.takeIf { it.isNotBlank() }?.let { choices.put(it) }
        }
        normalizeForgeHighResModules(request, JSONObject().put(CHOICES,choices))
    }
}
