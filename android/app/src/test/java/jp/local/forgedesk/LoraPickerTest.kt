package jp.local.forgedesk

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoraPickerTest {
    private val catalog=parseLoraCatalog(JSONObject("""
        {"preset":"sdxl","filter":"Hide other architectures","weight":0.8,"items":[
          {"name":"Sketch","alias":"pencil","path":"drawing/Sketch.safetensors","arch":["sdxl"],
           "thumbnail_id":"0123456789abcdef0123456789abcdef","preferred_weight":0.65},
          {"name":"Sketch","alias":"ink","path":"ink/Sketch.safetensors","arch":["flux"],
           "thumbnail_id":"../invalid"},
          {"name":"Photo","path":"Photo.safetensors","arch":[]},
          {"name":"Stamp","alias":"Photo","arch":["sdxl"]}
        ]}
    """.trimIndent()))

    @Test fun gridKeepsRealPathsAndBoundedPreviewIdentifiers() {
        val pencil=catalog.entries.first()
        assertEquals("drawing",pencil.folder)
        assertEquals(32,pencil.thumbnailId.length)
        assertEquals("",catalog.entries[1].thumbnailId)
        assertTrue(pencil.duplicateName)
        assertFalse(pencil.ambiguous)
        assertTrue(catalog.entries[2].ambiguous)
        assertTrue(catalog.entries[3].ambiguous)
    }

    @Test fun searchKeepsStableKeysAndArchitecturePreferences() {
        assertEquals(listOf(0),filterLoras(catalog,"SDXL","DRAWING").map {it.index})
        assertEquals(listOf(1),filterLoras(catalog,"全件","ink").map {it.index})
        assertEquals(listOf(0,3,2),filterLoras(catalog,"PC設定","").map {it.index})
    }

    @Test fun sameNamesWithUniqueAliasesUseOnlyWeightForSelectedFile() = runBlocking {
        val client=ForgeClient(LOCAL_FORGE)
        assertEquals("<lora:pencil:0.65>",client.loraInsertion(catalog.entries[0],catalog.weight))
        assertEquals("<lora:ink:0.8>",client.loraInsertion(catalog.entries[1],catalog.weight))
        assertTrue(runCatching {client.loraInsertion(catalog.entries[3],catalog.weight)}.isFailure)
    }

    @Test fun folderNavigationHonorsBoundariesAndKeepsOriginalIdentifiers() {
        val paths=listOf("leaf","sdxl/one","sdxl/deep/one","sdxl-extra/two","sdxl/deep/nested/three")
        val rows=paths.mapIndexed {i,p->IndexedValue(i,LoraEntry(p.substringAfterLast('/'),p,p,emptyList()))}
        val root=loraBranch(rows,"",false)
        assertEquals(listOf("sdxl" to 3,"sdxl-extra" to 1),root.children)
        assertEquals(listOf(0),root.entries.map {it.index})
        val branch=loraBranch(rows,"sdxl",false)
        assertEquals(3,branch.count)
        assertEquals(listOf("sdxl/deep" to 2),branch.children)
        assertEquals(listOf(1),branch.entries.map {it.index})
        assertEquals(listOf(1,2,4),loraBranch(rows,"sdxl",true).entries.map {it.index})
    }
}
