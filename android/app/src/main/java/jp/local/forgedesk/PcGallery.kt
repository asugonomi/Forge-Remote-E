package jp.local.forgedesk

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val INDEX = "/forge-remote/v1/gallery-index"
private const val IMAGES = "/forge-remote/v1/gallery"
private const val PAGE_SIZE = 24

internal fun galleryDateQuery(raw:String):String=Normalizer.normalize(raw.trim(),Normalizer.Form.NFKC)
    .replace('−','-').replace('ー','-').replace('/','-')

private data class OutputDay(val day:String,val count:Int)
private data class DateList(val version:Long,val total:Int,val days:List<OutputDay>)
private data class OutputPicture(val id:String,val name:String,val day:String,val folderDay:Boolean,val modified:String,val source:String)
private data class OutputPage(val offset:Int,val total:Int,val items:List<OutputPicture>)

private suspend fun dates(client:ForgeClient,fullScan:Boolean):DateList=withContext(Dispatchers.Default){
    val data=JSONObject(client.get("$INDEX/dates?refresh=$fullScan").toString(Charsets.UTF_8))
    val days=data.getJSONArray("days")
    DateList(data.getLong("version"),data.getInt("total"),List(days.length()){
        val row=days.getJSONObject(it)
        OutputDay(row.getString("day"),row.getInt("count"))
    })
}

private suspend fun outputPage(client:ForgeClient,day:String,offset:Int,version:Long):OutputPage=withContext(Dispatchers.Default){
    val filter=if(day.isEmpty()) "" else "&day="+android.net.Uri.encode(day)
    val data=JSONObject(client.get("$INDEX/page?offset=$offset&limit=$PAGE_SIZE&version=$version$filter").toString(Charsets.UTF_8))
    val items=data.getJSONArray("items")
    OutputPage(data.getInt("offset"),data.getInt("total"),List(items.length()){
        val r=items.getJSONObject(it)
        OutputPicture(r.getString("id"),r.getString("name"),r.getString("day"),
            r.getString("day_source")=="folder",r.getString("modified"),r.getString("source"))
    })
}

private class OutputThumbnails(val client:ForgeClient) {
    private val slots=Semaphore(3)
    private val cache=object:LruCache<String,Bitmap>(8*1024*1024){
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    suspend fun get(pic:OutputPicture):Bitmap=slots.withPermit {
        cache.get(pic.id) ?: previewBitmap(client.get("$IMAGES/${pic.id}/thumb"),256).also { cache.put(pic.id,it) }
    }
}

@Composable
internal fun PcOutputGallery(vm:ForgeModel) {
    val client=vm.client
    var serial by remember(client) { mutableIntStateOf(0) }
    var day by rememberSaveable(client.origin) { mutableStateOf("") }
    var page by rememberSaveable(client.origin) { mutableIntStateOf(0) }
    var detail by rememberSaveable(client.origin) { mutableIntStateOf(-1) }
    var immersive by rememberSaveable(client.origin) { mutableStateOf(false) }
    var chooseDate by rememberSaveable { mutableStateOf(false) }
    var listing by remember(client,serial) { mutableStateOf<DateList?>(null) }
    var datesError by remember(client,serial) { mutableStateOf("") }
    var rescan by remember(client) { mutableStateOf(false) }
    val refresh:()->Unit={rescan=true;serial++}
    LaunchedEffect(client,serial) {
        try {
            val d=dates(client,rescan)
            listing=d
            if(day.isNotEmpty() && d.days.none { it.day==day }) {day="";page=0;detail=-1}
            val count=if(day.isEmpty()) d.total else d.days.firstOrNull{it.day==day}?.count ?: 0
            page=page.coerceIn(0,((count-1)/PAGE_SIZE).coerceAtLeast(0))
            if(detail>=count) detail=-1
        } catch(e:CancellationException){throw e} catch(e:Exception){datesError="PCの画像一覧を取得できません。接続とPC補助のバージョンを確認してください。 "+e.message.orEmpty()}
    }
    val catalog=listing
    if(catalog==null){
        Column(Modifier.padding(12.dp)) {
            if(datesError.isEmpty()){LinearProgressIndicator(Modifier.fillMaxWidth());Text("PCの画像を確認中")}
            else {Text(datesError,color=MaterialTheme.colorScheme.error);TextButton(onClick=refresh){Text("再試行")}}
        }
        return
    }
    if(chooseDate){
        DateChooser(catalog,day,onClose={chooseDate=false}) {selected->
            day=selected;page=0;detail=-1;chooseDate=false
        }
    }
    val offset=(if(detail>=0) detail/PAGE_SIZE else page)*PAGE_SIZE
    var rows by remember(catalog,day,offset) { mutableStateOf<OutputPage?>(null) }
    var error by remember(catalog,day,offset) { mutableStateOf("") }
    val thumbs=remember(client,catalog.version){OutputThumbnails(client)}
    LaunchedEffect(client,catalog.version,day,offset){
        try {rows=outputPage(client,day,offset,catalog.version)}
        catch(e:CancellationException){throw e}
        catch(e:Exception){
            if(e.message.orEmpty().startsWith("HTTP 409")) {
                page=0;detail=-1;rescan=false;serial++;vm.notify("PCの画像一覧を更新しました")
            } else error="画像を読み込めません。 "+e.message.orEmpty()
        }
    }
    val current=rows
    val closeDetail:()->Unit={page=detail.coerceAtLeast(0)/PAGE_SIZE;detail=-1;immersive=false}
    if(detail>=0) {
        // Use a full-window viewer even on a folded or two-pane device.
        Dialog(onDismissRequest=closeDetail,properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=10.dp)) {
                    val total=if(day.isEmpty()) catalog.total else catalog.days.firstOrNull{it.day==day}?.count ?: 0
                    val previous:(()->Unit)?=if(detail>0) ({detail--}) else null
                    val next:(()->Unit)?=if(detail+1<total) ({detail++}) else null
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick=closeDetail){Text("一覧へ")}
                        Spacer(Modifier.weight(1f))
                        Text("${detail+1} / $total",style=MaterialTheme.typography.labelLarge)
                        TextButton(onClick={detail--},enabled=detail>0){Text("前の画像")}
                        TextButton(onClick={detail++},enabled=detail+1<total){Text("次の画像")}
                    }
                    val item=current?.items?.getOrNull(detail-offset)
                    if(item!=null) key(item.id) { OutputViewer(vm,item,immersive,{immersive=it},previous,next) }
                    else if(error.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else { Text(error);TextButton(onClick={closeDetail();refresh()}){Text("一覧を更新")} }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=10.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(day.ifEmpty{"すべての日付"},Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TextButton(onClick={chooseDate=true}){Text("日付を選ぶ")}
            TextButton(onClick=refresh){Text("更新")}
        }
        if(error.isNotEmpty()){Text(error,color=MaterialTheme.colorScheme.error);TextButton(onClick=refresh){Text("再試行")}}
        else if(current==null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if(current.items.isEmpty()) Text("この日付の画像はありません。")
        else {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("${current.offset+1} ～ ${current.offset+current.items.size} / ${current.total} 枚",
                    Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                TextButton(onClick={page--},enabled=page>0){Text("前のページ")}
                TextButton(onClick={page++},enabled=offset+current.items.size<current.total){Text("次のページ")}
            }
            key(day,offset){
                LazyVerticalGrid(columns=GridCells.Adaptive(122.dp),contentPadding=PaddingValues(bottom=12.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(current.items,key={_,item->item.id}) { i,item->
                        OutputTile(item,thumbs,current.offset+i+1){immersive=false;detail=current.offset+i}
                    }
                }
            }
        }
    }
}

@Composable
private fun DateChooser(catalog:DateList,current:String,onClose:()->Unit,select:(String)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    val dates=remember(query,catalog){
        val term=galleryDateQuery(query)
        catalog.days.filter { term in it.day || term in it.day.replace("-","") }
    }
    Dialog(onDismissRequest=onClose) {
        Surface(shape=MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max=600.dp).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("出力日を選ぶ",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick=onClose){Text("閉じる")}
                }
                OutlinedTextField(query,{query=it},singleLine=true,label={Text("日付を検索")},placeholder={Text("例 2026-11")},modifier=Modifier.fillMaxWidth())
                TextButton(onClick={select("")}){Text("すべての日付（${catalog.total} 枚）")}
                if(dates.isEmpty()) Text("該当する日付はありません。")
                LazyColumn {
                    items(dates,key={it.day}){d->
                        OutlinedButton(onClick={select(d.day)},modifier=Modifier.fillMaxWidth()) {
                            Text(d.day + if(d.day==current) " ✓" else "",Modifier.weight(1f))
                            Text("${d.count} 枚")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputTile(item:OutputPicture,thumbs:OutputThumbnails,number:Int,select:()->Unit) {
    var bitmap by remember(thumbs,item.id){mutableStateOf<Bitmap?>(null)}
    var failed by remember(thumbs,item.id){mutableStateOf(false)}
    LaunchedEffect(thumbs,item.id) {
        try {bitmap=thumbs.get(item)} catch(e:CancellationException){throw e} catch(_:Exception){failed=true}
    }
    OutlinedCard(onClick=select,modifier=Modifier.semantics{contentDescription="画像 $number を開く"}) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.35f),contentAlignment=Alignment.Center) {
            val image=bitmap
            if(image!=null) Image(image.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else if(failed) Text("プレビューなし",style=MaterialTheme.typography.labelSmall)
            else CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
        }
        Column(Modifier.padding(6.dp)){
            Text(item.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
            Text(item.day+" · "+item.source,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OutputViewer(vm:ForgeModel,item:OutputPicture,immersive:Boolean,setImmersive:(Boolean)->Unit,
                         previous:(()->Unit)?,next:(()->Unit)?) {
    val client=vm.client
    val scope=rememberCoroutineScope()
    val base="$IMAGES/${item.id}"
    var bitmap by remember {mutableStateOf<Bitmap?>(null)}
    var showInfo by rememberSaveable {mutableStateOf(false)}
    var info by remember {mutableStateOf<String?>(null)}
    var infoError by remember {mutableStateOf("")}
    var imageError by remember {mutableStateOf("")}
    var retry by remember {mutableIntStateOf(0)}
    var opening by remember {mutableStateOf(false)}
    LaunchedEffect(client,base,retry){
        imageError=""
        try {bitmap=previewBitmap(client.get("$base/display"),1600)}
        catch(e:CancellationException){throw e} catch(e:Exception){imageError="プレビューを表示できません。 "+e.message.orEmpty()}
    }
    LaunchedEffect(client,base,showInfo){
        if(showInfo && info==null){
            infoError=""
            try {info=JSONObject(client.get("$base/info").toString(Charsets.UTF_8)).optString("parameters")}
            catch(e:CancellationException){throw e} catch(e:Exception){infoError="生成情報を取得できません。 "+e.message.orEmpty()}
        }
    }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(item.name,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
            TextButton(onClick={setImmersive(true)},enabled=bitmap!=null){Text("全画面表示")}
        }
        Text("${item.source}　${if(item.folderDay) "出力フォルダーの日付" else "更新日で分類"} ${item.day}",
            style=MaterialTheme.typography.bodySmall)
        Text("PCの更新日時 "+item.modified.replace("T"," "),style=MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
            val image=bitmap
            if(image!=null) ZoomableResult(image,Modifier.fillMaxSize(),"選択した画像")
            else if(imageError.isEmpty()) CircularProgressIndicator()
            else Column{Text(imageError);TextButton(onClick={retry++}){Text("再試行")}}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick={showInfo=!showInfo}){Text(if(showInfo) "PNG Infoを閉じる" else "PNG Info")}
            Button(enabled=!opening,onClick={opening=true;scope.launch {
                try {vm.useImage(client.get(base))}
                catch(e:CancellationException){throw e} catch(e:Exception){vm.notify("元画像を開けません。 "+e.message.orEmpty())}
                finally {opening=false}
            }}){Text(if(opening) "転送中…" else "img2imgに使う")}
        }
        if(showInfo) {
            val text=info
            if(infoError.isNotEmpty()) Text(infoError,color=MaterialTheme.colorScheme.error)
            else if(text==null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if(text.isBlank()) Text("生成情報はありません。")
            else {
                TextButton(onClick={vm.restore(text)}){Text("生成設定を復元")}
                Box(Modifier.fillMaxWidth().weight(.7f).verticalScroll(rememberScrollState())) {
                    SelectionContainer{Text(text,style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
    }
    val full=bitmap
    if(immersive && full!=null) {
        val high by produceState(full,client,base) {
            try {value=previewBitmap(client.get("$base/display?large=true"),3072)}
            catch(e:CancellationException){throw e}
            catch(_:Exception){ /* Use the already displayed preview if the larger copy is unavailable. */ }
        }
        FullscreenImage(high,"PCギャラリーの画像",{setImmersive(false)},previous,next)
    }
}
