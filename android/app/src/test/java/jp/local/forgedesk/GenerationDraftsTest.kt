package jp.local.forgedesk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenerationDraftsTest {
    @Test fun restoresIndependentModesAndEndpointsAndDeviceDefault() {
        val disk=mutableMapOf<String,String>()
        fun boot(server:String)=GenerationDrafts(disk::get) { k,v,_->disk[k]=v }.apply {open(server)}
        val pc="https://first.test"
        val form=JSONObject().put("prompt","試験\n(two:1.2)").put("negative_prompt","blue")
            .put("width","").put("seed","-").put("enable_hr",true).put("extraJson","{unfinished")
        val image=JSONObject().put("prompt","image").put("width","768")
        val defaults=JSONObject().put("prompt","start")
        val first=boot(pc)
        first.setDefaults(false,defaults);first.setDraft(false,form);first.setDraft(true,image)
        first.lastImageMode=true
        first.pcProfiles=JSONObject().put("active","profile-a");first.persist(true)
        val fresh=boot(pc)
        assertTrue(fresh.lastImageMode)
        assertEquals(stableJson(form),stableJson(fresh.selected(false)))
        assertEquals("image",fresh.selected(true)!!.getString("prompt"))
        assertEquals("profile-a",fresh.pcProfiles!!.getString("active"))
        fresh.setDraft(false,null)
        assertEquals("start",fresh.selected(false)!!.getString("prompt"))
        fresh.setDefaults(false,null);fresh.persist()
        assertNull(boot(pc).selected(false))
        assertEquals("image",boot(pc).draft(true)!!.getString("prompt"))
        assertNull(boot("https://second.test").selected(true))
        assertTrue(disk.keys.none {it.contains("first")})
    }

    @Test fun latePcProfileRetainsOnlyFieldsEditedDuringFetch() {
        val before=JSONObject().put("prompt","").put("width","1024").put("steps",20)
        val edited=JSONObject(before.toString()).put("prompt","new").put("width","")
        val pc=JSONObject().put("prompt","preset").put("width",768).put("steps",36)
        val merged=mergeFetchedProfile(pc,before,edited)
        assertEquals("new",merged.getString("prompt"))
        assertEquals("",merged.getString("width"))
        assertEquals(36,merged.getInt("steps"))
    }

    @Test fun adetailerEditsRestoreByFieldIdAndSlotAndRejectRemovedModels() {
        val model=AdField("ad_model","string",listOf("None","detector"),null,null,null)
        val mask=AdField("ad_mask_blur","integer",emptyList(),0.0,64.0,1.0)
        fun state(fields:List<AdField>)=AdMode(fields,List(2){AdSlot(JSONObject().put("ad_model","None"))})
        val original=state(listOf(model,mask))
        original.enabled=true;original.selected=1
        original.slots[1].set("ad_model","detector");original.slots[1].set("ad_mask_blur","7")
        val saved=original.saved()
        val same=state(listOf(mask,model))
        assertTrue(same.restore(saved));assertEquals(1,same.selected)
        val args=same.arguments(false).getJSONObject("ADetailer").getJSONArray("args")
        assertEquals(7,args.getJSONObject(3).getInt("ad_mask_blur"))
        val changed=state(listOf(model.copy(choices=listOf("None")),mask))
        assertFalse(changed.restore(saved));assertFalse(changed.enabled)
        assertNull(state(listOf(mask,model)).saved())
    }

    @Test fun savedExtensionArgumentsWaitForMatchingOrderedSchema() {
        fun script(label:String)=JSONArray().put(JSONObject().put("name","fixture").put("is_alwayson",true)
            .put("args",JSONArray().put(JSONObject().put("label",label).put("value","plain"))))
        val fields=JSONArray().put(JSONArray().put("Text").put("TEXT").put("plain").put(JSONArray())
            .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL))
        val saved=JSONObject().put("t|fixture",JSONObject().put("schema",draftDigest("true"+stableJson(fields)))
            .put("edits",JSONObject().put("0","changed")))
        val settings=ExtensionSettings()
        settings.restore(false,saved)
        assertTrue(settings.requiresSchema(false))
        assertEquals(stableJson(saved),stableJson(settings.saved(false)))
        assertTrue(runCatching {settings.applyTo(JSONObject(),false)}.isFailure)
        settings.load(script("Text"))
        val body=JSONObject()
        settings.applyTo(body,false)
        assertEquals("changed",body.getJSONObject("alwayson_scripts").getJSONObject("fixture").getJSONArray("args").getString(0))
        settings.clear();settings.restore(false,saved);settings.load(script("Different field"))
        assertTrue(settings.review);assertNull(settings.saved(false))
    }
}
