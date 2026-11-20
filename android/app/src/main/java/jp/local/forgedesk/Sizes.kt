package jp.local.forgedesk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ArMode {
    var locked by mutableStateOf(false)
    var reversed by mutableStateOf(false)
    var oneDimension by mutableStateOf(false)
    var average by mutableIntStateOf(0)
    var precision by mutableStateOf("64")
}

private data class ArItem(val name:String,val reverse:String="",val w:Int=0,val h:Int=0,val comment:String="")

private fun arItems(arr:JSONArray):List<ArItem> = List(arr.length()){index->
    val row=arr.getJSONObject(index)
    ArItem(row.getString("label"),row.optString("reverse"),row.optInt("width"),row.optInt("height"),row.optString("comment"))
}

@Composable
fun SizePresets(vm:ForgeModel) {
    val mode=vm.aspectRatio
    var open by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        OutlinedButton(onClick={open=true},contentPadding=PaddingValues(horizontal=10.dp)){Text("サイズプリセット")}
        TextButton(onClick={val w=vm.width;vm.width=vm.height;vm.height=w},contentPadding=PaddingValues(horizontal=6.dp)){Text("縦横を交換")}
    }
    if(!open)return
    val scope=rememberCoroutineScope()
    val client=vm.client
    var revision by remember{mutableIntStateOf(0)}
    var ratios by remember(client){mutableStateOf(emptyList<ArItem>())}
    var resolutions by remember(client){mutableStateOf(emptyList<ArItem>())}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var comment by remember{mutableStateOf("")}
    LaunchedEffect(client,revision){
        busy=true;error=""
        try {
            val data=JSONObject(client.get("/forge-remote/v1/size-presets").toString(Charsets.UTF_8))
            ratios=arItems(data.getJSONArray("ratios"));resolutions=arItems(data.getJSONArray("resolutions"))
        }catch(e:CancellationException){throw e}
        catch(e:Exception){error="AR-PlusPlusの設定を取得できません。 "+e.localizedMessage.orEmpty()}
        finally{busy=false}
    }
    fun average():Int? {
        val w=vm.width.toIntOrNull()?:return null
        val h=vm.height.toIntOrNull()?:return null
        return if(w in 1..65536 && h in 1..65536)(w+h+1)/2 else null
    }
    fun ratio(item:ArItem){
        if(busy)return
        val current=average()
        val precision=mode.precision.toIntOrNull()
        if(current==null){error="幅と高さを正の整数で入力してください";return}
        if(precision==null || precision !in 4..128 || precision%4!=0){error="丸め単位は4～128の4の倍数です";return}
        val base=if(mode.locked && mode.average>0)mode.average else current
        val request=JSONObject().put("ratio",item.name).put("width",vm.width.toInt()).put("height",vm.height.toInt())
            .put("average",base).put("precision",precision).put("reverse",mode.reversed).put("one_dimension",mode.oneDimension)
        val originalMode=vm.imgMode
        busy=true;error=""
        scope.launch {
            try {
                val result=client.post("/forge-remote/v1/size-presets/ratio",request)
                if(vm.client!==client || vm.imgMode!=originalMode)return@launch
                vm.width=result.getInt("width").toString()
                vm.height=result.getInt("height").toString()
                mode.average=base;comment=item.comment
            } catch(e:CancellationException){throw e}
            catch(e:Exception){error="比率を適用できません。 "+e.localizedMessage.orEmpty()}
            finally{busy=false}
        }
    }
    AlertDialog(onDismissRequest={open=false},title={Text("サイズ  "+vm.width+" × "+vm.height)},
        text={
            Column(Modifier.fillMaxWidth().heightIn(max=510.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("アスペクト比",style=MaterialTheme.typography.titleSmall)
                PresetGrid(ratios,3,{if(mode.reversed)it.reverse else it.name},!busy,::ratio)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    FilterChip(mode.reversed,{mode.reversed=!mode.reversed},label={Text("比率を反転")},enabled=!busy)
                    FilterChip(mode.oneDimension,{mode.oneDimension=!mode.oneDimension},label={Text("一辺基準")},enabled=!busy)
                }
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(mode.locked,{ value->
                        if(value && mode.average<=0)mode.average=average()?:0
                        mode.locked=value
                    },enabled=!busy)
                    Text("基準サイズを固定 ("+(if(mode.locked)mode.average else average()?:0)+" px)")
                }
                OutlinedTextField(mode.precision,{mode.precision=it},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
                    label={Text("丸め単位（4～128 px）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                Text("固定解像度",style=MaterialTheme.typography.titleSmall)
                PresetGrid(resolutions,2,{it.name},!busy){ item->
                    vm.width=item.w.toString();vm.height=item.h.toString()
                    if(!mode.locked)mode.average=(item.w+item.h+1)/2
                    comment=item.comment;error=""
                }
                if(comment.isNotBlank())Text(comment,style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick={open=false}){Text("閉じる")}},
        dismissButton={TextButton(onClick={revision++},enabled=!busy){Text("PCから再読込")}})
}

@Composable
private fun PresetGrid(items:List<ArItem>,columns:Int,label:(ArItem)->String,enabled:Boolean,select:(ArItem)->Unit) {
    items.chunked(columns).forEach{group->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            group.forEach { item ->
                OutlinedButton(onClick={select(item)},enabled=enabled,modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=2.dp)){
                    Text(label(item),style=MaterialTheme.typography.labelMedium)
                }
            }
            repeat(columns-group.size){Spacer(Modifier.weight(1f))}
        }
    }
}
