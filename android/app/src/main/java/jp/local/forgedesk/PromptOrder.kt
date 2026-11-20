package jp.local.forgedesk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

internal data class PromptSlot(val start:Int,val end:Int)
internal data class PromptLine(val number:Int,val positions:List<Int>)

internal class PromptOrder(val source:String) {
    val slots:List<PromptSlot> = buildList {
        var begin=0
        fun finish(end:Int) {
            var left=begin; var right=end
            while(left<right && source[left].isWhitespace()) left++
            while(right>left && source[right-1].isWhitespace()) right--
            if(left<right) add(PromptSlot(left,right))
        }
        val closing=ArrayDeque<Char>()
        var escaped=false
        var quoted=false
        for((index,ch) in source.withIndex()) {
            if(escaped) { escaped=false; continue }
            if(ch=='\\') { escaped=true; continue }
            if(ch=='"') { quoted=!quoted; continue }
            if(quoted) continue
            when(ch) {
                '(' -> closing.addLast(')')
                '[' -> closing.addLast(']')
                '{' -> closing.addLast('}')
                '<' -> closing.addLast('>')
                ')',']','}','>' -> if(closing.lastOrNull()==ch) closing.removeLast()
                ',', '\r', '\n' -> if(closing.isEmpty()) { finish(index); begin=index+1 }
            }
        }
        finish(source.length)
    }
    fun text(id:Int):String = slots[id].let { source.substring(it.start,it.end) }

    // Empty physical lines are visible. A multiline quoted/weighted expression already
    // contains its own line breaks and remains one draggable phrase.
    val lines:List<PromptLine> = run {
        val starts=mutableListOf(0)
        var i=0
        while(i<source.length) {
            val c=source[i++]
            if(c=='\r' && source.getOrNull(i)=='\n')i++
            if(c=='\r' || c=='\n')starts.add(i)
        }
        fun lineAt(offset:Int):Int {
            val pos=starts.binarySearch(offset)
            return if(pos>=0)pos else -pos-2
        }
        val positions=starts.map { mutableListOf<Int>() }
        val covered=BooleanArray(starts.size)
        slots.forEachIndexed { position,slot ->
            val first=lineAt(slot.start)
            positions[first].add(position)
            for(line in first+1..lineAt(slot.end-1))covered[line]=true
        }
        starts.indices.filter {positions[it].isNotEmpty() || !covered[it]}
            .map { PromptLine(it+1,positions[it]) }
    }

    // Delimiters, whitespace and empty fields stay in their original slots.
    fun apply(order:List<Int>, selected:Int):Pair<String,TextRange> {
        require(order.size==slots.size && order.toSet()==slots.indices.toSet())
        var selection=TextRange.Zero
        val result=buildString(source.length) {
            var offset=0
            for((i,id) in order.withIndex()) {
                val slot=slots[i]
                append(source,offset,slot.start)
                val start=length
                append(text(id))
                if(id==selected) selection=TextRange(start,length)
                offset=slot.end
            }
            append(source,offset,source.length)
        }
        return result to selection
    }
}

internal fun movePromptSlot(order:List<Int>,from:Int,to:Int):List<Int> {
    if(from==to || from !in order.indices || to !in order.indices) return order
    return order.toMutableList().apply { add(to,removeAt(from)) }
}

private fun promptAt(rects:Map<Int,Rect>, point:Offset, nearest:Boolean):Int? {
    val visible=rects.entries.filter { it.value.width>0 && it.value.height>0 }
    return visible.firstOrNull { it.value.contains(point) }?.key
        ?: if(nearest) visible.minByOrNull { (_,rect) ->
            val dx=point.x-point.x.coerceIn(rect.left,rect.right)
            val dy=point.y-point.y.coerceIn(rect.top,rect.bottom)
            dx*dx+dy*dy
        }?.key else null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptOrderDialog(source:String, negative:Boolean,selection:TextRange,
                               close:()->Unit, commit:(String,TextRange)->Unit) {
    val parser=remember(source) { PromptOrder(source) }
    val initial=remember(parser){ parser.slots.indices.toList() }
    var order by remember(parser) { mutableStateOf(initial) }
    var selected by remember(parser) { mutableIntStateOf(parser.slots.indexOfFirst { selection.min in it.start until it.end }) }
    var dragId by remember(parser) { mutableStateOf<Int?>(null) }
    var hover by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var bubble by remember { mutableStateOf(IntSize.Zero) }
    val rects=remember(parser){ mutableMapOf<Int,Rect>() }
    val listState=rememberLazyListState()
    val edge=with(LocalDensity.current){44.dp.toPx()}
    val clearance=with(LocalDensity.current){24.dp.toPx()}
    fun move(id:Int,to:Int) { order=movePromptSlot(order,order.indexOf(id),to) }
    LaunchedEffect(dragId) {
        if(dragId==null)return@LaunchedEffect
        var last=withFrameNanos { it }
        while(true) {
            val now=withFrameNanos { it }
            val dt=((now-last)/1_000_000_000f).coerceIn(0f,.05f)
            last=now
            val direction=when {
                finger.y<edge -> -((edge-finger.y)/edge).coerceIn(0f,1f)
                finger.y>viewport.height-edge -> ((finger.y-viewport.height+edge)/edge).coerceIn(0f,1f)
                else -> 0f
            }
            if(direction!=0f && listState.scrollBy(direction*edge*11f*dt)!=0f)
                hover=promptAt(rects,origin+finger,true)
        }
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.widthIn(max=920.dp).fillMaxWidth(.97f).fillMaxHeight(.91f),
            shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(negative)"ネガティブの並べ替え" else "プロンプトの並べ替え",style=MaterialTheme.typography.titleLarge)
                Text("語句をタップして選び、指で移動します。",style=MaterialTheme.typography.bodySmall)
                if(order.size<2) Text("カンマや改行で区切った語句が2つ以上必要です。",style=MaterialTheme.typography.bodySmall)
                val touch=Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewport=it }
                    .onGloballyPositioned { origin=it.positionInWindow() }
                    .pointerInput(selected,parser) {
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                            val from=promptAt(rects,origin+down.position,false)
                            if(from==null || order.getOrNull(from)!=selected || selected<0)return@awaitEachGesture
                            try {
                                while(true) {
                                    val event=awaitPointerEvent(PointerEventPass.Initial)
                                    val change=event.changes.firstOrNull { it.id==down.id } ?: break
                                    if(change.isConsumed)break
                                    if(!change.pressed) {
                                        if(dragId!=null) {
                                            change.consume()
                                            promptAt(rects,origin+change.position,true)?.let{move(selected,it)}
                                        }
                                        break
                                    }
                                    if(dragId==null && (change.position-down.position).getDistance()>=viewConfiguration.touchSlop)
                                        dragId=selected
                                    if(dragId!=null) {
                                        change.consume()
                                        finger=change.position
                                        hover=promptAt(rects,origin+finger,true)
                                    }
                                }
                            } finally { dragId=null; hover=null }
                        }
                    }
                BoxWithConstraints(touch) {
                    LazyColumn(Modifier.fillMaxSize(),state=listState,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        items(parser.lines,key={it.number}) { line ->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                                Text(line.number.toString(),modifier=Modifier.widthIn(min=29.dp).padding(top=10.dp,end=4.dp)
                                    .semantics { contentDescription="行 "+line.number },
                                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(Modifier.weight(1f).heightIn(min=30.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                    line.positions.forEach { position ->
                                        val id=order[position]
                                        val token=parser.text(id)
                                        val target=dragId!=null && hover==position && id!=selected
                                        DisposableEffect(position) { onDispose { rects.remove(position) } }
                                        FilterChip(selected=id==selected,onClick={selected=id},label={
                                            Text(token,style=MaterialTheme.typography.bodyMedium)
                                        },border=if(target) BorderStroke(3.dp,MaterialTheme.colorScheme.primary)
                                            else FilterChipDefaults.filterChipBorder(true,id==selected),
                                            modifier=Modifier.onGloballyPositioned { rects[position]=it.boundsInWindow() }
                                                .alpha(if(id==dragId).38f else 1f)
                                                .semantics { contentDescription=(position+1).toString()+". "+token })
                                    }
                                }
                            }
                        }
                    }
                    dragId?.let { id ->
                        Surface(Modifier.offset {
                            val x=(finger.x-bubble.width/2).roundToInt().coerceIn(0,(viewport.width-bubble.width).coerceAtLeast(0))
                            val above=finger.y-clearance-bubble.height
                            val y=(if(above>=0f)above else finger.y+clearance).roundToInt()
                                .coerceIn(0,(viewport.height-bubble.height).coerceAtLeast(0))
                            IntOffset(x,y)
                        }.widthIn(max=minOf(maxWidth,320.dp)).onSizeChanged { bubble=it }
                            .semantics { contentDescription="移動中の語句 · "+parser.text(id) },
                            color=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,
                            shape=MaterialTheme.shapes.medium,shadowElevation=7.dp) {
                            Text(parser.text(id),Modifier.padding(horizontal=12.dp,vertical=6.dp),maxLines=4,
                                overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    val index=order.indexOf(selected)
                    TextButton(onClick={move(selected,index-1)},enabled=index>0){Text("前へ")}
                    TextButton(onClick={move(selected,index+1)},enabled=index>=0 && index<order.lastIndex){Text("次へ")}
                    Spacer(Modifier.weight(1f))
                    TextButton(close){Text("戻る")}
                    Button(onClick={val (s,r)=parser.apply(order,selected);commit(s,r)},enabled=order!=initial){Text("適用")}
                }
            }
        }
    }
}
