package jp.local.forgedesk

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.math.BigDecimal

private val attention = Regex("""^\((.+):(-?(?:\d+(?:\.\d*)?|\.\d+))\)$""", RegexOption.DOT_MATCHES_ALL)
private val loraWeight = Regex("""^(<lora:[^<>]+:)(-?(?:\d+(?:\.\d*)?|\.\d+))>$""", RegexOption.IGNORE_CASE)
private enum class PromptPicker { TAG, SAVED, LORA }

internal fun phrase(value: TextFieldValue): TextRange {
    val text = value.text
    var start = value.selection.min
    var end = value.selection.max
    if (start == end) {
        while (start > 0 && text[start - 1] !in ",\n") start--
        while (end < text.length && text[end] !in ",\n") end++
    }
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end-1].isWhitespace()) end--
    return TextRange(start, end)
}

internal fun adjustPromptWeight(word: String, step: Int): String {
    val lora = loraWeight.matchEntire(word)
    val emphasis = attention.matchEntire(word)
    val match = lora ?: emphasis
    val weight = (match?.groupValues?.get(2)?.toBigDecimal() ?: BigDecimal.ONE)
        .add(BigDecimal.valueOf(step.toLong(), 1)).stripTrailingZeros().toPlainString()
    if (lora != null) return "${lora.groupValues[1]}$weight>"
    val label = emphasis?.groupValues?.get(1) ?: word
    return if (weight == "1") label else "($label:$weight)"
}

private fun replace(value: TextFieldValue, range: TextRange, content: String, select: Boolean): TextFieldValue {
    val end = range.min + content.length
    return TextFieldValue(
        text = value.text.replaceRange(range.min, range.max, content),
        selection = if (select) TextRange(range.min, end) else TextRange(end)
    )
}

internal fun insertPrompt(value: TextFieldValue, tag: String, completion: Boolean): TextFieldValue {
    var range = if (completion) phrase(value) else value.selection
    if (!completion && range.collapsed && range.min in 1 until value.text.length &&
        value.text[range.min-1] !in " ,\n" && value.text[range.min] !in " ,\n") {
        range = TextRange(phrase(value).max)
    }
    if (!tag.startsWith("<lora:", true)) {
        val normalized = tag.replace('_',' ').trim()
        val existing = Regex("""[^,\n]+""").findAll(value.text).firstOrNull { part ->
            val word = part.value.trim().replace('_',' ')
            word.equals(normalized,true) && (!completion || part.range.last < range.min || part.range.first >= range.max)
        }
        if (existing != null) {
            val start = existing.range.first + existing.value.indexOfFirst { !it.isWhitespace() }
            return value.copy(selection=TextRange(start, start + existing.value.trim().length))
        }
    }
    val before = value.text.substring(0, range.min)
    val after = value.text.substring(range.max)
    if(!tag.startsWith("<lora:",true)) return insertDelimitedTag(before,tag,after)
    val left = when {
        before.isBlank() || before.endsWith('\n') -> ""
        before.trimEnd().endsWith(',') -> if (before.last().isWhitespace()) "" else " "
        else -> ", "
    }
    val right = if (after.isBlank() || after.startsWith('\n') || after.trimStart().startsWith(',')) "" else ", "
    return replace(value, range, "$left$tag$right", false)
}

internal suspend fun remoteTags(vm: ForgeModel, query: String): List<TagChoice> =
    decodeTagChoices(JSONArray(vm.client.get("/forge-remote/v1/tags?formatted=true&q=" +
        Uri.encode(tagSearchText(query))).toString(Charsets.UTF_8)))

private fun externalEdit(old: TextFieldValue, text: String): TextFieldValue {
    val selection = TextRange(old.selection.start.coerceAtMost(text.length),old.selection.end.coerceAtMost(text.length))
    return TextFieldValue(text,selection)
}

@Composable
fun PromptScreen(vm: ForgeModel) {
    var positive by remember { mutableStateOf(TextFieldValue(vm.prompt, TextRange(vm.prompt.length))) }
    var negative by remember { mutableStateOf(TextFieldValue(vm.negative, TextRange(vm.negative.length))) }
    LaunchedEffect(vm.prompt) {
        if (vm.prompt != positive.text) positive = externalEdit(positive, vm.prompt)
    }
    LaunchedEffect(vm.negative) {
        if (vm.negative != negative.text) negative = externalEdit(negative, vm.negative)
    }
    var useNegative by remember { mutableStateOf(false) }
    var orderSource by remember { mutableStateOf<TextFieldValue?>(null) }
    var orderNegative by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PromptPicker?>(null) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("native_prompt_tags", Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(prefs.getStringSet("tags", emptySet()).orEmpty().toSortedSet().toList()) }
    val active = if (useNegative) negative else positive
    fun update(value: TextFieldValue) {
        if (useNegative) { negative = value; vm.negative = value.text }
        else { positive = value; vm.prompt = value.text }
    }
    fun changeWeight(step: Int) {
        val range = phrase(active)
        if (range.collapsed) { vm.notify("強調する語句にカーソルを置いてください"); return }
        val word = active.text.substring(range.min, range.max)
        update(replace(active, range, adjustPromptWeight(word, step), true))
    }
    val range = phrase(active)
    val fragment = active.text.substring(range.min, range.max)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("プロンプト編集", style=MaterialTheme.typography.headlineSmall)
        TagField(vm, positive, { positive=it; vm.prompt=it.text }, label="プロンプト",
            modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused) useNegative=false }, minLines=3, maxLines=8)
        TagField(vm, negative, { negative=it; vm.negative=it.text }, label="ネガティブプロンプト",
            modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused) useNegative=true }, minLines=2, maxLines=6)
        Text(if (useNegative) "ネガティブの選択語句" else "プロンプトの選択語句", style=MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton({changeWeight(-1)}) { Text("弱く −0.1") }
            OutlinedButton({changeWeight(1)}) { Text("強く ＋0.1") }
            TextButton(onClick={orderSource=active;orderNegative=useNegative}) {Text("並べ替え")}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button({picker=PromptPicker.LORA}) { Text("LoRA を挿入") }
            OutlinedButton({picker=PromptPicker.TAG}) { Text("タグ検索") }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton({picker=PromptPicker.SAVED}) { Text("端末のタグ") }
            TextButton({editingTag=fragment}) { Text("タグを登録") }
        }
        HorizontalDivider()
        AdDetailerPanel(vm)
    }
    orderSource?.let { original ->
        PromptOrderDialog(original.text,orderNegative,original.selection,{orderSource=null}) { text,selectedRange ->
            val current=if(orderNegative) vm.negative else vm.prompt
            if(current!=original.text) vm.notify("編集中にプロンプトが更新されました。もう一度並べ替えを開いてください")
            else if(orderNegative) {negative=TextFieldValue(text,selectedRange); vm.negative=text}
            else {positive=TextFieldValue(text,selectedRange); vm.prompt=text}
            orderSource=null
        }
    }
    editingTag?.let { text ->
        AlertDialog(onDismissRequest={editingTag=null}, title={Text("端末にタグを登録")}, text={
            OutlinedTextField(text,{editingTag=it},label={Text("タグ名")},singleLine=true)
        }, confirmButton={
            TextButton(enabled=text.isNotBlank(),onClick={
                val tags=(saved + text.trim()).toSortedSet()
                prefs.edit().putStringSet("tags",tags).apply(); saved=tags.toList(); editingTag=null
            }) { Text("保存") }
        }, dismissButton={TextButton({editingTag=null}){Text("戻る")}})
    }
    picker?.let { kind ->
        val insert:(String)->Unit={ tag ->
            update(insertPrompt(active,tag,false))
            picker=null
        }
        if(kind==PromptPicker.LORA) LoraPicker(vm.client,{picker=null},insert)
        else PromptLibrary(vm,kind,saved,{picker=null},insert)
    }
}

@Composable
private fun PromptLibrary(vm: ForgeModel, type: PromptPicker, saved: List<String>, close: ()->Unit, choose: (String)->Unit) {
    var query by remember(type) { mutableStateOf("") }
    var all by remember(type) { mutableStateOf(emptyList<TagChoice>()) }
    var error by remember(type) { mutableStateOf("") }
    var busy by remember(type) { mutableStateOf(false) }
    val remoteQuery=if(type==PromptPicker.TAG) query.trim() else ""
    LaunchedEffect(type,remoteQuery,vm.client) {
        error=""; busy=false; all=emptyList()
        try {
            if(type==PromptPicker.TAG) {
                all=saved.map(::savedTagChoice).filter { it.name.contains(query,true) || it.insert.contains(query,true) }
                if(remoteQuery.isNotEmpty()) { busy=true; delay(300); all=mergeTagChoices(all,remoteTags(vm,remoteQuery)) }
            } else all=saved.map(::savedTagChoice)
        } catch (e: CancellationException) { throw e }
        catch(e: Exception) { error="PC辞書を取得できません。補助拡張と接続先を確認してください。" }
        finally { busy=false }
    }
    val filtered=if(type==PromptPicker.TAG) all else all.filter { it.name.contains(query,true) || it.insert.contains(query,true) }
    AlertDialog(onDismissRequest=close,title={Text(if(type==PromptPicker.TAG)"タグを検索" else "端末のタグ")},text={
        Column {
            OutlinedTextField(query,{query=it},label={Text("検索語")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(type==PromptPicker.TAG && vm.dictionaryStatus.isNotBlank())Text(vm.dictionaryStatus,style=MaterialTheme.typography.labelSmall)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotEmpty()) Text(error)
            if(!busy && error.isEmpty() && filtered.isEmpty()) Text(if(type==PromptPicker.TAG && query.isBlank()) "検索語を入力してください" else "候補はありません")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
                items(filtered,key={it.name}) { item ->
                    TextButton({choose(item.insert)},Modifier.fillMaxWidth()) {
                        Text(item.name,Modifier.weight(1f))
                        item.count?.let {
                            Text(java.text.NumberFormat.getIntegerInstance().format(it)+" 件",
                                Modifier.padding(start=8.dp),style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    },confirmButton={TextButton(close){Text("閉じる")}})
}
