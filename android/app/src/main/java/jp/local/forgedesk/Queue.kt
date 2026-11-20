package jp.local.forgedesk

import android.app.Application
import android.graphics.Bitmap
import android.util.AtomicFile
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private const val WAITING="waiting"
private const val ACTIVE="active"
private const val DONE="done"
private const val UNKNOWN="unknown"
private const val FAILED="failed"

data class ForgeJob(
    val id:String, val route:String, val origin:String, val label:String, val state:String=WAITING,
    val message:String="", val total:Int=0, val controlAck:String=""
)

/** A persisted active marker precedes every submission. Only waiting jobs may be dispatched. */
class ForgeQueue(private val vm:ForgeModel) {
    val jobs=mutableStateListOf<ForgeJob>()
    var paused by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var serverStatus by mutableStateOf("")
        private set
    var livePreview by mutableStateOf<Bitmap?>(null)
        private set
    var livePreviewJobId by mutableStateOf<String?>(null)
        private set
    var livePreviewFrameId by mutableIntStateOf(-1)
        private set
    var previewStatus by mutableStateOf("")
        private set
    var activeBatchSize by mutableIntStateOf(0)
        private set
    var activeIterations by mutableIntStateOf(0)
        private set
    var etaSeconds by mutableStateOf<Int?>(null)
        private set
    val hasActiveJob:Boolean get()=jobs.any { it.state==ACTIVE }
    private var running=false
    private val disk=File(vm.getApplication<Application>().filesDir,"generation-queue")
    private val ledger=AtomicFile(File(disk,"manifest.json"))
    private val ready=CompletableDeferred<Unit>()
    private val submissions=Mutex()
    private val scope get()=vm.viewModelScope

    init {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { check(disk.isDirectory || disk.mkdirs()) }
                val saved=withContext(Dispatchers.IO) {
                    try { ledger.openRead().use { JSONObject(it.bufferedReader().readText()) } }
                    catch(_:java.io.FileNotFoundException) { JSONObject() }
                }
                val items=saved.optJSONArray("jobs") ?: JSONArray()
                paused=saved.optBoolean("paused")
                for (i in 0 until items.length()) {
                    val j=items.getJSONObject(i)
                    var job=ForgeJob(j.getString("id"),j.getString("route"),j.getString("origin"),j.getString("label"),
                        j.getString("state"),j.optString("message"),j.optInt("total"),j.optString("controlAck"))
                    if(job.state==ACTIVE) {
                        val result=withContext(Dispatchers.IO) { readReceipt(job.id) }
                        if(result!=null) job=finishedJob(job,result.getInt("count"))
                        else {
                            paused=true
                            job=job.copy(state=UNKNOWN,message="アプリが終了したため受理状況を確認できません。PCの履歴を確認してください。")
                        }
                    }
                    jobs.add(job)
                }
                save();ready.complete(Unit);start()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){
                paused=true;ready.completeExceptionally(e);vm.notify("キューの保存状態を読み込めません。 "+e.localizedMessage.orEmpty())
            }
        }
    }

    private fun folder(id:String)=File(disk,id)
    private fun readReceipt(id:String):JSONObject? {
        val path=AtomicFile(File(folder(id),"result.json"))
        return try { path.openRead().use { JSONObject(it.bufferedReader().readText()) } }
        catch(_:java.io.FileNotFoundException){null}
    }
    private fun write(file:AtomicFile,bytes:ByteArray) {
        val stream=file.startWrite()
        try { stream.write(bytes);file.finishWrite(stream) }
        catch(e:Throwable){file.failWrite(stream);throw e}
    }
    // The manifest only holds short labels and small identifiers. Full requests and images are separate.
    private fun save() {
        val entries=JSONArray()
        jobs.forEach {
            entries.put(JSONObject().put("id",it.id).put("origin",it.origin).put("route",it.route)
                .put("label",it.label).put("state",it.state).put("message",it.message.take(500)).put("total",it.total)
                .put("controlAck",it.controlAck))
        }
        write(ledger,JSONObject().put("paused",paused).put("jobs",entries).toString().toByteArray())
    }
    private fun commit():Boolean {
        return try { save();true } catch(e:Exception){
            paused=true
            vm.notify("キューを保存できないため停止しました。 "+e.localizedMessage.orEmpty())
            false
        }
    }
    fun enqueue(route:String,body:JSONObject) {
        if(route !in setOf("/sdapi/v1/txt2img","/sdapi/v1/img2img")) {vm.notify("この生成経路には対応していません");return}
        val id=UUID.randomUUID().toString()
        val origin=vm.client.origin
        val snapshot=body.toString()
        val job=ForgeJob(id,route,origin,body.optString("prompt").take(80))
        scope.launch {
            try {
                ready.await()
                submissions.withLock {
                    withContext(Dispatchers.IO){
                        check(folder(id).mkdirs())
                        write(AtomicFile(File(folder(id),"request.json")),snapshot.toByteArray())
                    }
                    jobs.add(job)
                    if(!commit()){ jobs.remove(job);return@withLock }
                    vm.notify("生成をキューに追加しました")
                    start()
                }
            } catch(e:CancellationException){throw e} catch(e:Exception){vm.notify("生成条件を保存できません。 "+e.localizedMessage.orEmpty())}
        }
    }
    fun toggle() {
        if(!ready.isCompleted || ready.isCancelled) return
        paused=!paused
        if(commit() && !paused) start()
    }
    fun move(id:String, delta:Int) {
        val at=jobs.indexOfFirst {it.id==id && it.state==WAITING}
        if(at<0)return
        val candidates=jobs.indices.filter {jobs[it].state==WAITING}
        val target=candidates.getOrNull(candidates.indexOf(at)+delta) ?: return
        val other=jobs[target]
        jobs[target]=jobs[at];jobs[at]=other
        commit()
    }
    fun remove(id:String) {
        val job=jobs.firstOrNull {it.id==id && it.state!=ACTIVE} ?: return
        jobs.remove(job)
        if(commit()) scope.launch(Dispatchers.IO){folder(id).deleteRecursively()}
    }

    private val controls=ForgeJobControls(scope, { id, client ->
        vm.client === client && jobs.any { it.id==id && it.origin==client.origin && it.state==ACTIVE }
    }, vm::notify, ::recordControlAcknowledged)
    val controlTicket get()=controls.ticket?.takeIf { ticket ->
        jobs.any { it.id==ticket.id && it.state==ACTIVE && it.origin==vm.client.origin }
    }
    val controlPending get()=controls.pending
    val controlAvailabilityText get()=controls.status
    fun skipObservedJob(ticket:ForgeControlTicket)=controls.send(ticket,"skip",vm.client)
    fun interruptObservedJob(ticket:ForgeControlTicket)=controls.send(ticket,"interrupt",vm.client)
    private fun replace(job:ForgeJob) {
        val pos=jobs.indexOfFirst {it.id==job.id}
        if(pos>=0) jobs[pos]=job
    }

    /** An accepted control is not itself evidence that generation ended. Only a return receipt proves that. */
    fun recordControlAcknowledged(id:String,action:String) {
        if(action!="interrupt" && action!="skip")return
        val job=jobs.firstOrNull { it.id==id } ?: return
        val ack=if(job.controlAck=="interrupt")"interrupt" else action
        if(ack==job.controlAck)return
        val updated=job.copy(controlAck=ack)
        // A delayed HTTP acknowledgment can arrive after the generation POST and even after the next job starts.
        val terminal=job.state in setOf(DONE,"empty","skipped","interrupted","done_with_skip")
        replace(if(terminal)finishedJob(updated,updated.total) else updated)
        commit()
    }

    private fun finishedJob(job:ForgeJob,count:Int):ForgeJob {
        val state=when {
            job.controlAck=="interrupt" -> "interrupted"
            job.controlAck=="skip" -> if(count==0)"skipped" else "done_with_skip"
            count==0 -> "empty"
            else -> DONE
        }
        return job.copy(state=state,total=count,message=if(state=="empty")"PCから結果画像が返りませんでした。" else "")
    }
    private fun start() {
        if(running || paused || !ready.isCompleted || ready.isCancelled)return
        running=true
        scope.launch {
            try {
                while(!paused){
                    val job=jobs.firstOrNull {it.state==WAITING} ?: break
                    val client=vm.client
                    if(client.origin!=job.origin) {
                        paused=true;commit()
                        vm.notify("キューを停止しました。登録時の接続先 "+job.origin+" に戻してください。")
                        break
                    }
                    val request=try {
                        withContext(Dispatchers.IO) {
                            JSONObject(AtomicFile(File(folder(job.id),"request.json")).openRead().bufferedReader().use {it.readText()})
                                .put("force_task_id",forgeRemoteTaskId(job.id))
                        }
                    } catch(e:CancellationException){throw e} catch(e:Exception){
                        replace(job.copy(state=FAILED,message="保存済み生成条件を開けません"));commit();continue
                    }
                    if(job.route=="/sdapi/v1/txt2img") {
                        try {
                            prepareForgeHighResModules(request,client)
                        } catch(e:CancellationException){throw e} catch(e:Exception){
                            if(jobs.any { it.id==job.id && it.state==WAITING }) {
                                replace(job.copy(state=FAILED,message="生成条件を確認してください。"+e.localizedMessage.orEmpty().take(220)))
                                if(!commit())break
                            }
                            continue
                        }
                    }
                    // A reorder, pause, removal or origin change may have occurred while loading the payload.
                    if(paused || jobs.firstOrNull {it.state==WAITING}?.id!=job.id || vm.client.origin!=job.origin)continue
                    replace(job.copy(state=ACTIVE))
                    if(!commit())break
                    livePreviewJobId=job.id
                    livePreview=null;livePreviewFrameId=-1;etaSeconds=null
                    activeBatchSize=request.optInt("batch_size",1)
                    activeIterations=request.optInt("n_iter",1)
                    previewStatus="PCの受理を待っています"
                    progress=0f;serverStatus="PCへ送信中"
                    val controlPoller=launch { controls.watch(job.id,client) }
                    val poller=launch {
                        try {pollGeneration(job,client)}
                        catch(e:CancellationException){throw e}
                        catch(_:Exception){serverStatus="進捗を取得できません";previewStatus="途中画像を取得できません"}
                    }
                    try {
                        val reply=client.post(job.route,request)
                        poller.cancel()
                        controlPoller.cancel()
                        serverStatus="結果を保存中";progress=1f;etaSeconds=null
                        val count=withContext(Dispatchers.IO){storeResult(job.id,reply)}
                        val current=jobs.first { it.id==job.id }
                        replace(finishedJob(current,count))
                    } catch(e:CancellationException){throw e} catch(e:Exception){
                        paused=true
                        val current=jobs.first { it.id==job.id }
                        val ack=if(current.controlAck.isNotEmpty())"操作は受理されましたが、終了結果を確認できません。" else "PCの結果を確認してください。"
                        replace(current.copy(state=UNKNOWN,message=ack+"自動再送は停止中です。 "+e.localizedMessage.orEmpty().take(240)))
                    } finally {
                        poller.cancel()
                        controlPoller.cancel()
                        livePreviewJobId=null;livePreview=null;livePreviewFrameId=-1
                        activeBatchSize=0;activeIterations=0;etaSeconds=null;previewStatus=""
                    }
                    if(!commit())break
                }
            } finally {running=false}
        }
    }
    private suspend fun pollGeneration(job:ForgeJob,client:ForgeClient):Unit=coroutineScope {
        var prefs=PreviewPreferences(null,null,1200)
        val fetchPrefs=launch {
            try {prefs=readPreviewPreferences(client)}
            catch(e:CancellationException){throw e}
            catch(_:Exception){/* Progress still works if the options endpoint is unavailable. */}
        }
        var nextDelay=450L
        var seen=-1
        var capped=false
        var frameError=""
        var finished=false
        val task=forgeRemoteTaskId(job.id)
        try {
            while(isActive && !finished && livePreviewJobId==job.id){
                delay(nextDelay)
                val visible=previewAppVisible()
                val include=visible && prefs.enabled!=false && !capped
                try {
                    val result=client.post("/internal/progress",JSONObject().put("id_task",task)
                        .put("id_live_preview",seen).put("live_preview",include))
                    ensureActive()
                    if(livePreviewJobId!=job.id)return@coroutineScope
                    val active=result.optBoolean("active")
                    val queued=result.optBoolean("queued")
                    finished=result.optBoolean("completed") && !active
                    etaSeconds=if(active)result.optDouble("eta").takeIf{it.isFinite() && it>=0}?.toInt() else null
                    if(active) {
                        result.optDouble("progress").takeIf{it.isFinite()}?.let{progress=it.toFloat().coerceIn(0f,1f)}
                    } else if(finished)progress=1f
                    serverStatus=when {active->"生成中";finished->"結果を受信中";queued->"PCで待機中";else->"PCの受理を確認中"}
                    previewStatus=when {
                        finished->"完成画像を受信中"
                        !active->if(queued)"生成の順番待ち" else "PCの受理を待っています"
                        capped->"途中画像が大きいため進捗のみ表示"
                        prefs.enabled==false->"PCの途中画像表示はオフです"
                        frameError.isNotBlank()->frameError
                        livePreview!=null->"途中画像"
                        prefs.steps==-1->"PCの設定により画像ごとに更新します"
                        else->"最初の途中画像を待っています"
                    }
                    val frame=result.optInt("id_live_preview",-1)
                    if(active && include && frame>=0 && frame!=seen && !result.isNull("live_preview")){
                        seen=frame
                        try {
                            val decoded=decodePreviewFrame(result.getString("live_preview"))
                            ensureActive()
                            if(livePreviewJobId==job.id){
                                frameError=""
                                livePreview=decoded;livePreviewFrameId=frame;previewStatus="途中画像"
                            }
                        }catch(e:CancellationException){throw e}
                        catch(e:Exception){
                            if(e.message?.contains("大きすぎ")==true)capped=true
                            frameError=e.localizedMessage ?: "途中画像を読み取れません"
                            previewStatus=frameError
                        }
                    }
                    nextDelay=if(visible)prefs.interval else 3500L
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    if(include && e.message?.contains("大きすぎ")==true){
                        capped=true;nextDelay=350
                        previewStatus="途中画像が大きいため進捗のみ表示"
                    }else {
                        serverStatus="進捗を再取得中";etaSeconds=null
                        nextDelay=(nextDelay*2).coerceIn(1500L,6000L)
                    }
                }
            }
        } finally {fetchPrefs.cancel()}
    }

    private fun storeResult(id:String,reply:JSONObject):Int {
        val images=reply.optJSONArray("images") ?: error("応答に画像がありません")
        for(i in 0 until images.length()){
            val bytes=Base64.decode(images.getString(i).substringAfter(','),Base64.DEFAULT)
            write(AtomicFile(File(folder(id),"image-"+i)),bytes)
        }
        val receipt=JSONObject().put("count",images.length()).put("info",reply.optString("info"))
        write(AtomicFile(File(folder(id),"result.json")),receipt.toString().toByteArray())
        return images.length()
    }
    suspend fun image(id:String,i:Int):ByteArray=withContext(Dispatchers.IO){File(folder(id),"image-"+i).readBytes()}
    suspend fun info(id:String,i:Int):String=withContext(Dispatchers.IO){
        val raw=readReceipt(id)?.optString("info").orEmpty()
        runCatching {JSONObject(raw).optJSONArray("infotexts")?.optString(i).orEmpty()}.getOrDefault("")
    }
}

@Composable
fun QueueScreen(vm:ForgeModel,dualPane:Boolean=false) {
    val q=vm.queue
    var inspect by remember {mutableStateOf<ForgeJob?>(null)}
    var deletion by remember {mutableStateOf<ForgeJob?>(null)}
    val active=q.jobs.firstOrNull {it.state==ACTIVE}
    var viewedActive by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(active?.id){if(active!=null)viewedActive=active.id}
    val finishedViewed=viewedActive?.let {id->q.jobs.any {it.id==id && it.state!=ACTIVE && it.state!=WAITING}}==true
    val ordered=q.jobs.filter {it.state==ACTIVE}+q.jobs.filter {it.state==WAITING}+q.jobs.filter {it.state!=WAITING && it.state!=ACTIVE}.asReversed()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick={q.toggle()}){Text(if(q.paused)"キュー再開" else "次の送信を停止")}
            Text("待機 "+q.jobs.count {it.state==WAITING}+"件",Modifier.padding(top=12.dp))
        }
        if(ordered.isEmpty())Text("キューに生成はありません")
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val previewHeight=(maxHeight*.61f).coerceIn(224.dp,400.dp)
            LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)){
                if(!dualPane && (active!=null || finishedViewed)) item("result") {
                    Box(Modifier.fillMaxWidth().height(previewHeight)){ResultsPane(vm)}
                }
                items(ordered,key={it.id}){job ->
                    OutlinedCard(Modifier.fillMaxWidth()){
                        Column(Modifier.padding(10.dp)){
                            val state=if(job.state==ACTIVE)q.serverStatus else generationStateLabel(job.state)
                            Text(job.route.substringAfterLast('/')+" · "+state,style=MaterialTheme.typography.titleSmall)
                            Text(job.label.ifBlank {"（プロンプトなし）"},maxLines=2)
                            if(job.message.isNotBlank())Text(job.message,style=MaterialTheme.typography.bodySmall)
                            if(job.state==WAITING) Row {
                                Text("順序",Modifier.pointerInput(job.id){
                                    var distance=0f
                                    detectDragGesturesAfterLongPress(onDragStart={distance=0f},onDrag={change,drag->
                                        change.consume();distance+=drag.y
                                        val step=44.dp.toPx()
                                        if(kotlin.math.abs(distance)>=step){q.move(job.id,if(distance>0)1 else -1);distance=0f}
                                    })
                                }.padding(12.dp))
                                TextButton(onClick={q.move(job.id,-1)}){Text("上へ")}
                                TextButton(onClick={q.move(job.id,1)}){Text("下へ")}
                                TextButton(onClick={q.remove(job.id)}){Text("取り消す")}
                            } else if(job.state!=ACTIVE) Row {
                                if(job.total>0) TextButton(onClick={inspect=job}){Text("結果を見る ("+job.total+")")}
                                TextButton(onClick={deletion=job}){Text("履歴を削除")}
                            }
                        }
                    }
                }
            }
        }
    }
    deletion?.let {job ->
        AlertDialog(onDismissRequest={deletion=null},title={Text("この端末の履歴を削除しますか？")},
            text={Text("この生成の端末内の設定・結果画像を削除します。")},
            confirmButton={TextButton(onClick={q.remove(job.id);deletion=null}){Text("削除")}},
            dismissButton={TextButton(onClick={deletion=null}){Text("戻る")}})
    }
    inspect?.let {job->
        var index by remember(job.id){mutableIntStateOf(0)}
        var selected by remember(job.id,index){mutableStateOf<ByteArray?>(null)}
        var bmp by remember(job.id,index){mutableStateOf<Bitmap?>(null)}
        var text by remember(job.id,index){mutableStateOf("")}
        LaunchedEffect(job.id,index){
            try {val raw=q.image(job.id,index);bmp=previewBitmap(raw);selected=raw;text=q.info(job.id,index)}
            catch(e:CancellationException){throw e} catch(e:Exception){vm.notify("結果を開けません。 "+e.localizedMessage.orEmpty())}
        }
        AlertDialog(onDismissRequest={inspect=null},title={Text("生成結果 "+(index+1)+"/"+job.total)},
            text={Column{
                bmp?.let {
                    Image(it.asImageBitmap(),"生成結果",Modifier.fillMaxWidth().heightIn(max=240.dp))
                    FullscreenAction(it,"生成結果画像",selected)
                }
                Row{
                    TextButton(onClick={index--},enabled=index>0){Text("前へ")}
                    TextButton(onClick={index++},enabled=index+1<job.total){Text("次へ")}
                }
                TextButton(onClick={selected?.let{inspect=null;vm.useImage(it)}},enabled=selected!=null){Text("img2imgに使う")}
                TextButton(onClick={inspect=null;vm.restore(text)},enabled=text.isNotBlank()){Text("生成設定を復元")}
            }},confirmButton={TextButton(onClick={inspect=null}){Text("閉じる")}})
    }
}
