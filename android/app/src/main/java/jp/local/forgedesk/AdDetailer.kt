package jp.local.forgedesk

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class AdField(val id:String, val type:String, val choices:List<String>,
    val minimum:Double?, val maximum:Double?, val step:Double?) {
    fun value(text:String):Any = when(type) {
        "boolean" -> text.toBooleanStrict()
        "integer","number" -> {
            val v=text.toDoubleOrNull()
            require(v!=null && v.isFinite() && (minimum==null || v>=minimum) && (maximum==null || v<=maximum)) {
                "${adLabel(id)} の数値が範囲外です"
            }
            if(type=="integer") {
                require(v==v.toInt().toDouble()) {"${adLabel(id)} は整数で指定してください"}
                v.toInt()
            } else v
        }
        else -> text
    }
}

private fun values(array:JSONArray?):List<String> =
    if(array==null) emptyList() else (0 until array.length()).mapNotNull {array.opt(it) as? String}

internal class AdSlot(val defaults:JSONObject) {
    private val edits:SnapshotStateMap<String,String> = mutableStateMapOf()
    fun text(key:String) = edits[key] ?: if(defaults.isNull(key)) "" else defaults.get(key).toString()
    fun set(key:String,value:String){edits[key]=value}
    fun saved(fields:List<AdField>)=JSONObject().also { out ->
        for (f in fields) edits[f.id]?.let { out.put(f.id,JSONObject().put("type",f.type).put("value",it)) }
    }
    fun restore(fields:List<AdField>, data:JSONObject?):Boolean {
        edits.clear()
        if(data==null)return true
        var compatible=true
        for(id in data.keys()){
            val old=data.optJSONObject(id)
            val field=fields.firstOrNull {it.id==id}
            val value=old?.opt("value") as? String
            if(field==null || value==null || old?.optString("type")!=field.type ||
                (field.choices.isNotEmpty() && id!="ad_controlnet_module" && value !in field.choices)) {
                compatible=false
            } else edits[id]=value
        }
        return compatible
    }
    fun json(fields:List<AdField>):JSONObject {
        val obj=JSONObject()
        for(f in fields) {
            if(f.id in edits) obj.put(f.id,f.value(edits.getValue(f.id)))
            else if(defaults.has(f.id) && !defaults.isNull(f.id)) obj.put(f.id,defaults.get(f.id))
        }
        fun ordered(min:String,max:String) {
            if(obj.has(min) && obj.has(max)) require(obj.getDouble(min)<=obj.getDouble(max)) {
                "${adLabel(min)} は ${adLabel(max)} 以下で指定してください"
            }
        }
        ordered("ad_mask_min_ratio","ad_mask_max_ratio")
        ordered("ad_controlnet_guidance_start","ad_controlnet_guidance_end")
        return obj
    }
}

internal class AdMode(val fields:List<AdField>, val slots:List<AdSlot>, private val defaultSkip:Boolean=false) {
    var enabled by mutableStateOf(false)
    var skip by mutableStateOf(defaultSkip)
    var selected by mutableIntStateOf(0)
    fun saved():JSONObject? {
        val data=JSONArray()
        slots.forEach { data.put(it.saved(fields)) }
        if(!enabled && skip==defaultSkip && selected==0 && (0 until data.length()).all {data.getJSONObject(it).length()==0})return null
        return JSONObject().put("enabled",enabled).put("skip",skip).put("selected",selected).put("slots",data)
    }
    fun restore(data:JSONObject?):Boolean {
        val entries=data?.optJSONArray("slots")
        val incompatibleSlot=(slots.size until (entries?.length() ?: 0)).any { (entries?.optJSONObject(it)?.length() ?: 0)>0 }
        var compatible=!incompatibleSlot
        slots.forEachIndexed { i,s -> if(!s.restore(fields,entries?.optJSONObject(i)))compatible=false }
        enabled=compatible && data?.optBoolean("enabled")==true
        skip=data?.optBoolean("skip",defaultSkip) ?: defaultSkip
        selected=(data?.optInt("selected") ?: 0).coerceIn(0,slots.lastIndex)
        return compatible
    }
    fun arguments(image:Boolean):JSONObject {
        if(!enabled)return JSONObject()
        val args=JSONArray().put(true).put(image && skip)
        var active=false
        for(slot in slots) {
            if(!slot.text("ad_tab_enable").equals("false",true) && slot.text("ad_model").let {it.isNotBlank() && it!="None"}) {
                args.put(slot.json(fields));active=true
            } else args.put(JSONObject().put("ad_tab_enable",false).put("ad_model","None"))
        }
        require(active) {"ADetailerで有効にするタブと検出モデルを選んでください"}
        return JSONObject().put("ADetailer",JSONObject().put("args",args))
    }
}

internal fun adModeInfo(schema:JSONObject, scripts:JSONArray, mode:Boolean, models:List<String>, bridge:JSONObject):AdMode {
    val script=(0 until scripts.length()).mapNotNull(scripts::optJSONObject).firstOrNull {
        it.optString("name").equals("ADetailer",true) && it.optBoolean("is_img2img")==mode
    } ?: error("PCにADetailerが見つかりません")
    val args=script.getJSONArray("args")
    val properties=schema.getJSONObject("properties")
    val controls=bridge.optJSONObject("controls")
    val profiles=bridge.optJSONObject("profiles")?.optJSONObject(if(mode)"img2img" else "txt2img")
    val fields=properties.keys().asSequence().filter {it.startsWith("ad_")}.map { id ->
        val p=properties.getJSONObject(id);val ui=controls?.optJSONObject(id)
        val choices=when {
            id=="ad_model" -> (listOf("None")+models).distinct()
            !values(ui?.optJSONArray("choices")).isEmpty() -> values(ui?.optJSONArray("choices"))
            else -> values(p.optJSONArray("enum"))
        }
        fun bound(key:String)=((ui?.opt(key) as? Number) ?: (p.opt(key) as? Number))?.toDouble()
        AdField(id,p.optString("type","string"),choices,bound("minimum"),bound("maximum"),bound("step"))
    }.toList()
    val slots=(2 until args.length()).map { index ->
        val state=args.getJSONObject(index).optJSONObject("value") ?: error("ADetailerの引数形式を確認してください")
        val saved=profiles?.optJSONObject((index-2).toString())
        val obj=JSONObject()
        for(f in fields) {
            val value=when {
                saved!=null && !saved.isNull(f.id) -> saved.get(f.id)
                !state.isNull(f.id) -> state.get(f.id)
                else -> properties.getJSONObject(f.id).opt("default")
            }
            if(value!=null && value!=JSONObject.NULL)obj.put(f.id,value)
        }
        AdSlot(obj)
    }
    require(slots.isNotEmpty()) {"ADetailerのタブがありません"}
    return AdMode(fields,slots,mode && (profiles?.optJSONObject("0")?.optBoolean("ad_skip_img2img") ?: args.optJSONObject(1)?.optBoolean("value") ?: false))
}

class AdDetailerState {
    private val modes=mutableStateMapOf<Boolean,AdMode>()
    private val pending=mutableStateMapOf<Boolean,JSONObject>()
    private var revision=0
    var review by mutableStateOf(false)
        private set
    internal fun get(image:Boolean)=modes[image]
    fun clear(){revision++;modes.clear();pending.clear();review=false}
    internal fun saved(image:Boolean):JSONObject? = modes[image]?.saved() ?: pending[image]
    internal fun restore(image:Boolean,data:JSONObject?){
        val mode=modes[image]
        if(mode!=null){if(!mode.restore(data))review=true;return}
        if(data==null)pending.remove(image) else pending[image]=data
    }
    internal fun requiresSchema(image:Boolean) = pending[image]?.optBoolean("enabled")==true
    internal suspend fun load(client:ForgeClient) {
        val version=revision
        val schema=JSONObject(client.get("/adetailer/v1/schema").toString(Charsets.UTF_8))
        val scripts=JSONArray(client.get("/sdapi/v1/script-info").toString(Charsets.UTF_8))
        val models=values(JSONObject(client.get("/adetailer/v1/ad_model").toString(Charsets.UTF_8)).optJSONArray("ad_model"))
        val bridge=try { JSONObject(client.get("/forge-remote/v1/ad-controls").toString(Charsets.UTF_8)) }
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){JSONObject()}
        val parsed=listOf(false,true).associateWith {adModeInfo(schema,scripts,it,models,bridge)}
        if(version!=revision)return
        parsed.forEach {(mode,settings)->if(mode !in modes){
            if(!settings.restore(pending.remove(mode)))review=true
            modes[mode]=settings
        }}
    }
}
internal fun adScripts(vm:ForgeModel):JSONObject {
    require(!vm.adDetailer.requiresSchema(vm.imgMode)){"保存したADetailerの設定をPCに照合できません。接続を確認してください"}
    return vm.adDetailer.get(vm.imgMode)?.arguments(vm.imgMode) ?: JSONObject()
}

private val groups=linkedMapOf(
    "検出" to "model model_classes confidence hires_fix_only mask_filter_method mask_k mask_min_ratio mask_max_ratio",
    "プロンプト・LoRA" to "prompt negative_prompt copy_main_loras copy_main_lora_triggers copy_main_lora_triggers_only",
    "マスク" to "x_offset y_offset dilate_erode mask_merge_invert mask_blur",
    "インペイント" to "denoising_strength inpaint_only_masked inpaint_only_masked_padding use_inpaint_width_height inpaint_width inpaint_height inpaint_scale restore_face",
    "サンプリング・モデル" to "use_steps steps use_cfg_scale cfg_scale use_checkpoint checkpoint use_vae vae use_sampler sampler scheduler use_noise_multiplier noise_multiplier use_clip_skip clip_skip",
    "自動タグ" to "use_autotag autotag_general_thresh autotag_character_thresh autotag_hide_rating autotag_character_first autotag_remove_underscore",
    "ControlNet" to "controlnet_model controlnet_module controlnet_weight controlnet_guidance_start controlnet_guidance_end"
)
private val labels=mapOf(
    "model" to "検出モデル","model_classes" to "検出クラス","confidence" to "検出信頼度","hires_fix_only" to "Hires. fix時のみ",
    "mask_filter_method" to "上位マスクの並べ方","mask_k" to "上位マスク数（0で全件）","mask_min_ratio" to "最小面積率","mask_max_ratio" to "最大面積率",
    "prompt" to "ADetailer プロンプト","negative_prompt" to "ADetailer ネガティブ","copy_main_loras" to "メインのLoRAを追加",
    "copy_main_lora_triggers" to "LoRAのトリガーを追加","copy_main_lora_triggers_only" to "使用中のトリガーのみ",
    "x_offset" to "マスクの横移動","y_offset" to "マスクの縦移動","dilate_erode" to "縮小（−）／拡張（＋）","mask_merge_invert" to "マスクの結合・反転",
    "mask_blur" to "マスクぼかし","denoising_strength" to "変化の強さ","inpaint_only_masked" to "マスク部分のみ処理","inpaint_only_masked_padding" to "マスク余白 px",
    "use_inpaint_width_height" to "専用の画像サイズ","inpaint_width" to "最小幅 px","inpaint_height" to "最小高さ px","inpaint_scale" to "インペイント倍率","restore_face" to "顔を修復",
    "use_steps" to "専用ステップ数","steps" to "ステップ数","use_cfg_scale" to "専用CFG","cfg_scale" to "CFG",
    "use_checkpoint" to "専用チェックポイント","checkpoint" to "チェックポイント","use_vae" to "専用VAE","vae" to "VAE",
    "use_sampler" to "専用サンプラー・スケジューラー","sampler" to "サンプラー","scheduler" to "スケジューラー",
    "use_noise_multiplier" to "専用ノイズ倍率","noise_multiplier" to "ノイズ倍率","use_clip_skip" to "専用CLIP skip","clip_skip" to "CLIP skip",
    "use_autotag" to "自動タグを有効化","autotag_general_thresh" to "一般タグのしきい値","autotag_character_thresh" to "キャラクターのしきい値",
    "autotag_hide_rating" to "レーティングを除外","autotag_character_first" to "キャラクタータグを優先","autotag_remove_underscore" to "_ を空白に置換",
    "controlnet_model" to "ControlNetモデル","controlnet_module" to "プリプロセッサー","controlnet_weight" to "重み",
    "controlnet_guidance_start" to "適用開始","controlnet_guidance_end" to "適用終了"
)
private fun adLabel(id:String)=labels[id.removePrefix("ad_")] ?: id.removePrefix("ad_").replace('_',' ')

@Composable
fun AdDetailerPanel(vm:ForgeModel) {
    var open by remember {mutableStateOf(false)}
    val endpoint=vm.client
    val state=vm.adDetailer.get(vm.imgMode)
    var failure by remember(endpoint) {mutableStateOf("")}
    var retry by remember {mutableIntStateOf(0)}
    var group by remember(vm.imgMode) {mutableStateOf("検出")}
    val selected=state?.selected ?: 0
    TextButton(onClick={open=!open}) { Text("ADetailer"+(if(state?.enabled==true)" · 有効" else "")+if(open)" ▴" else " ▾") }
    if(!open)return
    if(vm.adDetailer.review)Text("PCの項目が変更されました。ADetailerの設定を確認してください。")
    LaunchedEffect(endpoint,retry) {
        if(vm.adDetailer.get(vm.imgMode)==null) {
            failure=""
            try {vm.adDetailer.load(endpoint)}
            catch(cancel:CancellationException){throw cancel}
            catch(e:Exception){failure=e.localizedMessage ?: "設定を取得できません"}
        }
    }
    if(state==null) {
        if(failure.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {Text(failure);TextButton({retry++}){Text("ADetailer設定を再取得")}}
        return
    }
    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(state.enabled,{state.enabled=it});Text(if(vm.imgMode)"img2imgで使用" else "txt2imgで使用")}
    if(!state.enabled)return
    if(vm.imgMode) Row(verticalAlignment=Alignment.CenterVertically){Checkbox(state.skip,{state.skip=it});Text("元のimg2img処理をスキップ")}
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        state.slots.forEachIndexed {i,_ -> FilterChip(selected==i,{state.selected=i},label={Text("タブ "+(i+1))})}
    }
    val slot=state.slots[selected]
    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(slot.text("ad_tab_enable").toBoolean(),{slot.set("ad_tab_enable",it.toString())});Text("このタブを有効化")}
    val known=groups.values.flatMap {it.split(' ')}.map {"ad_"+it}.toSet()+"ad_tab_enable"
    val others=state.fields.map {it.id}.filterNot {it in known}
    val sections=if(others.isEmpty())groups.keys.toList() else groups.keys+"その他"
    sections.forEach {name->
        TextButton(onClick={group=if(group==name)"" else name}){Text(name+if(group==name)" ▴" else " ▾")}
        if(group==name) {
            val ids=groups[name]?.split(' ')?.map {"ad_"+it} ?: others
            ids.forEach {id->
                state.fields.firstOrNull {it.id==id}?.let {field-> key(selected,id) { AdControl(field,slot) } }
            }
        }
    }
}

@Composable
private fun AdControl(field:AdField, slot:AdSlot) {
    val current=slot.text(field.id)
    val label=adLabel(field.id)
    when {
        field.type=="boolean" -> Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(current.toBoolean(),{slot.set(field.id,it.toString())});Text(label)
        }
        field.choices.isNotEmpty() && field.id!="ad_controlnet_module" -> {
            var choosing by remember {mutableStateOf(false)}
            OutlinedButton({choosing=true},Modifier.fillMaxWidth()){Text("$label · "+current.ifBlank{"選択"},maxLines=2)}
            if(choosing) {
                var term by remember {mutableStateOf("")}
                AlertDialog(onDismissRequest={choosing=false},title={Text(label)},text={
                    Column {
                        if(field.choices.size>10) OutlinedTextField(term,{term=it},label={Text("絞り込み")},singleLine=true)
                        LazyColumn(Modifier.heightIn(max=320.dp)) {
                            items(field.choices.filter {it.contains(term,true)}) {value->
                                TextButton({slot.set(field.id,value);choosing=false}) {Text(value)}
                            }
                        }
                    }
                },confirmButton={TextButton({choosing=false}){Text("閉じる")}})
            }
        }
        else -> {
            val numeric=field.type=="integer" || field.type=="number"
            val range=listOfNotNull(field.minimum,field.maximum).joinToString(" ～ ")
            val multiline=field.id in setOf("ad_prompt","ad_negative_prompt")
            OutlinedTextField(current,{slot.set(field.id,it)},label={Text(label+if(numeric && range.isNotEmpty())" ($range)" else "")},
                singleLine=!multiline,minLines=if(multiline)2 else 1,modifier=Modifier.fillMaxWidth(),
                keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Decimal else KeyboardType.Text))
        }
    }
}
