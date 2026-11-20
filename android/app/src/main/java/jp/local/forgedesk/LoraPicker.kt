package jp.local.forgedesk

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun filterLoras(catalog:LoraCatalog, filter:String, term:String):List<IndexedValue<LoraEntry>> {
    val matchPreset=filter=="PC設定" && catalog.preset.isNotBlank() && catalog.filter!="Off"
    fun rank(e:LoraEntry)=when {
        !matchPreset || e.arch.isEmpty() -> 1
        catalog.preset in e.arch -> 0
        else -> 2
    }
    val query=term.trim()
    return catalog.entries.withIndex().filter {(_,e)->
        e.matches(query) && when(filter) {
            "PC設定" -> catalog.filter!="Hide other architectures" || rank(e)!=2
            "全件" -> true
            else -> e.arch.any {it.equals(filter,ignoreCase=true)}
        }
    }.sortedWith(compareBy<IndexedValue<LoraEntry>> {rank(it.value)}
        .thenBy(String.CASE_INSENSITIVE_ORDER) {it.value.name}
        .thenBy(String.CASE_INSENSITIVE_ORDER) {it.value.path})
}

internal data class LoraBranch(val children:List<Pair<String,Int>>,val entries:List<IndexedValue<LoraEntry>>,val count:Int)

internal fun loraBranch(rows:List<IndexedValue<LoraEntry>>, folder:String, search:Boolean):LoraBranch {
    val prefix=if(folder.isEmpty()) "" else "$folder/"
    val children=mutableMapOf<String,Int>()
    val entries=mutableListOf<IndexedValue<LoraEntry>>()
    var count=0
    for(row in rows) {
        val parent=row.value.folder
        if(parent!=folder && !parent.startsWith(prefix))continue
        count++
        if(search || parent==folder) entries.add(row) else {
            val child=prefix+parent.removePrefix(prefix).substringBefore('/')
            children[child]=children.getOrDefault(child,0)+1
        }
    }
    return LoraBranch(children.toList().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER){it.first}),entries,count)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LoraPicker(client:ForgeClient, close:()->Unit, choose:(String)->Unit) {
    val settings=LocalContext.current.getSharedPreferences("lora_browser",0)
    val origin=client.origin
    var browsing by rememberSaveable(origin){mutableStateOf(settings.getBoolean("folders:$origin",false))}
    var folder by rememberSaveable(origin){mutableStateOf(settings.getString("path:$origin","").orEmpty())}
    var query by rememberSaveable {mutableStateOf("")}
    var filter by rememberSaveable(origin) {mutableStateOf(settings.getString("filter:$origin","PC設定") ?: "PC設定")}
    var revision by remember {mutableIntStateOf(0)}
    var catalog by remember(client,revision){mutableStateOf<LoraCatalog?>(null)}
    var error by remember(client,revision){mutableStateOf("")}
    var inserting by remember(client){mutableStateOf(false)}
    val noCovers=remember(client,revision){mutableSetOf<String>()}
    val scope=rememberCoroutineScope()
    val grid=rememberLazyGridState()
    LaunchedEffect(client,revision) {
        try {catalog=client.loraCatalog()}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {error="一覧を取得できません。PCとの接続を確認してください。"}
    }
    val filterOptions=remember(catalog) {
        listOf("PC設定","全件")+catalog?.entries.orEmpty().flatMap {it.arch}
            .filter {it.isNotBlank()}.distinctBy {it.lowercase()}.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    LaunchedEffect(catalog){if(catalog!=null && filter !in filterOptions)filter="PC設定"}
    LaunchedEffect(query,filter,browsing,folder) {grid.scrollToItem(0)}
    LaunchedEffect(browsing,folder,filter,origin) {
        settings.edit().putBoolean("folders:$origin",browsing).putString("path:$origin",folder)
            .putString("filter:$origin",filter).apply()
    }
    val rows=remember(catalog,query,filter) {catalog?.let{filterLoras(it,filter,query)}.orEmpty()}
    val branch=remember(rows,browsing,folder,query){
        if(browsing)loraBranch(rows,folder,query.isNotBlank()) else LoraBranch(emptyList(),rows,rows.size)
    }
    val noImageInfo=remember(catalog) {catalog?.entries?.let {it.isNotEmpty() && it.none {e->e.thumbnailId.isNotBlank()}}==true}
    fun back(){if(browsing && folder.isNotEmpty()){folder=folder.substringBeforeLast('/',"");query=""}else close()}
    Dialog(onDismissRequest=::back,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp).semantics {testTagsAsResourceId=true},
            contentAlignment=Alignment.Center) {
            Surface(Modifier.widthIn(max=920.dp).fillMaxWidth().heightIn(max=820.dp).fillMaxHeight(),
                shape=MaterialTheme.shapes.large,tonalElevation=5.dp) {
                BoxWithConstraints(Modifier.padding(horizontal=12.dp,vertical=6.dp)) {
                    val wide=maxWidth>=540.dp
                    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("LoRA を選択",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                            Text("${branch.count} 件",style=MaterialTheme.typography.labelMedium)
                            TextButton(onClick={browsing=!browsing}) {Text(if(browsing)"一覧表示" else "フォルダー")}
                            TextButton(onClick=close){Text("閉じる")}
                        }
                        if(wide) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                LoraSearch(query,{query=it},Modifier.weight(1f))
                                LoraFilters(filter,filterOptions){filter=it}
                            }
                        } else {
                            LoraSearch(query,{query=it},Modifier.fillMaxWidth())
                            LoraFilters(filter,filterOptions){filter=it}
                        }
                        if(catalog==null && error.isEmpty() || inserting) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(error.isNotEmpty()) Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(error,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={revision++}){Text("再読み込み")}
                        }
                        if(noImageInfo) Text("接続先のLoRA一覧にはプレビュー情報がありません。",style=MaterialTheme.typography.bodySmall)
                        if(browsing) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                verticalAlignment=Alignment.CenterVertically) {
                                TextButton(onClick={folder="";query=""},enabled=folder.isNotEmpty()){Text("ルート")}
                                var parent=""
                                for(part in folder.split('/').filter {it.isNotEmpty()}) {
                                    val path=if(parent.isEmpty())part else "$parent/$part"
                                    Text("›",style=MaterialTheme.typography.labelMedium)
                                    TextButton(onClick={folder=path;query=""},enabled=folder!=path){Text(part)}
                                    parent=path
                                }
                            }
                        }
                        if(catalog!=null && branch.count==0) Text("候補はありません")
                        LazyVerticalGrid(columns=GridCells.Adaptive(130.dp),state=grid,
                            modifier=Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(bottom=6.dp),
                            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(branch.children,key={it.first}) { (path,count) ->
                                OutlinedCard(onClick={folder=path;query=""},
                                    modifier=Modifier.fillMaxWidth().testTag("lora_folder")
                                        .semantics {contentDescription="フォルダーを開く · "+path}) {
                                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                        Text("📁",style=MaterialTheme.typography.titleLarge)
                                        Text(path.substringAfterLast('/'),style=MaterialTheme.typography.titleSmall,
                                            maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text("$count 件",style=MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            items(branch.entries,key={it.index}) {row ->
                                val entry=row.value
                                LoraCard(client,entry,noCovers,!inserting) {
                                    if(inserting || entry.ambiguous) return@LoraCard
                                    inserting=true
                                    scope.launch {
                                        try {choose(client.loraInsertion(entry,catalog?.weight ?: "1"))}
                                        catch(e:CancellationException){throw e}
                                        catch(e:Exception){error="LoRAを挿入できませんでした。"}
                                        finally {inserting=false}
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
private fun LoraSearch(value:String,change:(String)->Unit,modifier:Modifier) {
    OutlinedTextField(value,change,modifier=modifier,singleLine=true,label={Text("検索語")})
}

@Composable
private fun LoraFilters(selected:String,options:List<String>,change:(String)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={open=true}){
            Text("方式 · $selected ▾",maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            for(option in options) DropdownMenuItem(text={Text(option)},onClick={open=false;change(option)})
        }
    }
}

@Composable
private fun LoraCard(client:ForgeClient,entry:LoraEntry,noCovers:MutableSet<String>,enabled:Boolean,pick:()->Unit) {
    val id=entry.thumbnailId
    var bitmap by remember(client,id){mutableStateOf<Bitmap?>(null)}
    var status by remember(client,id,noCovers){mutableStateOf(when {
        id.isEmpty() -> "画像情報なし"
        id in noCovers -> "プレビューなし"
        else -> "画像を読み込み中"
    })}
    LaunchedEffect(client,id,noCovers) {
        if(id.isEmpty() || id in noCovers) return@LaunchedEffect
        try {
            bitmap=client.loraThumbnails.load(id)
            if(bitmap==null) {noCovers.add(id);status="プレビューなし"} else status="プレビュー画像あり"
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {status="プレビューを取得できません"}
    }
    val folder=entry.folder.ifBlank {if(entry.duplicateName)"ルート" else ""}
    val description=buildString {
        append("LoRAを挿入 · ").append(entry.name)
        if(folder.isNotBlank())append(" · ").append(folder)
    }
    OutlinedCard(onClick=pick,enabled=enabled && !entry.ambiguous,
        modifier=Modifier.fillMaxWidth().testTag("lora_card").semantics {contentDescription=description;stateDescription=status}) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.32f).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment=Alignment.Center) {
            val image=bitmap
            if(image!=null) Image(remember(image){image.asImageBitmap()},null,Modifier.fillMaxSize().testTag("lora_preview")
                .semantics(mergeDescendants=true){contentDescription="LoRAのプレビュー画像"},
                contentScale=ContentScale.Fit)
            else Text(status,Modifier.padding(5.dp),style=MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.padding(horizontal=7.dp,vertical=5.dp)) {
            Text(entry.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)
            if(folder.isNotEmpty()) Text(folder,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
            if(entry.alias!=entry.name) Text(entry.alias,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
            if(entry.arch.isNotEmpty()) Text(entry.arch.joinToString(" · "),style=MaterialTheme.typography.labelSmall)
            if(entry.ambiguous) Text("Forgeの挿入名が重複",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
        }
    }
}
