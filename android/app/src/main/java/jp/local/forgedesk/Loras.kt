package jp.local.forgedesk

import android.net.Uri
import android.graphics.Bitmap
import android.util.JsonReader
import android.util.JsonToken
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal val loraThumbnailId=Regex("[0-9a-f]{32}")

data class LoraEntry(
    val name:String, val alias:String, val path:String, val arch:List<String>,
    val thumbnailId:String="", val ambiguous:Boolean=false, val duplicateName:Boolean=false,
    val preferredWeight:String?=null
) {
    fun matches(term:String):Boolean = name.contains(term,true) || alias.contains(term,true) || path.contains(term,true)
    val folder:String get()=path.substringBeforeLast('/', "")
}
data class LoraCatalog(val entries:List<LoraEntry>,val preset:String="",val filter:String="Off",val weight:String="1")

internal class LoraThumbnails(private val client:ForgeClient) {
    private val slots=Semaphore(3)
    private val bitmaps=object : LruCache<String,Bitmap>(6*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    suspend fun load(id:String):Bitmap? {
        bitmaps.get(id)?.let {return it}
        return slots.withPermit {
            bitmaps.get(id) ?: client.thumbnailBytes(id)?.let {bytes->
                previewBitmap(bytes,256).also {bitmaps.put(id,it)}
            }
        }
    }
}

private fun weightText(value:Any?):String? {
    val n=value?.toString()?.toBigDecimalOrNull() ?: return null
    return if(n.toDouble().isFinite()) n.stripTrailingZeros().toPlainString() else null
}

internal fun parseLoraCatalog(payload:JSONObject):LoraCatalog {
    val rows=payload.getJSONArray("items")
    val entries=(0 until rows.length()).mapNotNull { i ->
        val o=rows.optJSONObject(i) ?: return@mapNotNull null
        val name=o.optString("name").ifBlank {return@mapNotNull null}
        val types=o.optJSONArray("arch")
        val arch=if(types==null) emptyList() else (0 until types.length()).map {types.optString(it)}
        LoraEntry(name,o.optString("alias").ifBlank {name},o.optString("path",name),arch,
            o.optString("thumbnail_id").takeIf(loraThumbnailId::matches).orEmpty(),
            o.optBoolean("ambiguous"),o.optBoolean("duplicate_name"),weightText(o.opt("preferred_weight")))
    }
    // Older companions have relative paths and aliases, but lack the two collision flags.
    val names=entries.groupingBy {it.name.lowercase()}.eachCount()
    val identifiers=entries.flatMap {setOf(it.name.lowercase(),it.alias.lowercase())}.groupingBy {it}.eachCount()
    val resolved=entries.map {e->e.copy(ambiguous=e.ambiguous || identifiers.getValue(e.alias.lowercase())>1,
        duplicateName=e.duplicateName || names.getValue(e.name.lowercase())>1)}
    return LoraCatalog(resolved,payload.optString("preset"),payload.optString("filter"),weightText(payload.opt("weight")) ?: "1")
}

suspend fun ForgeClient.loraCatalog(): LoraCatalog = withContext(Dispatchers.IO) {
    try {
        parseLoraCatalog(JSONObject(get("/forge-remote/v1/loras").toString(Charsets.UTF_8)))
    } catch(e:CancellationException) {throw e}
    catch(e:Exception) {
        // A stock Forge server provides raw safetensors metadata. Only names leave this reader.
        val c=URL(origin+"/sdapi/v1/loras").openConnection() as HttpURLConnection
        val raw=try {
            c.instanceFollowRedirects=false; c.connectTimeout=8000; c.readTimeout=45000
            require(c.responseCode in 200..299) {"LoRA一覧の取得に失敗しました（HTTP ${c.responseCode}）"}
            c.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                JsonReader(input).use { reader ->
                    val entries=mutableListOf<Pair<String,String>>()
                    reader.beginArray()
                    while(reader.hasNext()) {
                        var name=""; var alias=""
                        reader.beginObject()
                        while(reader.hasNext()) {
                            val key=reader.nextName()
                            if(key in setOf("name","alias") && reader.peek()==JsonToken.STRING) {
                                val text=reader.nextString()
                                if(key=="name") name=text else alias=text
                            } else reader.skipValue()
                        }
                        reader.endObject()
                        if(name.isNotBlank()) entries+=name to alias
                    }
                    reader.endArray()
                    entries
                }
            }
        } finally { c.disconnect() }
        val options=try { JSONObject(get("/sdapi/v1/options").toString(Charsets.UTF_8)) }
            catch(cancel:CancellationException) {throw cancel}
            catch(ignored:Exception) { JSONObject() }
        // Raw Forge entries expose unfiltered aliases, including collisions. Match its filename fallback.
        val owners=mutableMapOf<String,Int>()
        for((name,alias) in raw) for(key in setOf(name.lowercase(),alias.lowercase())) owners[key]=(owners[key] ?: 0)+1
        val byFilename=options.optString("lora_preferred_name")=="Filename"
        val counts=raw.groupingBy {it.first.lowercase()}.eachCount()
        LoraCatalog(raw.map { (name,given) ->
            val alias=given.ifBlank {name}
            val conflict=alias.lowercase() in setOf("none","addams") || (owners[alias.lowercase()] ?: 0)>1
            val identifier=if(byFilename || conflict)name else alias
            LoraEntry(name,identifier,name,emptyList(),ambiguous=(owners[identifier.lowercase()] ?: 0)>1,
                duplicateName=(counts[name.lowercase()] ?: 0)>1)
        },weight=weightText(options.opt("extra_networks_default_multiplier")) ?: "1")
    }
}

suspend fun ForgeClient.loraInsertion(entry:LoraEntry, defaultWeight:String):String {
    require(!entry.ambiguous) { "Forgeが同じ挿入名を複数のLoRAに割り当てています" }
    val preferred=entry.preferredWeight ?: if(entry.duplicateName)null else try {
        val json=JSONObject(get("/tacapi/v1/lora-info/"+Uri.encode(entry.name)).toString(Charsets.UTF_8))
        weightText(json.opt("preferred weight"))
    } catch(e:CancellationException) {throw e}
    catch(e:Exception) {null}
    return "<lora:${entry.alias}:${preferred ?: defaultWeight}>"
}
