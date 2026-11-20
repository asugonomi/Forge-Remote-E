package jp.local.forgedesk

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Only owned by the two panes in the current composition. */
internal class TagSuggestions {
    private var owner:Any?=null
    var tags by mutableStateOf(emptyList<TagChoice>())
        private set
    var choose:(String)->Unit={}
        private set
    fun begin(source:Any) { owner=source; tags=emptyList(); choose={} }
    fun publish(source:Any, values:List<TagChoice>, selected:(String)->Unit) {
        if (source===owner) {tags=values; choose=selected}
    }
    fun dismiss(source:Any) {
        if(source===owner) dismissCurrent()
    }
    fun dismissCurrent() {owner=null;tags=emptyList();choose={}}
}
internal val LocalPreviewTags = compositionLocalOf<TagSuggestions?> { null }

@Composable
internal fun TagField(vm:ForgeModel, value:TextFieldValue, onValueChange:(TextFieldValue)->Unit,
                      label:String, modifier:Modifier=Modifier, minLines:Int=1, maxLines:Int=8) {
    val shared=LocalPreviewTags.current
    val suggestions=shared ?: remember { TagSuggestions() }
    val source=remember { Any() }
    val ctx=LocalContext.current
    val currentChange by rememberUpdatedState(onValueChange)
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    DisposableEffect(suggestions,source){onDispose { suggestions.dismiss(source) }}
    LaunchedEffect(focused,editing,value.text,value.selection,suggestions,vm.client) {
        if(!focused || !editing) { suggestions.dismiss(source); return@LaunchedEffect }
        val range=phrase(value)
        val fragment=value.text.substring(range.min,range.max)
        suggestions.begin(source)
        if(fragment.isBlank() || fragment.length>80 || fragment.contains('<'))return@LaunchedEffect
        delay(200)
        val saved=ctx.getSharedPreferences("native_prompt_tags",Context.MODE_PRIVATE)
            .getStringSet("tags",emptySet()).orEmpty()
            .map(::savedTagChoice).filter { it.name.contains(fragment,true) || it.insert.contains(fragment,true) }
            .sortedBy { it.name }
        fun show(choices:List<TagChoice>) {
            val visible=choices.take(32).associateBy {it.name}
            suggestions.publish(source,visible.values.toList()) {name->
                visible[name]?.let { candidate ->
                    editing=false
                    suggestions.dismiss(source)
                    currentChange(insertPrompt(value,candidate.insert,true))
                }
            }
        }
        show(saved)
        try {
            val remote=remoteTags(vm,fragment)
            show(mergeTagChoices(saved,remote))
        } catch(e:CancellationException){throw e}
          catch(_:Exception){/* Local saved tags remain available when the PC is offline. */}
    }
    OutlinedTextField(value,{newValue ->
        if(newValue.text!=value.text) editing=true
        if(newValue.text!=value.text || newValue.selection!=value.selection) suggestions.dismiss(source)
        onValueChange(newValue)
    },label={Text(label)},modifier=modifier.onFocusChanged {
        focused=it.isFocused
        if(!it.isFocused){editing=false;suggestions.dismiss(source)}
    },minLines=minLines,maxLines=maxLines)
    if(shared==null && suggestions.tags.isNotEmpty()) TagStrip(suggestions)
}

@Composable
internal fun TagStrip(suggestions:TagSuggestions,modifier:Modifier=Modifier.fillMaxWidth().heightIn(max=260.dp)) {
    val tags=suggestions.tags
    if(tags.isEmpty())return
    val grid=rememberLazyGridState()
    val formatter=remember{NumberFormat.getIntegerInstance(Locale.JAPAN)}
    LaunchedEffect(tags.firstOrNull()?.name){grid.scrollToItem(0)}
    BackHandler { suggestions.dismissCurrent() }
    Surface(modifier.semantics{contentDescription="入力中のタグ候補"},color=MaterialTheme.colorScheme.surface){
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("タグ候補",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                Text("${tags.size} 件",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={suggestions.dismissCurrent()},modifier=Modifier.semantics{contentDescription="タグ候補を閉じる"}){
                    Text("閉じる")
                }
            }
            HorizontalDivider()
            LazyVerticalGrid(GridCells.Adaptive(224.dp),state=grid,modifier=Modifier.fillMaxWidth().weight(1f),
                contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp)) {
                items(tags,key={it.name}) { tag ->
                    OutlinedCard(onClick={suggestions.choose(tag.name)},modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().heightIn(min=54.dp).padding(horizontal=12.dp,vertical=7.dp),
                            verticalArrangement=Arrangement.Center) {
                            Text(tag.name,softWrap=true,style=MaterialTheme.typography.bodyLarge,
                                color=MaterialTheme.colorScheme.onSurface)
                            tag.count?.let {count->
                                Text("${formatter.format(count)} 件",style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PreviewWithTags(vm:ForgeModel,suggestions:TagSuggestions) {
    val showing=suggestions.tags.isNotEmpty()
    Box(Modifier.fillMaxSize()){
        val visibility=if(showing)Modifier.alpha(0f).clearAndSetSemantics{} else Modifier
        Box(Modifier.fillMaxSize().then(visibility)){ResultsPane(vm)}
        if(showing)TagStrip(suggestions,Modifier.fillMaxSize())
    }
}
