package jp.local.forgedesk

import org.json.JSONArray

/** The displayed dictionary spelling and the prompt text can differ. */
internal data class TagChoice(val name:String, val insert:String, val count:Long?=null)

internal fun mergeTagChoices(saved:List<TagChoice>, remote:List<TagChoice>):List<TagChoice> {
    val choices=LinkedHashMap<String,TagChoice>()
    for(tag in remote+saved) choices.putIfAbsent(tag.name,tag)
    return choices.values.sortedByDescending { it.count ?: -1L }
}

private val underscoreFaces = "0_0,(o)_(o),+_+,+_-,._.,<o>_<o>,<|>_<|>,=_=,>_<,3_3,6_9,>_o,@_@,^_^,o_o,u_u,x_x,|_|,||_||".split(',').toSet()
private val syntaxWeight = Regex("""\([^)]*:-?(?:\d+(?:\.\d*)?|\.\d+)\)""")
private val literalBracket = Regex("""\\([()\[\]])""")

internal fun tagSearchText(text:String):String = literalBracket.replace(text) {it.groupValues[1]}

// Fallback for an older PC helper or a simple, locally registered tag.
private fun standardTagText(raw:String):String = buildString {
    var slashes=0
    val keepUnderscores=raw in underscoreFaces
    for(char in raw) {
        if(char in "()[]" && slashes%2==0)append('\\')
        append(if(char=='_' && !keepUnderscores) ' ' else char)
        slashes=if(char=='\\') slashes+1 else 0
    }
}

internal fun savedTagChoice(raw:String):TagChoice {
    val snippet=raw.contains(',') || raw.contains('\n') || raw.startsWith("__") ||
        (raw.startsWith('<') && raw.contains(':') && raw.endsWith('>')) ||
        syntaxWeight.containsMatchIn(raw) || (raw.startsWith('(') && raw.endsWith(')') && raw !in underscoreFaces) ||
        (raw.startsWith('[') && raw.endsWith(']')) ||
        (raw.startsWith('{') && raw.endsWith('}'))
    return TagChoice(raw,if(snippet) raw else standardTagText(raw))
}

internal fun decodeTagChoices(items:JSONArray):List<TagChoice> = buildList {
    for(index in 0 until items.length()) {
        when(val entry=items.opt(index)) {
            is String -> if(entry.isNotBlank()) add(TagChoice(entry,standardTagText(entry)))
            is org.json.JSONObject -> {
                val name=entry.optString("name")
                val insert=entry.optString("insert")
                val count=(entry.opt("count") as? Number)?.toLong()?.takeIf {it>=0}
                if(name.isNotBlank() && insert.isNotBlank())add(TagChoice(name,insert,count))
            }
        }
    }
}
