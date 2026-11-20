package jp.local.forgedesk

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class HiresModulesTest {
    private fun job() = JSONObject().put("enable_hr",true)
    private fun modules(body:JSONObject) = body.getJSONArray("hr_additional_modules").toString()
    @Test fun missingOrNullUsesForgeUiDefault() {
        for (body in listOf(job(),job().put("hr_additional_modules",JSONObject.NULL))) {
            normalizeForgeHighResModules(body)
            assertEquals("""["Use same choices"]""",modules(body))
            normalizeForgeHighResModules(body)
            assertEquals("""["Use same choices"]""",modules(body))
        }
    }
    @Test fun disabledHiresIsUntouched() {
        val body=JSONObject().put("enable_hr",false)
        normalizeForgeHighResModules(body)
        assertFalse(body.has("hr_additional_modules"))
    }
    @Test fun explicitRequestWinsIncludingEmpty() {
        val profile=JSONObject().put("hr_additional_modules",JSONArray().put("other.safetensors"))
        for (s in listOf("""[]""","""["chosen.safetensors"]""","""["Use same choices"]""")) {
            val body=job().put("hr_additional_modules",JSONArray(s))
            normalizeForgeHighResModules(body,profile)
            assertEquals(s,modules(body))
        }
    }
    @Test fun currentProfileIncludingBuiltInIsPreserved() {
        for(s in listOf("""[]""","""["my-vae.safetensors","my-te.pt"]""")) {
            val body=job()
            normalizeForgeHighResModules(body,JSONObject().put("hr_additional_modules",JSONArray(s)))
            assertEquals(s,modules(body))
        }
    }
    @Test fun restoreOrderedNamedPngModules() {
        val body=job().put("infotext","Photo of a cafe\nSteps: 20, Hires Module 2: txt.encoder.v1, Comment: \"ignore, Hires Module 3: x\", Hires Module 1: \"my,vae\"")
        val choices=JSONArray().put("txt.encoder.v1.safetensors").put("my,vae.pt")
        normalizeForgeHighResModules(body,JSONObject().put("hr_additional_module_choices",choices))
        assertEquals("""["my,vae.pt","txt.encoder.v1.safetensors"]""",modules(body))
    }
    @Test fun restoreBuiltInDoesNotInherit() {
        val body=job().put("infotext","example\nSteps: 8, Hires Module 1: Built-in")
        normalizeForgeHighResModules(body)
        assertEquals("[]",modules(body))
    }
    @Test fun missingModuleNeverSilentlyChangesSelection() {
        val body=job().put("infotext","example\nSteps: 8, Hires Module 1: missing")
        val profile=JSONObject().put("hr_additional_module_choices",JSONArray().put("other.safetensors"))
        assertThrows(IllegalArgumentException::class.java){normalizeForgeHighResModules(body,profile)}
        assertFalse(body.has("hr_additional_modules"))
    }
}
