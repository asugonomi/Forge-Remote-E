package jp.local.forgedesk

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

private data class Published(val id:String,val number:Int)

internal fun generationStateLabel(state:String):String=when(state) {
    "waiting" -> "待機"
    "done" -> "完了"
    "done_with_skip" -> "スキップ操作後に完了"
    "skipped" -> "スキップ"
    "interrupted" -> "中断要求後に終了"
    "empty" -> "画像なしで終了"
    "failed" -> "失敗"
    else -> "結果未確認"
}

private fun noImageMessage(job:ForgeJob?):String=when(job?.state) {
    "interrupted" -> "中断後にPCから返された画像はありません。"
    "skipped" -> "スキップ後にPCから返された画像はありません。"
    "empty" -> "生成が終了しました。PCから返された画像はありません。"
    "failed" -> "生成に失敗しました。詳しくはキューで確認できます。"
    "unknown" -> "結果を確認できません。PCの履歴を確認してください。"
    else -> ""
}

@Composable
fun ResultsPane(vm:ForgeModel) {
    val queue=vm.queue
    val active=queue.jobs.firstOrNull {it.state=="active"}
    var observedId by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(active?.id) {if(active!=null) observedId=active.id}
    if(active!=null) {
        key(active.id) { ActiveResultPane(queue,active) }
        return
    }
    val frames by remember(queue) { derivedStateOf {
        queue.jobs.filter {it.total>0}.flatMap {job -> List(job.total){Published(job.id,it)}}
    } }
    val observed=queue.jobs.firstOrNull { it.id==observedId }
    val emptyMessage=if(observed?.total==0)noImageMessage(observed) else ""
    val latest=frames.lastOrNull {it.id==observedId} ?: frames.lastOrNull()
    var selectedId by rememberSaveable {mutableStateOf("")}
    var selectedNumber by rememberSaveable {mutableIntStateOf(0)}
    var lastLatestId by rememberSaveable {mutableStateOf("")}
    var lastLatestNumber by rememberSaveable {mutableIntStateOf(-1)}
    fun select(p:Published){selectedId=p.id;selectedNumber=p.number}
    LaunchedEffect(latest){
        if(latest!=null && (latest.id!=lastLatestId || latest.number!=lastLatestNumber)){
            select(latest)
            lastLatestId=latest.id
            lastLatestNumber=latest.number
        }
    }
    val selected=Published(selectedId,selectedNumber)
    val index=frames.indexOf(selected).let {if(it<0)frames.lastIndex else it}
    val frame=frames.getOrNull(index)
    Column(Modifier.fillMaxSize().padding(6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(if(frame!=null && emptyMessage.isNotBlank())"以前の生成結果" else "生成結果",
                Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            if(frame!=null) {
                TextButton(enabled=index>0,onClick={select(frames[index-1])},contentPadding=PaddingValues(2.dp),
                    modifier=Modifier.width(44.dp).semantics{contentDescription="前の画像"}){Text("‹")}
                Text((index+1).toString()+" / "+frames.size,style=MaterialTheme.typography.labelLarge)
                TextButton(enabled=index<frames.lastIndex,onClick={select(frames[index+1])},contentPadding=PaddingValues(2.dp),
                    modifier=Modifier.width(44.dp).semantics{contentDescription="次の画像"}){Text("›")}
            }
        }
        if(frame==null){
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                Text(emptyMessage.ifBlank {"生成が完了すると画像が表示されます。"},style=MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        if(emptyMessage.isNotBlank()) Text(emptyMessage,style=MaterialTheme.typography.bodySmall)
        var retry by remember(frame){mutableIntStateOf(0)}
        var bitmap by remember(frame,retry){mutableStateOf<Bitmap?>(null)}
        var error by remember(frame,retry){mutableStateOf("")}
        LaunchedEffect(frame,retry){
            try {
                bitmap=previewBitmap(queue.image(frame.id,frame.number),3072)
            } catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.localizedMessage ?: "画像を開けませんでした"}
        }
        val current=bitmap
        if(current==null){
            Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){
                if(error.isBlank())CircularProgressIndicator()
                else Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Text("結果画像を表示できません。 "+error,color=MaterialTheme.colorScheme.error)
                    TextButton(onClick={retry++}){Text("再読み込み")}
                }
            }
        }else key(frame,current){
            ZoomableResult(current,Modifier.fillMaxWidth().weight(1f),showFullscreen=true)
        }
    }
}

@Composable
private fun ActiveResultPane(queue:ForgeQueue,job:ForgeJob) {
    val belongsToJob=queue.livePreviewJobId==job.id
    val frame=if(belongsToJob)queue.livePreview else null
    val hint=if(belongsToJob)queue.previewStatus else ""
    Column(Modifier.fillMaxSize().padding(horizontal=6.dp,vertical=4.dp),
        verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("生成プレビュー",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            frame?.let { FullscreenAction(it,"生成中のプレビュー画像") }
        }
        GenerationProgress(queue)
        GenerationControls(queue,job)
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)
            .clipToBounds(),contentAlignment=Alignment.Center) {
            if(frame!=null) {
                Image(remember(frame){frame.asImageBitmap()},"生成中のプレビュー画像",Modifier.fillMaxSize()
                    .semantics {stateDescription="プレビュー更新 "+queue.livePreviewFrameId},contentScale=ContentScale.Fit)
            } else {
                Text(hint.ifBlank { "プレビューの受信待ち" },Modifier.padding(8.dp),
                    style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun confine(offset:Offset,frame:IntSize,image:Bitmap,zoom:Float):Offset {
    if(frame.width==0 || frame.height==0)return Offset.Zero
    val fit=minOf(frame.width.toFloat()/image.width,frame.height.toFloat()/image.height)
    val rangeX=((image.width*fit*zoom-frame.width)/2f).coerceAtLeast(0f)
    val rangeY=((image.height*fit*zoom-frame.height)/2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-rangeX,rangeX),offset.y.coerceIn(-rangeY,rangeY))
}

@Composable
internal fun ZoomableResult(bitmap:Bitmap,modifier:Modifier,description:String="生成結果画像",
                            showFullscreen:Boolean=false,onImageTap:(()->Unit)?=null){
    val tap by rememberUpdatedState(onImageTap)
    var zoom by remember {mutableFloatStateOf(1f)}
    var shift by remember {mutableStateOf(Offset.Zero)}
    var frame by remember {mutableStateOf(IntSize.Zero)}
    fun transform(multiplier:Float,drag:Offset,pivot:Offset){
        val scale=(zoom*multiplier).coerceIn(1f,8f)
        val ratio=scale/zoom
        val localPivot=pivot-Offset(frame.width/2f,frame.height/2f)
        shift=confine((shift-localPivot)*ratio+localPivot+drag,frame,bitmap,scale)
        zoom=scale
    }
    Column(modifier){
        Box(Modifier.fillMaxWidth().weight(1f).background(if(onImageTap!=null) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceVariant).clipToBounds()
            .onSizeChanged { size->frame=size;shift=confine(shift,size,bitmap,zoom) }
            .pointerInput(bitmap){detectTransformGestures {center,pan,scale,_->transform(scale,pan,center)}}
            .pointerInput(bitmap){detectTapGestures(onTap={tap?.invoke()},onDoubleTap={at->
                if(zoom>1.01f){zoom=1f;shift=Offset.Zero}else transform(2f,Offset.Zero,at)
            })},contentAlignment=Alignment.Center){
            Image(bitmap.asImageBitmap(),description,Modifier.fillMaxSize().graphicsLayer {
                scaleX=zoom;scaleY=zoom;translationX=shift.x;translationY=shift.y
            },contentScale=ContentScale.Fit)
            if(showFullscreen) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp),
                shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface.copy(alpha=.88f)) {
                FullscreenAction(bitmap,description)
            }
        }
        if(onImageTap==null) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly){
            TextButton(enabled=zoom>1f,onClick={transform(1f/1.5f,Offset.Zero,Offset(frame.width/2f,frame.height/2f))},
                modifier=Modifier.semantics{contentDescription="縮小"}){Text("−")}
            TextButton(onClick={zoom=1f;shift=Offset.Zero}){Text("全体表示")}
            Text((zoom*100).roundToInt().toString()+"%",style=MaterialTheme.typography.labelSmall)
            TextButton(enabled=zoom<8f,onClick={transform(1.5f,Offset.Zero,Offset(frame.width/2f,frame.height/2f))},
                modifier=Modifier.semantics{contentDescription="拡大"}){Text("＋")}
        }
    }
}
