package jp.local.forgedesk

import android.app.Application
import android.util.Base64
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

internal const val PROFILE_PENDING="PCの生成設定を取得中"

class ForgeModel(app:Application): AndroidViewModel(app) {
    private val prefs=app.getSharedPreferences("native",0)
    private val vault=PairVault(app)
    private var savedPair by mutableStateOf(runCatching {vault.load()}.getOrNull())
    internal val rememberedPc:PairedPc? get()=savedPair?.pc
    internal var pendingCode by mutableStateOf<SetupCode?>(null)
        private set
    var pairingBusy by mutableStateOf(false)
        private set
    var pairingError by mutableStateOf("")
        private set
    private fun initialClient():ForgeClient {
        val origin=prefs.getString("origin",LOCAL_FORGE) ?: LOCAL_FORGE
        if(origin.startsWith("paired:")){
            return savedPair?.takeIf{it.pc.origin==origin}?.let{ForgeClient(PairedGateway(getApplication(),it))}
                ?: ForgeClient.unavailable(origin)
        }
        return runCatching{ForgeClient(origin)}.getOrElse{ForgeClient(LOCAL_FORGE)}
    }
    var client by mutableStateOf(initialClient())
        private set
    var tab by mutableStateOf("gen")
    var prompt by mutableStateOf("")
    var negative by mutableStateOf("")
    var steps by mutableIntStateOf(20)
    var width by mutableStateOf("1024")
    var height by mutableStateOf("1024")
    var cfg by mutableFloatStateOf(6f)
    var seed by mutableStateOf("-1")
    var sampler by mutableStateOf("DPM++ 2M")
    var scheduler by mutableStateOf("Automatic")
    var strength by mutableFloatStateOf(.65f)
    val adDetailer = AdDetailerState()
    var imgMode by mutableStateOf(false)
    var input by mutableStateOf<ByteArray?>(null)
    var samplers by mutableStateOf(listOf("DPM++ 2M"))
    var schedules by mutableStateOf(listOf("Automatic"))
    var scripts by mutableStateOf("")
    var extraJson by mutableStateOf("")
    var info by mutableStateOf("")
    var online by mutableStateOf("接続を確認中")
    var bridge by mutableStateOf("補助APIを確認中")
    var dictionaryStatus by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
    val extensionSettings=ExtensionSettings()
    var profileStatus by mutableStateOf(PROFILE_PENDING)
    var checkpoint by mutableStateOf("")
    var batch by mutableStateOf("1")
    var iterations by mutableStateOf("1")
    var distilled by mutableFloatStateOf(3f)
    var highRes by mutableStateOf(false)
    var hrScale by mutableFloatStateOf(1.25f)
    var hrUpscaler by mutableStateOf("Latent")
    var hrSteps by mutableIntStateOf(12)
    var hrCfg by mutableFloatStateOf(4f)
    val aspectRatio=ArMode()
    private val draftPrefs=app.getSharedPreferences("generation-drafts",0)
    private val store=GenerationDrafts({key->draftPrefs.getString(key,null)}, {key,value,sync->
        val update=draftPrefs.edit().putString(key,value)
        if(sync)check(update.commit()){"生成設定を端末に保存できません"} else update.apply()
    })
    private val builtIn by lazy { snapshot() }
    private var profiles:JSONObject?=null
    private var lastCaptured=""
    private var entryForm=JSONObject()
    private var localPriority=false
    private var profileRevision=0
    var hasDeviceDefaults by mutableStateOf(false)
        private set
    private var extraProfile=JSONObject()
    val queue=ForgeQueue(this)
    init {
        builtIn
        openEndpoint()
        viewModelScope.launch {snapshotFlow { snapshot().toString() }.collect { persistDraft() }}
        refresh()
    }
    fun notify(text:String) { Toast.makeText(getApplication(),text,Toast.LENGTH_LONG).show() }
    fun connect(host:String,token:String) {
        runCatching { ForgeClient(host.trim().trimEnd('/'),token) }.onSuccess(::activate)
            .onFailure { notify(it.message ?: "接続先を確認してください") }
    }
    private fun activate(next:ForgeClient) {
            if(client.origin!=next.origin) {
                persistDraft(true)
                extensionSettings.clear(); adDetailer.clear(); input=null
                client=next
                openEndpoint()
            } else {
                client=next
            }
            prefs.edit().putString("origin",next.origin).apply(); refresh()
    }
    fun readSetup(code:String) {
        if(pairingBusy)return
        tab="settings";pairingError=""
        pendingCode=runCatching{SetupCode.parse(code)}.getOrElse {
            pairingError="PCの新しいペアリングコードを貼り付けてください";null
        }
    }
    fun cancelSetup() {if(!pairingBusy){pendingCode=null;pairingError=""}}
    fun pair() {
        val setup=pendingCode ?: return
        if(pairingBusy)return
        pairingBusy=true;pairingError=""
        viewModelScope.launch {
            try {
                val saved=withContext(Dispatchers.IO) {
                    vault.prepare()
                    PairedGateway.redeem(getApplication(),setup).also { vault.save(it) }
                }
                savedPair=saved;pendingCode=null
                activate(ForgeClient(PairedGateway(getApplication(),saved)))
                notify("PCを登録しました")
            } catch(e:CancellationException){throw e}
            catch(e:Exception){
                pairingError=when(e){
                    is java.security.GeneralSecurityException->"端末に接続情報を安全に保存できません。PCのコードを再発行してください"
                    is java.io.IOException->e.localizedMessage.orEmpty().take(140)
                    else->"PCを登録できません。PCで新しいコードを発行してください"
                }
            } finally {pairingBusy=false}
        }
    }
    fun useRememberedPc(){savedPair?.let{activate(ForgeClient(PairedGateway(getApplication(),it)))}}
    fun forgetPc(){
        runCatching{vault.forget()}.onFailure{notify("登録を解除できません");return}
        savedPair=null;pendingCode=null;pairingError=""
        if(client.origin.startsWith("paired:"))activate(ForgeClient(LOCAL_FORGE))
    }
    fun refresh() {
        val remote=client
        loadProfile(false)
        restoreSchemas()
        online="接続を確認中"; bridge="補助APIを確認中";dictionaryStatus=""
        viewModelScope.launch {
            runCatching { JSONArray(remote.get("/sdapi/v1/samplers").toString(Charsets.UTF_8)) }
                .onSuccess { if(client===remote) { samplers=names(it,"name"); online="ForgeNeo 接続済み" } }
                .onFailure { if(client===remote) online="未接続 · 接続設定を確認" }
            runCatching { JSONArray(remote.get("/sdapi/v1/schedulers").toString(Charsets.UTF_8)) }
                .onSuccess { if(client===remote) schedules=names(it,"label") }
        }
        viewModelScope.launch {
            val capabilities=try {JSONObject(remote.get("/forge-remote/v1/capabilities").toString(Charsets.UTF_8))}
                catch(e:CancellationException){throw e}
                catch(_:Exception){null}
            if(client===remote) {
                bridge=if(capabilities?.optBoolean("gallery")==true)"補助API 接続済み" else "補助API 未接続"
                dictionaryStatus=when {
                    capabilities?.has("tags")!=true -> ""
                    !capabilities.optBoolean("tags") -> "PCのタグ辞書は無効または未設定です"
                    else -> capabilities.optString("tag_dictionary").ifBlank {"有効"}.let {"PCのタグ辞書 · $it"}
                }
            }
        }
        viewModelScope.launch {
            runCatching { JSONObject(remote.get("/sdapi/v1/scripts").toString(Charsets.UTF_8)) }.onSuccess {
                if(client===remote) scripts=listOf("txt2img","img2img").joinToString("\n\n"){ k -> k+"\n"+(it.optJSONArray(k)?.let { a-> (0 until a.length()).joinToString("、"){i->a.optString(i)} } ?: "") }
            }
        }
    }
    private fun names(arr:JSONArray,key:String)= (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { obj->obj.optString(key).ifBlank { obj.optString("name") } } }.filter { it.isNotBlank() }
    private fun snapshot()=JSONObject().put("prompt",prompt).put("negative_prompt",negative).put("width",width)
        .put("height",height).put("steps",steps).put("cfg_scale",cfg.toDouble()).put("seed",seed)
        .put("sampler_name",sampler).put("scheduler",scheduler).put("denoising_strength",strength.toDouble())
        .put("distilled_cfg_scale",distilled.toDouble()).put("batch_size",batch).put("n_iter",iterations)
        .put("hr_scale",hrScale.toDouble()).put("hr_upscaler",hrUpscaler).put("enable_hr",highRes).put("hr_second_pass_steps",hrSteps).put("hr_cfg",hrCfg.toDouble())
        .put("infotext",info)
        .put("extraJson",extraJson)
        .put("aspectRatio",JSONObject().put("locked",aspectRatio.locked).put("reversed",aspectRatio.reversed)
            .put("oneDimension",aspectRatio.oneDimension).put("average",aspectRatio.average).put("precision",aspectRatio.precision))
        .put("ad",adDetailer.saved(imgMode)).put("extensions",extensionSettings.saved(imgMode))
    private fun applyProfile(p:JSONObject) {
        prompt=p.optString("prompt");negative=p.optString("negative_prompt")
        width=p.getStringValue("width");height=p.getStringValue("height");steps=p.getInt("steps")
        cfg=p.getDouble("cfg_scale").toFloat();seed=p.getStringValue("seed");sampler=p.getString("sampler_name")
        scheduler=p.getString("scheduler");strength=p.optDouble("denoising_strength",.45).toFloat()
        distilled=p.optDouble("distilled_cfg_scale",3.0).toFloat();batch=p.getStringValue("batch_size")
        iterations=p.getStringValue("n_iter");highRes=p.optBoolean("enable_hr")
        hrScale=p.optDouble("hr_scale",1.25).toFloat();hrUpscaler=p.optString("hr_upscaler","Latent")
        hrSteps=p.optInt("hr_second_pass_steps",12);hrCfg=p.optDouble("hr_cfg",4.0).toFloat()
        info=p.optString("infotext")
        extraJson=p.optString("extraJson")
        val ar=p.optJSONObject("aspectRatio")
        aspectRatio.locked=ar?.optBoolean("locked")==true
        aspectRatio.reversed=ar?.optBoolean("reversed")==true
        aspectRatio.oneDimension=ar?.optBoolean("oneDimension")==true
        aspectRatio.average=ar?.optInt("average") ?: 0
        aspectRatio.precision=ar?.optString("precision","64") ?: "64"
        adDetailer.restore(imgMode,p.optJSONObject("ad"))
        extensionSettings.restore(imgMode,p.optJSONObject("extensions"))
    }
    private fun JSONObject.getStringValue(key:String)=get(key).toString()
    private fun modeKey(image:Boolean)=if(image) "img2img" else "txt2img"
    private fun openEndpoint() {
        store.open(client.origin)
        profiles=store.pcProfiles
        checkpoint=profiles?.optString("checkpoint").orEmpty().substringAfterLast('\\').substringAfterLast('/')
        imgMode=store.lastImageMode
        selectStoredForm()
    }
    private fun present(form:JSONObject) {
        val full=JSONObject(builtIn.toString())
        form.keys().forEach { full.put(it,form.get(it)) }
        applyProfile(full)
        entryForm=snapshot()
        lastCaptured=entryForm.toString()
    }
    private fun selectStoredForm(){
        extraProfile=profiles?.optJSONObject(modeKey(imgMode)) ?: JSONObject()
        val stored=store.selected(imgMode)
        localPriority=stored!=null
        hasDeviceDefaults=store.defaults(imgMode)!=null
        present(stored ?: extraProfile)
        profileStatus=when {
            store.draft(imgMode)!=null -> "前回の編集内容を復元しました"
            hasDeviceDefaults -> "端末の初期値を適用しました"
            profiles!=null -> "前回取得したPC設定"
            else -> PROFILE_PENDING
        }
    }
    fun persistDraft(sync:Boolean=false) {
        val current=snapshot()
        val text=current.toString()
        if(text!=lastCaptured) {
            store.setDraft(imgMode,current)
            lastCaptured=text
        }
        store.persist(sync)
    }
    fun saveDeviceDefaults(){
        val current=snapshot()
        store.setDefaults(imgMode,current);store.setDraft(imgMode,current)
        store.persist(true)
        hasDeviceDefaults=true;localPriority=true;lastCaptured=current.toString()
        notify("このモードの端末初期値を保存しました")
    }
    fun applyDeviceDefaults(){
        val form=store.defaults(imgMode) ?: return
        store.setDraft(imgMode,null)
        present(form);localPriority=true;store.persist(true)
        profileStatus="端末の初期値を適用しました"
        restoreSchemas()
    }
    private fun resetPc(image:Boolean) {
        store.setDefaults(image,null);store.setDraft(image,null)
        if(imgMode==image){hasDeviceDefaults=false;localPriority=false}
    }
    private fun restoreSchemas(){
        val remote=client
        if(adDetailer.requiresSchema(imgMode)) viewModelScope.launch {
            try {adDetailer.load(remote)} catch(e:CancellationException){throw e} catch(_:Exception){}
        }
        if(extensionSettings.requiresSchema(imgMode)) viewModelScope.launch {
            try {extensionSettings.refresh(remote)} catch(e:CancellationException){throw e} catch(_:Exception){}
        }
    }
    fun loadProfile(reset:Boolean) {
        val remote=client
        val version=++profileRevision
        val requestedMode=imgMode
        val cached=profiles?.optJSONObject(modeKey(requestedMode))
        val savedDefault=store.defaults(requestedMode)
        if(reset && cached!=null){
            resetPc(requestedMode);present(cached);store.persist(true)
            profileStatus="前回取得したPC設定"
        }
        if(!localPriority && profiles==null)profileStatus=PROFILE_PENDING
        viewModelScope.launch {
            try {
                val data=JSONObject(remote.get("/forge-remote/v1/generation-defaults").toString(Charsets.UTF_8))
                if(client!==remote || version!=profileRevision)return@launch
                profiles=data; checkpoint=data.optString("checkpoint").substringAfterLast('\\').substringAfterLast('/')
                store.pcProfiles=data
                val base=data.getJSONObject(modeKey(imgMode))
                extraProfile=base
                if(reset && cached==null && store.defaults(requestedMode)===savedDefault)resetPc(requestedMode)
                if(!localPriority){
                    val current=snapshot()
                    val changed=stableJson(current)!=stableJson(entryForm)
                    present(mergeFetchedProfile(base,entryForm,current))
                    if(changed){store.setDraft(imgMode,snapshot());localPriority=true}
                    profileStatus=listOf("PC設定を読込済み",data.optString("active")).filter(String::isNotBlank).joinToString(" · ")
                }
                store.persist()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(client===remote && version==profileRevision){
                if(reset && cached==null)notify("PCの初期値を取得できません。接続を確認してください")
                if(!localPriority)profileStatus=if(cached!=null)"前回取得したPC設定（オフライン）" else "PCの初期値を取得できません · 補助APIを確認"
            }}
        }
    }
    fun mode(image:Boolean) {
        if(imgMode==image)return
        persistDraft()
        imgMode=image
        store.lastImageMode=image
        selectStoredForm()
        store.persist()
        restoreSchemas()
    }
    fun useImage(bytes:ByteArray) { mode(true); input=bytes; tab="gen" }
    fun restore(raw:String) {
        val match=Regex("(?m)^Steps:\\s*\\d+").findAll(raw).lastOrNull()
        if(match==null) {notify("Forge形式の生成パラメーターが見つかりません");return}
        val text=raw.substring(0,match.range.first).trimEnd().split(Regex("(?m)^Negative prompt:\\s*"),limit=2)
        prompt=text.first().trimEnd('\r', '\n'); negative=text.getOrNull(1).orEmpty()
        val tail=raw.substring(match.range.first)
        fun field(k:String)=Regex("(?:^|, )"+Regex.escape(k)+": ([^,\\n]+)").find(tail)?.groupValues?.get(1)?.trim()
        field("Steps")?.toIntOrNull()?.let {steps=it.coerceIn(1,150)}
        field("CFG scale")?.toFloatOrNull()?.let {cfg=it}
        field("Seed")?.let {seed=it}
        field("Sampler")?.let {sampler=it}
        field("Schedule type")?.let {scheduler=it}
        Regex("(\\d+)x(\\d+)").matchEntire(field("Size").orEmpty())?.let {width=it.groupValues[1];height=it.groupValues[2]}
        field("Denoising strength")?.toFloatOrNull()?.let {strength=it}
        highRes=field("Hires upscale")!=null || field("Hires upscaler")!=null || field("Hires resize")!=null
        field("Hires upscale")?.toFloatOrNull()?.let {hrScale=it}
        field("Hires upscaler")?.let {hrUpscaler=it}
        field("Hires steps")?.toIntOrNull()?.let {hrSteps=it}
        field("Hires CFG Scale")?.toFloatOrNull()?.let {hrCfg=it}
        (field("Distilled CFG Scale") ?: field("Shift"))?.toFloatOrNull()?.let {distilled=it}
        info=raw; tab="gen"; notify("生成設定を復元しました")
    }
    fun enqueue(keepEditorOpen:Boolean=false) {
        if(sending) return
        val body=try {
            val w=width.toInt(); val h=height.toInt()
            require(w in 64..4096 && h in 64..4096) { "幅・高さは64〜4096で指定してください" }
            val n=seed.toLongOrNull() ?: error("Seedを整数で入力してください")
            val extra=if(extraJson.isBlank()) JSONObject() else JSONObject(extraJson)
            val ad=adScripts(this)
            ad.keys().forEach { key->extra.put(key,ad.get(key)) }
            JSONObject().put("prompt",prompt).put("negative_prompt",negative).put("width",w).put("height",h)
                .put("seed",n).put("steps",steps).put("cfg_scale",cfg.toDouble()).put("sampler_name",sampler)
                .put("scheduler",scheduler).put("batch_size",batch.toInt().also { require(it in 1..8){"バッチサイズは1〜8です"} })
                .put("n_iter",iterations.toInt().also { require(it in 1..100){"バッチ回数は1〜100です"} })
                .put("distilled_cfg_scale",distilled.toDouble()).put("send_images",true)
                .put("save_images",true).put("override_settings_restore_afterwards",true).put("alwayson_scripts",extra)
                 .also {
                    if(info.isNotBlank()) it.put("infotext",info)
                    if(!imgMode) {
                        it.put("enable_hr",highRes)
                        if(highRes) {
                            it.put("hr_second_pass_steps",hrSteps).put("hr_cfg",hrCfg.toDouble()).put("denoising_strength",strength.toDouble())
                            it.put("hr_scale",hrScale.toDouble()).put("hr_upscaler",hrUpscaler)
                            listOf("hr_distilled_cfg").forEach {k->if(extraProfile.has(k))it.put(k,extraProfile.get(k))}
                        }
                    }
                    extensionSettings.applyTo(it,imgMode)
                }
        } catch(e:Exception){ notify(e.message ?: "生成設定を確認してください");return }
        val useImg=imgMode; val image=input
        val capturedClient=client; val queuedProfile=extraProfile
        if(useImg && image==null) {notify("入力画像を選んでください");return}
        val denoise=strength
        sending=true
        viewModelScope.launch {
            try {
                if(useImg) {
                    val b64=withContext(Dispatchers.Default){Base64.encodeToString(image,Base64.NO_WRAP)}
                    body.put("init_images",JSONArray().put(b64)).put("denoising_strength",denoise.toDouble())
                }
                if(!useImg) prepareForgeHighResModules(body,capturedClient,queuedProfile)
                require(client===capturedClient) { "接続先が変更されました。生成条件を確認してください" }
                queue.enqueue("/sdapi/v1/"+(if(useImg)"img2img" else "txt2img"),body)
                if(!keepEditorOpen) tab="queue"
            } catch(e:Exception){notify(e.message ?: "登録に失敗しました")} finally {sending=false}
        }
    }
}
