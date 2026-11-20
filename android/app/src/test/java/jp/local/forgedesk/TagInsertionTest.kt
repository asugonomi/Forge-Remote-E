package jp.local.forgedesk

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TagInsertionTest {
    @Test fun countSortPlacesUnknownAfterZeroAndKeepsDictionaryTies() {
        val rows=JSONArray()
        for((name,number) in listOf("low" to 0L,"high" to 8000000L,"tie1" to 200L,"tie2" to 200L)) {
            rows.put(JSONObject().put("name",name).put("insert",name).put("count",number))
        }
        val remote=decodeTagChoices(rows)
        assertEquals(listOf("high","tie1","tie2","low","local"),
            mergeTagChoices(listOf(savedTagChoice("local"),savedTagChoice("high")),remote).map { it.name })
    }
    @Test fun serverControlsInsertionWithoutChangingDictionaryLabel() {
        val raw="character_name_(series)"
        val spelled="""character name \(series\)"""
        val choice=decodeTagChoices(JSONArray().put(JSONObject().put("name",raw).put("insert",spelled))).single()
        assertEquals(raw,choice.name)
        val before=TextFieldValue("one,\nchar, two",TextRange(9))
        val result=insertPrompt(before,choice.insert,true)
        assertEquals("one,\n"+spelled+", two",result.text)
        assertEquals(TextRange(5+spelled.length+2),result.selection)
    }
    @Test fun fallbackFormatsRawTagsOnceAndKeepsEmoticons() {
        val raw=JSONArray().put("example_(series)_[form]").put("""already_\(quoted\)""").put("(o)_(o)")
        val decoded=decodeTagChoices(raw)
        assertEquals(listOf("""example \(series\) \[form\]""","""already \(quoted\)""","""\(o\)_\(o\)"""),decoded.map{it.insert})
        assertEquals("example (series) [form]",tagSearchText(decoded.first().insert))
    }
    @Test fun savedSnippetsKeepPromptSyntaxAndPcSettingsWinForSameTag() {
        for(snippet in listOf("(high_quality:1.25)","<lora:sample_name:0.6>","__my_wildcard__","[blue_hair:red_hair:0.5]"))
            assertEquals(snippet,savedTagChoice(snippet).insert)
        val saved=savedTagChoice("extra_name_(series)")
        assertEquals("""extra name \(series\)""",saved.insert)
        val pc=TagChoice(saved.name,"extra name (series)")
        assertEquals(listOf(pc),mergeTagChoices(listOf(saved),listOf(pc)))
    }
}
