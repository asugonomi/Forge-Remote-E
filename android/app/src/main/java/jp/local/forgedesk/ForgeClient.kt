package jp.local.forgedesk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream

const val LOCAL_FORGE = "http://127.0.0.1:17870"

class ForgeClient(val origin:String, private val token:String="") {
    init {
        val uri=URI(origin)
        require(origin==LOCAL_FORGE || (uri.scheme=="https" && uri.host!=null && uri.userInfo==null &&
            uri.query==null && uri.fragment==null && uri.path.isNullOrEmpty())) { "HTTPS のホスト名を入力してください" }
    }
    suspend fun get(path:String):ByteArray = request(path,null)
    suspend fun post(path:String, body:JSONObject):JSONObject =
        withContext(Dispatchers.Default) { JSONObject(request(path,body).toString(Charsets.UTF_8).ifBlank { "{}" }) }

    private fun connection(path:String):HttpURLConnection {
        require(path.startsWith("/") && !path.startsWith("//"))
        val bridge=path.startsWith("/forge-remote/")
        val base=if(bridge && origin==LOCAL_FORGE) "http://127.0.0.1:17872" else origin
        return (URL(base+path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=false
            connectTimeout=8000
            if (bridge && origin!=LOCAL_FORGE && token.isNotEmpty()) setRequestProperty("Authorization","Bearer "+token)
        }
    }

    private fun HttpURLConnection.readBytesLimited(limit:Int):ByteArray = inputStream.use { input ->
        val out=ByteArrayOutputStream()
        val buffer=ByteArray(16384)
        while(true) {
            val n=input.read(buffer)
            if(n<0) break
            require(out.size()+n<=limit) { "応答画像が大きすぎます" }
            out.write(buffer,0,n)
        }
        out.toByteArray()
    }

    internal val loraThumbnails by lazy { LoraThumbnails(this) }
    internal suspend fun thumbnailBytes(id:String):ByteArray? = withContext(Dispatchers.IO) {
        require(loraThumbnailId.matches(id))
        val c=connection("/forge-remote/v1/loras/$id/thumb")
        c.readTimeout=8000
        suspendCancellableCoroutine { result ->
            result.invokeOnCancellation { c.disconnect() }
            if(!result.isActive) return@suspendCancellableCoroutine
            try {
                val bytes=when(val status=c.responseCode) {
                    200 -> c.readBytesLimited(512*1024)
                    404 -> null
                    else -> error("プレビューの取得に失敗しました（HTTP $status）")
                }
                result.resume(bytes)
            } catch(e:Exception) {
                result.resumeWithException(e)
            } finally {
                c.disconnect()
            }
        }
    }

    private suspend fun request(path:String,body:JSONObject?):ByteArray=withContext(Dispatchers.IO) {
        val progressRequest=path=="/internal/progress"
        val maxResponseBytes=if(progressRequest) 8*1024*1024 else 96*1024*1024
        val c=connection(path)
        try {
            c.readTimeout=when {
                progressRequest -> 8000
                path.endsWith("2img") -> 1200000
                else -> 45000
            }
            if(body!=null) {
                c.requestMethod="POST"
                c.doOutput=true
                c.setRequestProperty("Content-Type","application/json")
                val bytes=body.toString().toByteArray()
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val status=c.responseCode
            if(status !in 200..299) {
                val detail=c.errorStream?.use { stream ->
                    val buf=ByteArray(1200); val n=stream.read(buf)
                    if(n>0) String(buf,0,n,Charsets.UTF_8).take(360) else ""
                }.orEmpty()
                error("HTTP $status $detail")
            }
            c.readBytesLimited(maxResponseBytes)
        } finally { c.disconnect() }
    }
}
