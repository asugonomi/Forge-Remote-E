package jp.local.forgedesk

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val IMAGE_LIMIT = 24 * 1024 * 1024
private data class Entry(val id: String, val name: String, val date: Long = 0)

private fun InputStream.limited(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(32*1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        require(out.size().toLong() + n <= max) { "画像または生成情報が大きすぎます" }
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

internal suspend fun readPicture(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
    ctx.contentResolver.openInputStream(uri)?.use { it.limited(IMAGE_LIMIT) }
        ?: error("画像を開けませんでした")
}

private fun sample(options: BitmapFactory.Options, edge: Int) {
    require(options.outWidth > 0 && options.outHeight > 0) { "この画像形式を読み取れません" }
    var divisor = 1
    while (maxOf(options.outWidth, options.outHeight).toLong() / divisor > edge) divisor *= 2
    options.inSampleSize = divisor
    options.inJustDecodeBounds = false
}

internal suspend fun previewBitmap(bytes: ByteArray, edge: Int = 1024): Bitmap = withContext(Dispatchers.Default) {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    sample(options, edge)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("画像を表示できません")
}

private suspend fun localThumb(ctx: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
    sample(opt, 256)
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        ?: error("画像を表示できません")
}

/** Forge puts parameters in tEXt or iTXt. zTXt and compressed iTXt are also accepted. */
internal fun parameters(png: ByteArray): String {
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    if (png.size<8 || !signature.indices.all { png[it]==signature[it] }) return ""
    val key = "parameters\u0000".toByteArray(Charsets.US_ASCII)
    var p=8
    while (p<=png.size-12) {
        var size=0L
        for (i in p until p+4) size=(size shl 8)+(png[i].toInt() and 255)
        if (size > png.size-p-12) return ""
        val start=p+8
        val end=start+size.toInt()
        val kind=String(png,p+4,4,Charsets.US_ASCII)
        if (kind in listOf("tEXt","iTXt","zTXt") && size in key.size.toLong()..1048576L &&
            key.indices.all { png[start+it]==key[it] }) {
            var pos=start+key.size
            var compressed=false
            if (kind=="iTXt") {
                if (end-pos<4 || png[pos].toInt() !in 0..1 || png[pos+1].toInt()!=0) return ""
                compressed=png[pos].toInt()==1
                pos+=2
                repeat(2) {
                    while (pos<end && png[pos].toInt()!=0) pos++
                    if (pos>=end) return ""
                    pos++
                }
            } else if (kind=="zTXt") {
                if (pos>=end || png[pos++].toInt()!=0) return ""
                compressed=true
            }
            val charset=if(kind=="iTXt") Charsets.UTF_8 else Charsets.ISO_8859_1
            return if (compressed) {
                runCatching { InflaterInputStream(ByteArrayInputStream(png,pos,end-pos)).use {
                    String(it.limited(1048576),charset)
                } }.getOrDefault("")
            } else String(png,pos,end-pos,charset)
        }
        p=end+4
    }
    return ""
}

private suspend fun album(ctx: Context, tree: Uri): List<Entry> = withContext(Dispatchers.IO) {
    val parent = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
    val fields = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    val rows = mutableListOf<Entry>()
    ctx.contentResolver.query(children,fields,null,null,null)?.use { cursor ->
        while (cursor.moveToNext()) {
            if (!cursor.getString(2).orEmpty().startsWith("image/")) continue
            val uri=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0))
            rows.add(Entry(uri.toString(),cursor.getString(1).orEmpty(),cursor.getLong(3)))
        }
    } ?: error("フォルダーを開けませんでした")
    rows.sortedByDescending { it.date }
}

@Composable
fun GalleryScreen(vm: ForgeModel) {
    var location by rememberSaveable { mutableStateOf("PC") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("PC","端末").forEach {
                FilterChip(selected=location==it,onClick={location=it}, label={Text(it)})
            }
        }
        if (location=="PC") PcOutputGallery(vm) else DeviceGallery(vm)
    }
}

@Composable
private fun DeviceGallery(vm: ForgeModel) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("gallery",Context.MODE_PRIVATE) }
    var folder by rememberSaveable { mutableStateOf(prefs.getString("album",null)) }
    var revision by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<Entry>()) }
    var selected by remember { mutableStateOf<Entry?>(null) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val openFolder= rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri!=null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                folder?.takeIf { it != uri.toString() }?.let { old ->
                    runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                prefs.edit().putString("album",uri.toString()).apply()
                folder=uri.toString(); selected=null; revision++
            } catch(e: Exception) { error=e.localizedMessage.orEmpty() }
        }
    }
    val openFile= rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) selected=Entry(uri.toString(),"端末の画像")
    }
    LaunchedEffect(folder,revision) {
        rows=emptyList(); index=0; error=""; busy=true
        try {
            if(folder!=null) rows=album(ctx,Uri.parse(folder))
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { error=e.localizedMessage.orEmpty() }
        finally { busy=false }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick={openFile.launch(arrayOf("image/*"))}) {Text("画像を開く")}
                OutlinedButton(onClick={openFolder.launch(folder?.let(Uri::parse))}) {Text("フォルダーを選ぶ")}
        }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val list: @Composable (Modifier)->Unit = { mod ->
                Column(mod,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if (rows.isEmpty() && !busy && error.isEmpty()) Text(
                        if(folder==null) "画像を開くか、一覧に表示するフォルダーを選んでください。" else "画像はありません。")
                    if(rows.isNotEmpty()) {
                        val perPage=8
                        val start=index.coerceIn(0,(rows.size-1)/perPage)*perPage
                        Text((start+1).toString()+" ～ "+minOf(start+perPage,rows.size)+" / "+rows.size)
                        rows.drop(start).take(perPage).chunked(2).forEach { pair ->
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                pair.forEach { item -> key(item) { GalleryTile(item,Modifier.weight(1f)){selected=item} } }
                                if(pair.size==1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Row {
                            TextButton(onClick={index--},enabled=start>0){Text("前へ")}
                            TextButton(onClick={index++},enabled=start+perPage<rows.size){Text("次へ")}
                        }
                    }
                }
            }
            val item=selected
            if (maxWidth>=600.dp) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                list(Modifier.weight(1f))
                Box(Modifier.weight(1f)) { if(item!=null) key(item) { Detail(vm,item) } }
            } else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(item!=null) key(item) { Detail(vm,item) }
                list(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun GalleryTile(item:Entry, modifier:Modifier, select:()->Unit) {
    val ctx=LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue=null,item) {
        try {
            value=localThumb(ctx,Uri.parse(item.id))
        } catch(e:CancellationException){throw e} catch(_:Exception){value=null}
    }
    OutlinedCard(modifier.clickable(onClick=select)) {
        bitmap?.let { Image(it.asImageBitmap(),item.name,Modifier.fillMaxWidth().height(96.dp),contentScale=ContentScale.Crop) }
        Text(item.name,Modifier.padding(6.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun Detail(vm:ForgeModel,item:Entry) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var info by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(item) {
        try {
            val raw=readPicture(ctx,Uri.parse(item.id))
            bitmap=previewBitmap(raw); bytes=raw
            info=withContext(Dispatchers.Default){parameters(raw)}
        } catch(e:CancellationException){throw e} catch(e:Exception){error=e.localizedMessage.orEmpty()}
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        bitmap?.let {
            Image(it.asImageBitmap(),item.name,Modifier.fillMaxWidth().heightIn(max=280.dp),contentScale=ContentScale.Fit)
            FullscreenAction(it,"端末の画像",bytes)
        }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        bytes?.let { original ->
            OutlinedButton(onClick={vm.useImage(original)}) {Text("img2imgに使う")}
            if(info.isNotBlank()) {
                Button(onClick={vm.restore(info)}) {Text("生成設定を復元")}
                SelectionContainer {Text(info,style=MaterialTheme.typography.bodySmall)}
            } else OutlinedButton(onClick={scope.launch {
                try {
                    val request=withContext(Dispatchers.Default){JSONObject().put("image",Base64.encodeToString(original,Base64.NO_WRAP))}
                    info=vm.client.post("/sdapi/v1/png-info",request).optString("info")
                    if(info.isBlank()) vm.notify("生成情報はありません")
                } catch(e:CancellationException){throw e} catch(e:Exception){error=e.localizedMessage.orEmpty()}
            }}) {Text("PCでPNG Infoを確認")}
        }
    }
}
