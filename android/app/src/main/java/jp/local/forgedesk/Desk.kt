package jp.local.forgedesk

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

private val Graphite=Color(0xff121519)
private val Teal=Color(0xff91e4cd)
private val palette=darkColorScheme(primary=Teal,onPrimary=Color(0xff00382e),secondary=Color(0xffb5c4dd),
    background=Graphite,surface=Graphite,surfaceContainer=Color(0xff20252b),surfaceVariant=Color(0xff2b333a))
private val menus=listOf("gen" to "生成","prompt" to "プロンプト","gallery" to "画像","queue" to "キュー")
class Desk:ComponentActivity() {
    private val model by lazy {ViewModelProvider(this)[ForgeModel::class.java]}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val vm=model
        acceptPairLink(intent)
        val topology=WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
        setContent {
            val layout by topology.collectAsState(null)
            val hinge=layout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
            val systemDensity=LocalDensity.current
            val compactDensity=remember(systemDensity) {
                // Reduce control dimensions without shrinking the user's chosen text size.
                Density(systemDensity.density * 0.90f, systemDensity.fontScale / 0.90f)
            }
            CompositionLocalProvider(LocalDensity provides compactDensity) {
                MaterialTheme(colorScheme=palette) { Home(vm,hinge) }
            }
        }
    }
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        acceptPairLink(intent)
    }
    private fun acceptPairLink(incoming:Intent?) {
        val data=incoming?.data ?: return
        if(incoming.action==Intent.ACTION_VIEW && data.scheme=="forgeremote" && data.host=="pair"){
            model.readSetup(data.toString())
            incoming.data=null
            setIntent(Intent(this,Desk::class.java))
        }
    }
    override fun onStop(){
        model.persistDraft(true)
        super.onStop()
    }
}
@Composable private fun Home(vm:ForgeModel,fold:FoldingFeature?) {
    BackHandler(vm.tab!="gen"){vm.tab="gen"}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=680.dp
        Scaffold(containerColor=Graphite,
            topBar={ if(wide) Navigation(vm,true) },
            bottomBar={ if(!wide) Navigation(vm,false) }
        ) { inset ->
            Box(Modifier.fillMaxSize().padding(inset)) { Workspace(vm,fold,wide) }
        }
    }
}

private val moreMenus=listOf("extensions" to "拡張","settings" to "接続")

@Composable private fun Navigation(vm:ForgeModel, wide:Boolean){
    var more by remember { mutableStateOf(false) }
    Surface(color=Color(0xff1b2025)){
        Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
            val visible=if(wide) menus+moreMenus else menus
            visible.forEach { (key,label)->
                val active=vm.tab==key
                TextButton(onClick={vm.tab=key},modifier=Modifier.weight(1f).semantics{contentDescription=label+"画面"},
                    colors=ButtonDefaults.textButtonColors(contentColor=if(active) Teal else Color.LightGray,
                        containerColor=if(active) Color(0xff263b37) else Color.Transparent),
                    contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp)){Text(label,maxLines=1,style=MaterialTheme.typography.labelMedium)}
            }
            if(!wide) Box(Modifier.weight(1f)){
                TextButton(onClick={more=true},modifier=Modifier.fillMaxWidth().semantics{contentDescription="その他の画面"},contentPadding=PaddingValues(0.dp)){
                    Text(moreMenus.firstOrNull{it.first==vm.tab}?.second ?: "その他",maxLines=1,style=MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded=more,onDismissRequest={more=false}){
                    moreMenus.forEach { (key,label)->DropdownMenuItem(text={Text(label)},onClick={more=false;vm.tab=key}) }
                }
            }
        }
    }
}

@Composable private fun Workspace(vm:ForgeModel,fold:FoldingFeature?,wide:Boolean){
    val suggestions=remember { TagSuggestions() }
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {origin=it.positionInWindow()}) {
        val density=LocalDensity.current
        val horizontal=fold?.orientation==FoldingFeature.Orientation.HORIZONTAL && fold.isSeparating
        val vertical=fold?.orientation==FoldingFeature.Orientation.VERTICAL
        val bounds=fold?.bounds
        if(horizontal && bounds!=null){
            val top=with(density){(bounds.top-origin.y).toDp()}
            val gap=with(density){bounds.height().toDp()}.coerceAtLeast(2.dp)
            if(top>=90.dp && maxHeight-top-gap>=90.dp){
                Column(Modifier.fillMaxSize()){
                    Box(Modifier.fillMaxWidth().height(top)){CompositionLocalProvider(LocalPreviewTags provides suggestions){Destination(vm,vm.tab,true)}}
                    Spacer(Modifier.fillMaxWidth().height(gap).background(Color.Black))
                    Box(Modifier.fillMaxWidth().weight(1f)){PreviewWithTags(vm,suggestions)}
                }
                return@BoxWithConstraints
            }
        }
        val gap=if(vertical && bounds!=null)with(density){bounds.width().toDp()}.coerceAtLeast(2.dp) else 2.dp
        val left=if(vertical && bounds!=null)with(density){(bounds.left-origin.x).toDp()} else (maxWidth-gap)*0.48f
        if((vertical || wide) && left>=180.dp && maxWidth-left-gap>=180.dp){
            Row(Modifier.fillMaxSize()){
                Box(Modifier.fillMaxHeight().width(left)){CompositionLocalProvider(LocalPreviewTags provides suggestions){Destination(vm,vm.tab,true)}}
                Spacer(Modifier.fillMaxHeight().width(gap).background(Color(0xff374047)))
                Box(Modifier.fillMaxHeight().weight(1f)){PreviewWithTags(vm,suggestions)}
            }
        }else Column(Modifier.fillMaxSize()){
            Box(Modifier.fillMaxWidth().weight(1f)){
                CompositionLocalProvider(LocalPreviewTags provides suggestions){Destination(vm,vm.tab)}
            }
            if(suggestions.tags.isNotEmpty()){
                TagStrip(suggestions,Modifier.fillMaxWidth().height((this@BoxWithConstraints.maxHeight*.45f).coerceAtMost(350.dp)))
            }
        }
    }
}

@Composable private fun Destination(vm:ForgeModel,key:String,dualPane:Boolean=false){
    key(key) {
        when(key){
            "gen" -> Generate(vm,dualPane)
            "prompt" -> PromptScreen(vm)
            "gallery" -> GalleryScreen(vm)
            "queue" -> QueueScreen(vm,dualPane)
            "extensions" -> ExtensionsScreen(vm)
            else -> Settings(vm)
        }
    }
}
@Composable private fun Block(label:String,content:@Composable ColumnScope.()->Unit){
    Surface(shape=RoundedCornerShape(14.dp),color=Color(0xff20252b),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(label,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            content()
        }
    }
}
@Composable private fun Page(title:String,content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        content()
    }
}
@Composable private fun Choice(label:String,value:String,choices:List<String>,pick:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    Box{
        OutlinedButton(onClick={open=true}){Text("$label  $value ▾",maxLines=1)}
        DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=350.dp)){
            choices.forEach{ s->DropdownMenuItem(text={Text(s)},onClick={pick(s);open=false}) }
        }
    }
}
@Composable private fun Generate(vm:ForgeModel,dualPane:Boolean){
    val scope=rememberCoroutineScope(); val ctx=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){ uri->
        if(uri!=null) scope.launch { runCatching{readPicture(ctx,uri)}.onSuccess(vm::useImage).onFailure{vm.notify(it.message?:"画像を開けません")} }
    }
    Page("画像を生成"){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(!vm.imgMode,{vm.mode(false)},label={Text("テキストから")})
            FilterChip(vm.imgMode,{vm.mode(true)},label={Text("画像から")})
        }
        if(vm.imgMode) Block("入力画像"){
            val source=vm.input
            val bmp by produceState<android.graphics.Bitmap?>(null,source){value=source?.let{runCatching{previewBitmap(it,512)}.getOrNull()}}
            bmp?.let {
                Image(it.asImageBitmap(),"入力画像",Modifier.fillMaxWidth().height(144.dp),contentScale=ContentScale.Fit)
                FullscreenAction(it,"入力画像",source)
            }
            OutlinedButton(onClick={picker.launch(arrayOf("image/*"))}){Text(if(source==null)"画像を選択" else "画像を変更")}
            Text("変化の強さ  "+String.format("%.2f",vm.strength))
            CompactSlider(vm.strength,{vm.strength=it},valueRange=0f..1f)
        }
        Text(vm.profileStatus,style=MaterialTheme.typography.labelMedium,color=Teal)
        Block("プロンプト"){
            var draft by remember {mutableStateOf(TextFieldValue(vm.prompt,TextRange(vm.prompt.length)))}
            LaunchedEffect(vm.prompt) {
                if(draft.text!=vm.prompt) draft=TextFieldValue(vm.prompt,TextRange(vm.prompt.length))
            }
            TagField(vm,draft,{draft=it;vm.prompt=it.text},"描きたい内容",Modifier.fillMaxWidth(),minLines=2)
            TextButton(onClick={vm.tab="prompt"}){Text("タグ・強弱・LoRAを編集")}
            if(vm.negative.isNotBlank()) Text("ネガティブ  "+vm.negative,style=MaterialTheme.typography.bodySmall,maxLines=2)
        }
        Button(onClick={vm.enqueue(dualPane)},enabled=!vm.sending && vm.profileStatus!=PROFILE_PENDING,modifier=Modifier.fillMaxWidth().heightIn(min=40.dp)){Text(if(vm.sending)"登録中…" else "生成キューに追加")}
        Block("生成設定"){
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedTextField(vm.width,{vm.width=it},Modifier.weight(1f),label={Text("幅 px")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(vm.height,{vm.height=it},Modifier.weight(1f),label={Text("高さ px")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            SizePresets(vm)
            Text("ステップ数  ${vm.steps}"); CompactSlider(vm.steps.toFloat(),{vm.steps=it.toInt()},valueRange=1f..150f)
            Text("CFG  "+String.format("%.1f",vm.cfg)); CompactSlider(vm.cfg,{vm.cfg=(it*10).toInt()/10f},valueRange=0f..30f)
            Choice("サンプラー",vm.sampler,vm.samplers){vm.sampler=it}
            Choice("スケジュール",vm.scheduler,vm.schedules){vm.scheduler=it}
            OutlinedTextField(vm.seed,{vm.seed=it},label={Text("Seed · ランダムは -1")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedTextField(vm.batch,{vm.batch=it},Modifier.weight(1f),label={Text("バッチサイズ")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(vm.iterations,{vm.iterations=it},Modifier.weight(1f),label={Text("バッチ回数")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            Text("Shift / Distilled CFG  "+String.format("%.1f",vm.distilled))
            CompactSlider(vm.distilled,{vm.distilled=(it*10).toInt()/10f},valueRange=0f..30f)
            if(!vm.imgMode){
                Row(verticalAlignment=Alignment.CenterVertically){Switch(vm.highRes,{vm.highRes=it});Text("  Hires. fix")}
                if(vm.highRes){
                    Text("拡大倍率  "+String.format("%.2f",vm.hrScale));CompactSlider(vm.hrScale,{vm.hrScale=(it*100).toInt()/100f},valueRange=1f..4f)
                    OutlinedTextField(vm.hrUpscaler,{vm.hrUpscaler=it},label={Text("アップスケーラー")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                    Text("Hires ステップ  "+vm.hrSteps);CompactSlider(vm.hrSteps.toFloat(),{vm.hrSteps=it.toInt()},valueRange=0f..150f)
                    Text("Hires CFG  "+vm.hrCfg);CompactSlider(vm.hrCfg,{vm.hrCfg=(it*10).toInt()/10f},valueRange=0f..30f)
                    Text("変化の強さ  "+String.format("%.2f",vm.strength));CompactSlider(vm.strength,{vm.strength=it},valueRange=0f..1f)
                }
            }
            if(vm.info.isNotEmpty()) OutlinedButton(onClick={vm.info=""}){Text("PNG Infoの追加設定を解除")}
        }
        var advanced by remember{mutableStateOf(false)}
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"拡張設定を閉じる" else "その他の拡張設定")}
        if(advanced) Block("Always-on Scripts"){
            Text("Forge API形式の拡張名と args を入力します。")
            OutlinedTextField(vm.extraJson,{vm.extraJson=it},label={Text("拡張設定 JSON")},modifier=Modifier.fillMaxWidth())
            Text(vm.scripts,style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun Settings(vm:ForgeModel){
    var host by remember(vm.client){mutableStateOf(vm.client.origin.takeIf{it.startsWith("https://")}.orEmpty())}
    var token by remember{mutableStateOf("")}
    Page("接続設定"){
        Text("Forge Remote E（Everyone）1.0",style=MaterialTheme.typography.titleMedium)
        Block("Wi-Fi・TailscaleでPCに接続"){PairSettings(vm)}
        Block("生成の初期値と下書き"){
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(!vm.imgMode,{vm.mode(false)},label={Text("テキストから")})
                FilterChip(vm.imgMode,{vm.mode(true)},label={Text("画像から")})
            }
            Text(if(vm.hasDeviceDefaults)"初期値 · この端末に登録済み" else "初期値 · PCの生成設定")
            Text("正負プロンプトと生成条件の下書きを自動保存し、次回は最後の編集内容から再開します。",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={vm.saveDeviceDefaults()}){Text("現在の内容を端末の初期値に保存")}
            var action by remember(vm.client,vm.imgMode){mutableStateOf("")}
            OutlinedButton(enabled=vm.hasDeviceDefaults,onClick={action="device"}){Text("端末の初期値を適用")}
            OutlinedButton(onClick={action="pc"}){Text("PCの初期値に戻す")}
            if(action.isNotBlank())AlertDialog(onDismissRequest={action=""},title={Text(if(action=="device")"端末の初期値を適用しますか？" else "PCの初期値に戻しますか？")},
                text={Text(if(action=="device")"このモードの編集中の内容を、端末に登録した初期値へ戻します。"
                    else "この接続先とモードの下書き・端末初期値を解除し、PCの生成設定を読み込みます。")},
                confirmButton={TextButton(onClick={if(action=="device")vm.applyDeviceDefaults() else vm.loadProfile(true);action=""}){Text("適用")}},
                dismissButton={TextButton(onClick={action=""}){Text("キャンセル")}})
        }
        Block("接続状態・その他の接続方法"){
            Text(vm.online); Text(vm.bridge);Text(vm.profileStatus)
            if(vm.dictionaryStatus.isNotBlank())Text(vm.dictionaryStatus,style=MaterialTheme.typography.bodySmall)
            if(vm.checkpoint.isNotBlank()) Text(vm.checkpoint,style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={vm.connect(LOCAL_FORGE,"")}){Text("ADB転送でPCに接続")}
            OutlinedTextField(host,{host=it},label={Text("HTTPS サーバー")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(token,{token=it},label={Text("補助APIトークン（任意）")},singleLine=true,modifier=Modifier.fillMaxWidth(),visualTransformation=PasswordVisualTransformation())
            Button(onClick={vm.connect(host,token)}){Text("接続して確認")}
            Text("手動のHTTPS接続で入力した補助APIトークンはアプリ終了まで保持します。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
