package jp.local.forgedesk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdDetailerTest {
    private fun info(): AdMode {
        val schema=JSONObject("""{"properties":{"ad_tab_enable":{"type":"boolean","default":true},"ad_model":{"type":"string","default":"None"},"ad_confidence":{"type":"number","minimum":0,"maximum":1,"default":0.3},"ad_steps":{"type":"integer","minimum":1,"default":28},"ad_mask_min_ratio":{"type":"number","default":0},"ad_mask_max_ratio":{"type":"number","default":1},"is_api":{"type":"boolean","default":true}}}""")
        val scripts=JSONArray("""[{"name":"ADetailer","is_img2img":true,"args":[{"value":false},{"value":false},{"value":{"ad_model":"None"}},{"value":{"ad_model":"head.pt","ad_confidence":0.4}}]}]""")
        val bridge=JSONObject("""{"controls":{"ad_steps":{"maximum":150}},"profiles":{"img2img":{"1":{"ad_steps":33}}}}""")
        return adModeInfo(schema,scripts,true,listOf("head.pt"),bridge)
    }
    @Test fun disabledDoesNotSendOrEnableItself(){
        assertEquals(0,info().arguments(true).length())
    }
    @Test fun twoSlotsKeepPositionsAndUseTypedEdits(){
        val mode=info();mode.enabled=true;mode.skip=true
        mode.slots[1].set("ad_confidence","0.45")
        val args=mode.arguments(true).getJSONObject("ADetailer").getJSONArray("args")
        assertEquals(4,args.length());assertTrue(args.getBoolean(0));assertTrue(args.getBoolean(1))
        assertFalse(args.getJSONObject(2).getBoolean("ad_tab_enable"))
        assertEquals("head.pt",args.getJSONObject(3).getString("ad_model"))
        assertEquals(0.45,args.getJSONObject(3).getDouble("ad_confidence"),0.00001)
        assertEquals(33,args.getJSONObject(3).getInt("ad_steps"));assertFalse(args.getJSONObject(3).has("is_api"))
        assertFalse(mode.arguments(false).getJSONObject("ADetailer").getJSONArray("args").getBoolean(1))
    }
    @Test fun invalidMaskAndNonIntegerRejected(){
        val mode=info();mode.enabled=true
        mode.slots[1].set("ad_mask_min_ratio","0.6"); mode.slots[1].set("ad_mask_max_ratio","0.2")
        assertThrows(IllegalArgumentException::class.java){mode.arguments(true)}
        mode.slots[1].set("ad_mask_max_ratio","1");mode.slots[1].set("ad_steps","1.5")
        assertThrows(IllegalArgumentException::class.java){mode.arguments(true)}
        mode.slots[1].set("ad_steps","151");assertThrows(IllegalArgumentException::class.java){mode.arguments(true)}
    }
    @Test fun noEffectiveDetectorRejected(){
        val mode=info();mode.enabled=true;mode.slots[1].set("ad_tab_enable","false")
        assertThrows(IllegalArgumentException::class.java){mode.arguments(true)}
    }
}
