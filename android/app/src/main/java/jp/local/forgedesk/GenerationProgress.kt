package jp.local.forgedesk

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun GenerationProgress(queue:ForgeQueue) {
    if(!queue.hasActiveJob) return
    if(queue.serverStatus!="生成中") {
        IndeterminateProgress(queue.serverStatus.ifBlank { "PCへ送信中" })
        return
    }
    val fraction=queue.progress.coerceIn(0f,1f)
    val eta=queue.etaSeconds?.takeIf {it>0}?.let {seconds->
        if(seconds>=60) "　残り約 ${seconds/60}分${seconds%60}秒" else "　残り約 ${seconds}秒"
    }.orEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical=2.dp)){
        Text("生成中  "+(fraction*100).roundToInt()+"%"+eta,style=MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth().height(4.dp)
            .semantics {contentDescription="生成画像の進捗"})
    }
}

@Composable
internal fun IndeterminateProgress(label:String){
    Column(Modifier.fillMaxWidth().padding(vertical=2.dp)){
        Text(label,style=MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(modifier=Modifier.fillMaxWidth().height(4.dp)
            .semantics {contentDescription=label})
    }
}

private fun ForgeQueue.accepts(snapshot:ForgeControlTicket):Boolean {
    val latest=controlTicket ?: return false
    return !controlPending && latest.id==snapshot.id && latest.epoch==snapshot.epoch && latest.unit==snapshot.unit &&
        jobs.any { it.id==snapshot.id && it.state=="active" }
}

@Composable
internal fun GenerationControls(queue:ForgeQueue,job:ForgeJob) {
    val ticket=queue.controlTicket?.takeIf {it.id==job.id}
    val canSend=ticket!=null && queue.accepts(ticket)
    val batch=if(queue.livePreviewJobId==job.id) queue.activeBatchSize else 0
    val iterations=if(queue.livePreviewJobId==job.id) queue.activeIterations else 0
    var stopTarget by remember(job.id) { mutableStateOf<ForgeControlTicket?>(null) }

    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick={
            if(ticket!=null && queue.accepts(ticket)) queue.skipObservedJob(ticket)
        },enabled=canSend,contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp),
            modifier=Modifier.weight(1f).heightIn(min=40.dp).semantics{contentDescription="現在の画像・バッチをスキップ"}) {
            Text(if(batch>1)"バッチをスキップ" else "画像をスキップ",style=MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(onClick={
            if(ticket!=null && queue.accepts(ticket)) stopTarget=ticket
        },enabled=canSend,contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp),
            modifier=Modifier.weight(1f).heightIn(min=40.dp).semantics {contentDescription="実行中ジョブを中断"}) {
            Text("ジョブを中断",style=MaterialTheme.typography.labelLarge)
        }
    }
    val detail=when {
        job.controlAck=="interrupt" -> "PCが中断要求を受け付けました。"
        job.controlAck=="skip" && queue.controlPending -> "PCがスキップ要求を受け付けました。"
        queue.controlAvailabilityText.isNotBlank() -> queue.controlAvailabilityText
        queue.controlPending -> "操作を送信中…"
        batch>1 -> "スキップ対象は同時処理中の ${batch} 枚です。"
        batch==1 && iterations==1 -> "1枚の生成では、スキップ後に次の画像はありません。"
        else -> ""
    }
    if(detail.isNotEmpty()) Text(detail,style=MaterialTheme.typography.labelSmall)
    stopTarget?.let {captured->
        val current=queue.accepts(captured)
        AlertDialog(onDismissRequest={stopTarget=null},title={Text("このジョブを中断しますか？")},
            text={Text(if(current) "現在のジョブの残りの生成を中断します。ほかの待機中のジョブはキューに残ります。"
                       else "PCの処理状況が変わりました。現在のプレビューで操作対象を確認してください。")},
            confirmButton={TextButton(onClick={
                stopTarget=null
                if(queue.accepts(captured))queue.interruptObservedJob(captured)
            },enabled=current) {Text("残りを中断")}},
            dismissButton={TextButton(onClick={stopTarget=null}){Text("続ける")}})
    }
}
