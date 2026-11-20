package jp.local.forgedesk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

private enum class FieldKind { BOOL, CHOICE, NUMBER, TEXT, OTHER }

private data class Arg(val label:String, val default:Any?, val choices:List<String>,
                       val low:BigDecimal?, val high:BigDecimal?, val step:BigDecimal?) {
    val kind = when {
        label.isBlank() -> FieldKind.OTHER
        default is Boolean -> FieldKind.BOOL
        choices.isNotEmpty() && default is String && default in choices -> FieldKind.CHOICE
        choices.isNotEmpty() -> FieldKind.OTHER
        default is Number -> FieldKind.NUMBER
        default is String -> FieldKind.TEXT
        else -> FieldKind.OTHER
    }
    fun parse(text:String):Any = when (kind) {
        FieldKind.BOOL -> text.toBooleanStrict()
        FieldKind.CHOICE -> text.also { require(it in choices) { "$label の選択肢を確認してください" } }
        FieldKind.TEXT -> text
        FieldKind.OTHER -> error("$label は専用の入力形式が必要です")
        FieldKind.NUMBER -> {
            val n = text.trim().toBigDecimalOrNull() ?: error("$label に数値を入力してください")
            require(n.toDouble().isFinite() && (low==null || n>=low) && (high==null || n<=high)) {
                "$label の範囲を確認してください"
            }
            if (step != null && step.signum()>0) {
                require((n - (low ?: BigDecimal.ZERO)).remainder(step).signum()==0) { "$label は ${step.toPlainString()} 刻みです" }
            } else if (default is Int || default is Long) {
                require(n.stripTrailingZeros().scale()<=0) { "$label に整数を入力してください" }
            }
            if(n.stripTrailingZeros().scale()<=0) runCatching { n.longValueExact() }.getOrElse { n.toDouble() }
            else n.toDouble()
        }
    }
}

private data class ScriptDef(val name:String,val img:Boolean,val always:Boolean,val args:List<Arg>,val signature:String) {
    val id = (if(img) "i|" else "t|")+name
    val unsupported = args.mapIndexedNotNull { n,a -> if(a.kind==FieldKind.OTHER) a.label.ifBlank{"引数 ${n+1}"} else null }
    val reserved = name.lowercase() in setOf("adetailer","sampler","seed")
    val usable = unsupported.isEmpty() && !reserved && !(always && args.isEmpty())
    val reason get()=when {
        name.equals("adetailer",true) -> "プロンプト編集のADetailer設定を使用します。"
        reserved -> "生成画面の設定を使用します。"
        unsupported.isNotEmpty() -> "専用の入力形式が必要です（${unsupported.joinToString("、")}）。"
        always && args.isEmpty() -> "調整可能な引数はありません。"
        else -> ""
    }
}

class ExtensionSettings {
    private var definitions by mutableStateOf(emptyList<ScriptDef>())
    private val chosen = mutableStateMapOf<String,Map<Int,String>>()
    private val pending=mutableStateMapOf<Boolean,JSONObject>()
    private var revision=0
    var review by mutableStateOf(false)
        private set
    fun clear() { revision++;definitions=emptyList(); chosen.clear();pending.clear();review=false }
    internal fun saved(image:Boolean):JSONObject? {
        pending[image]?.let{return it}
        val out=JSONObject()
        for (d in definitions.filter {it.img==image && it.id in chosen}) {
            val edits=JSONObject()
            chosen.getValue(d.id).forEach { (i,text)->edits.put(i.toString(),text) }
            out.put(d.id,JSONObject().put("schema",d.signature).put("edits",edits))
        }
        return out.takeIf {it.length()>0}
    }
    internal fun restore(image:Boolean,data:JSONObject?) {
        val prefix=if(image)"i|" else "t|"
        chosen.keys.filter {it.startsWith(prefix)}.forEach {chosen.remove(it)}
        pending.remove(image)
        if(data==null || data.length()==0)return
        if(definitions.isEmpty()){pending[image]=data;return}
        for (id in data.keys()) {
            val d=definitions.firstOrNull {it.img==image && it.id==id}
            val item=data.optJSONObject(id)
            val edits=item?.optJSONObject("edits")
            if(d==null || !d.usable || item?.optString("schema")!=d.signature || edits==null){review=true;continue}
            val values=mutableMapOf<Int,String>()
            for(k in edits.keys()){
                val i=k.toIntOrNull()
                val text=edits.opt(k) as? String
                if(i!=null && i in d.args.indices && text!=null)values[i]=text
            }
            enable(d,true)
            chosen[id]=values
        }
    }
    internal fun requiresSchema(image:Boolean)=pending.containsKey(image)
    internal suspend fun refresh(client:ForgeClient){
        val version=revision
        val json=JSONArray(client.get("/sdapi/v1/script-info").toString(Charsets.UTF_8))
        if(version==revision)load(json)
    }
    fun applyTo(body:JSONObject, imgMode:Boolean) {
        require(!requiresSchema(imgMode)){"保存した拡張設定をPCに照合できません。接続を確認してください"}
        val always = body.optJSONObject("alwayson_scripts") ?: JSONObject()
        for (d in definitions.filter { it.img==imgMode && chosen.containsKey(it.id) }) {
            require(d.usable) { "${d.name} の入力形式が変わりました" }
            val edits=chosen.getValue(d.id)
            val args=JSONArray()
            for ((i, field) in d.args.withIndex()) {
                // Keep server-supplied types and order; convert only the explicitly edited fields.
                args.put(if(i in edits) field.parse(edits.getValue(i)) else field.default)
            }
            if(d.always) {
                require(always.keys().asSequence().none { it.equals(d.name,true) }) { "${d.name} の設定が重複しています" }
                always.put(d.name,JSONObject().put("args",args))
            } else {
                body.put("script_name",d.name); body.put("script_args",args)
            }
        }
        body.put("alwayson_scripts",always)
    }
    private fun enable(d:ScriptDef,on:Boolean) {
        if (!on) { chosen.remove(d.id); return }
        if (!d.always) definitions.filter { !it.always && it.img==d.img }.forEach { chosen.remove(it.id) }
        chosen[d.id]=emptyMap()
    }
    internal fun load(json:JSONArray) {
        val list=(0 until json.length()).mapNotNull { n ->
            val s=json.optJSONObject(n) ?: return@mapNotNull null
            val args=s.optJSONArray("args") ?: return@mapNotNull null
            val name=s.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fields=(0 until args.length()).map { index ->
                val a=args.optJSONObject(index) ?: JSONObject()
                val c=a.optJSONArray("choices")
                val choices=c?.let { xs -> (0 until xs.length()).map { xs.opt(it) } }.orEmpty()
                val validChoices=choices.all { it is String }
                // A list of labels alone cannot safely describe a multi-select, numeric index or file input.
                val default = if(a.isNull("value") || !validChoices) null else a.opt("value")
                fun decimal(key:String) = (a.opt(key) as? Number)?.toString()?.toBigDecimalOrNull()
                Arg(a.optString("label"), default, choices.map { it.toString() },decimal("minimum"),decimal("maximum"),decimal("step"))
            }
            val signature=JSONArray()
            for(field in fields)signature.put(JSONArray().put(field.label).put(field.kind.name).put(field.default)
                .put(JSONArray(field.choices)).put(field.low?.toString()).put(field.high?.toString()).put(field.step?.toString()))
            val always=s.optBoolean("is_alwayson")
            ScriptDef(name,s.optBoolean("is_img2img"),always,fields,draftDigest(always.toString()+stableJson(signature)))
        }
        // Drop opt-ins when a reconnect changes argument order, defaults or allowed values.
        val previous=definitions.associate { it.id to it.signature }
        val current=list.associate { it.id to it.signature }
        chosen.keys.toList().filter { previous[it]!=current[it] || it !in current }.forEach { chosen.remove(it) }
        definitions=list
        pending.toMap().forEach { (image,data)->restore(image,data) }
    }

    @Composable
    fun Screen(vm:ForgeModel) {
        var refresh by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var expanded by remember(vm.imgMode) { mutableStateOf<String?>(null) }
        val endpoint=vm.client
        LaunchedEffect(endpoint,refresh) {
            busy=true; error=""
            try { this@ExtensionSettings.refresh(endpoint) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { error="拡張設定を取得できませんでした。"+e.localizedMessage.orEmpty() }
            finally { busy=false }
        }
        var filter by remember { mutableStateOf("") }
        val all=definitions.filter { it.img==vm.imgMode && it.name.contains(filter.trim(),true) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("拡張機能",style=MaterialTheme.typography.headlineSmall)
            if(review) Text("PCの拡張項目が変更されました。使用する拡張の設定を確認してください。")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!vm.imgMode,{vm.mode(false)},label={Text("テキストから")})
                FilterChip(vm.imgMode,{vm.mode(true)},label={Text("画像から")})
                TextButton({refresh++}) {Text("再取得")}
            }
            OutlinedTextField(filter,{filter=it},label={Text("拡張名で絞り込み")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            for(always in listOf(true,false)) {
                val group=all.filter { it.always==always }.sortedBy { it.name.lowercase() }
                if(group.isNotEmpty()) Text(if(always) "常時実行スクリプト" else "選択式スクリプト（1件）",
                    style=MaterialTheme.typography.titleMedium)
                for(d in group) key(d.id) {
                    val included=chosen.containsKey(d.id)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            if(d.name.equals("ADetailer",ignoreCase=true) && d.always) {
                                AdDetailerPanel(vm)
                                return@Column
                            }
                            Text(d.name,style=MaterialTheme.typography.titleMedium)
                            if(!d.usable) Text(d.reason,style=MaterialTheme.typography.bodySmall)
                            else {
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                                    horizontalArrangement=Arrangement.SpaceBetween) {
                                    Text(if(always) "次の生成に設定を送る" else "次の生成で使用")
                                    Switch(included,{on -> enable(d,on); if(on) expanded=d.id },modifier=Modifier.semantics {contentDescription=d.name+" の生成設定"})
                                }
                                if(included && d.args.isNotEmpty()) {
                                    TextButton({expanded=if(expanded==d.id)null else d.id}) {
                                        Text(if(expanded==d.id)"項目を閉じる" else "項目を編集")
                                    }
                                    if(expanded==d.id) d.args.forEachIndexed { i,field ->
                                        val value=chosen[d.id]?.get(i) ?: field.default.toString()
                                        ExtensionField(field,value) { changed -> chosen[d.id]=chosen[d.id].orEmpty() + (i to changed) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionField(arg:Arg,text:String,onChange:(String)->Unit) {
    when(arg.kind) {
        FieldKind.BOOL -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
            verticalAlignment=Alignment.CenterVertically) {
            Text(arg.label,Modifier.weight(1f)); Switch(text.toBoolean(),{onChange(it.toString())},modifier=Modifier.semantics {contentDescription=arg.label})
        }
        FieldKind.CHOICE -> {
            var dialog by remember { mutableStateOf(false) }
            OutlinedButton({dialog=true},modifier=Modifier.fillMaxWidth()) { Text(arg.label+" · "+text) }
            if(dialog) {
                var q by remember { mutableStateOf("") }
                AlertDialog(onDismissRequest={dialog=false},title={Text(arg.label)},text={
                    Column {
                        if(arg.choices.size>10) OutlinedTextField(q,{q=it},label={Text("絞り込み")},singleLine=true)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
                            items(arg.choices.filter { it.contains(q,true) }) { item ->
                                TextButton({onChange(item);dialog=false},Modifier.fillMaxWidth()){Text(item)}
                            }
                        }
                    }
                },confirmButton={TextButton({dialog=false}){Text("閉じる")}})
            }
        }
        FieldKind.NUMBER -> {
            OutlinedTextField(text,onChange,label={Text(arg.label)},singleLine=true,modifier=Modifier.fillMaxWidth(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                supportingText={Text(listOfNotNull(arg.low?.let {"最小 $it"},arg.high?.let {"最大 $it"},arg.step?.let {"刻み $it"}).joinToString("　"))})
            val lo=arg.low; val hi=arg.high; val step=arg.step
            if(lo!=null && hi!=null && step!=null && hi>lo && step>BigDecimal.ZERO) {
                val count=(hi-lo).divideToIntegralValue(step)
                if(count>=BigDecimal.ONE && count<=BigDecimal(1000)) {
                    val pos=(text.toBigDecimalOrNull() ?: lo).minus(lo).divideToIntegralValue(step).toFloat()
                    CompactSlider(pos.coerceIn(0f,count.toFloat()),{x->
                        val n=lo+step.multiply(BigDecimal(Math.round(x)))
                        onChange(n.stripTrailingZeros().toPlainString())
                    },valueRange=0f..count.toFloat(),steps=count.toInt()-1)
                }
            }
        }
        FieldKind.TEXT -> OutlinedTextField(text,onChange,label={Text(arg.label)},maxLines=4,modifier=Modifier.fillMaxWidth())
        FieldKind.OTHER -> Text("専用の入力形式が必要です")
    }
}

@Composable fun ExtensionsScreen(vm:ForgeModel) { vm.extensionSettings.Screen(vm) }
