package jp.local.forgedesk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.cert.X509Certificate

private fun tailnet(origin:String):Boolean {
    val host=URI(origin).host.removeSurrounding("[","]").lowercase()
    val octets=host.split(".")
    return host.startsWith("fd7a:115c:a1e0:") ||
        (octets.size==4 && octets[0]=="100" && octets[1].toIntOrNull() in 64..127)
}
internal fun routeCandidates(origins:List<String>,wifi:Boolean):List<String> =
    origins.distinct().sortedBy { if(tailnet(it)==wifi) 1 else 0 }

internal fun pairedApiPath(path:String):Boolean=runCatching{
    val uri=URI(path)
    val route=uri.path
    !uri.isAbsolute && uri.rawAuthority==null && uri.fragment==null && route.startsWith("/") &&
        '\\' !in route && route.split('/').none{it==".." || it=="."} &&
        (path.startsWith("/forge-remote/v1/") || path.startsWith("/tacapi/v1/lora-info/") ||
         route in setOf("/adetailer/v1/schema","/adetailer/v1/ad_model","/internal/progress",
             "/sdapi/v1/txt2img","/sdapi/v1/img2img","/sdapi/v1/options","/sdapi/v1/png-info",
             "/sdapi/v1/loras","/sdapi/v1/samplers","/sdapi/v1/schedulers","/sdapi/v1/scripts",
             "/sdapi/v1/script-info","/sdapi/v1/sd-modules"))
}.getOrDefault(false)

internal class GatewayTls(pin:String) {
    private val verifier=LeafPin(pin)
    private val factory=verifier.socketFactory()
    fun open(origin:String,path:String,token:String?):HttpsURLConnection {
        val url=URL(origin+path)
        return (url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
            sslSocketFactory=factory
            hostnameVerifier=javax.net.ssl.HostnameVerifier { name,session ->
                // The pairing fingerprint identifies this PC on either link, including a new private IP.
                name.removeSurrounding("[","]").equals(url.host.removeSurrounding("[","]"),true) && runCatching {
                    verifier.accepts(session.peerCertificates.first() as X509Certificate)
                }.getOrDefault(false)
            }
            instanceFollowRedirects=false
            useCaches=false
            connectTimeout=1600
            readTimeout=2300
            if(token!=null) setRequestProperty("Authorization","Bearer "+token)
        }
    }
}

private fun tinyJson(c:HttpsURLConnection):JSONObject {
    val bytes=ByteArrayOutputStream()
    c.inputStream.use { s->
        val buf=ByteArray(512)
        while(true){
            val n=s.read(buf)
            if(n<0)break
            if(bytes.size()+n>8192) throw IOException("PCの応答が不正です")
            bytes.write(buf,0,n)
        }
    }
    return JSONObject(bytes.toString("UTF-8"))
}

/** Keep the bearer and the one-use setup secret out of error messages, URLs, and logs. */
internal class PairedGateway(private val ctx:Context,private val saved:SavedPair) {
    val pc=saved.pc
    val status=MutableStateFlow("登録済み · 接続を確認中")
    private val tls=GatewayTls(pc.pin)
    private val lock=Mutex()
    @Volatile private var chosen:String?=null
    private var lastNetwork=""
    private var verifiedAt=0L
    private var tokenRejected=false
    private var failedAt:Long?=null
    private var foundAt=Long.MIN_VALUE
    private var discovered=emptyList<String>()
    private val cm=ctx.getSystemService(ConnectivityManager::class.java)

    fun failed(origin:String) { if(chosen==origin) {chosen=null;status.value="PCに再接続中"} }

    suspend fun open(path:String,fresh:Boolean):HttpsURLConnection {
        require(pairedApiPath(path)) {
            "このAPIはペアリング接続で使用できません"
        }
        val origin=route(fresh)
        return tls.open(origin,path,saved.token).apply { connectTimeout=8000 }
    }
    private fun healthy(origin:String):Boolean {
        val c=tls.open(origin,"/health",saved.token)
        return try {
            val status=c.responseCode
            if(status==401 || status==403)tokenRejected=true
            status==200 && tinyJson(c).let{it.optInt("version")==1 && it.optString("server_id")==pc.id}
        } catch(e:Exception){false} finally{c.disconnect()}
    }
    @Suppress("DEPRECATION")
    private suspend fun route(fresh:Boolean):String=lock.withLock {
        val active=cm?.activeNetwork
        val wifiNet=cm?.allNetworks?.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }
        val wifi=wifiNet!=null
        val network="${active?.networkHandle}/${wifiNet?.networkHandle}"
        val now=SystemClock.elapsedRealtime()
        if(network!=lastNetwork) {chosen=null;discovered=emptyList();failedAt=null;foundAt=Long.MIN_VALUE;lastNetwork=network}
        val lastFailure=failedAt
        if(lastFailure!=null && now-lastFailure<2000)throw IOException("登録したPCがオフラインです")
        val previous=chosen
        if(!fresh && previous!=null && now-verifiedAt<20_000) return@withLock previous
        val candidates=routeCandidates(discovered+pc.endpoints,wifi)
        tokenRejected=false
        val (primary,secondary)=candidates.partition{tailnet(it)!=wifi}
        var selected=primary.firstOrNull(::healthy)
        if(selected==null && wifi && (foundAt==Long.MIN_VALUE || now-foundAt>30_000)) {
            foundAt=now
            discovered=discoverForgeOrigins(ctx,pc.id).mapNotNull{runCatching{gatewayOrigin(it)}.getOrNull()}
            selected=discovered.filterNot { it in primary }.firstOrNull(::healthy)
        }
        if(selected==null) selected=secondary.firstOrNull(::healthy)
        if(selected==null) {
            failedAt=SystemClock.elapsedRealtime()
            chosen=null
            if(tokenRejected){
                status.value="PCが端末の登録を確認できません · 再ペアリングが必要"
                throw IOException("PCで新しいコードを発行して、この端末をもう一度登録してください")
            }
            status.value="未接続 · Wi-Fi または Tailscale とPCを確認"
            throw IOException("登録したPCに接続できません。Wi-Fi または Tailscale とPCの起動状態を確認してください")
        }
        chosen=selected;verifiedAt=SystemClock.elapsedRealtime();failedAt=null
        status.value=if(tailnet(selected)) "接続済み · Tailscale" else "接続済み · LAN"
        selected
    }
    companion object {
        suspend fun redeem(ctx:Context,code:SetupCode):SavedPair=withContext(Dispatchers.IO) {
            val tls=GatewayTls(code.pc.pin)
            val cm=ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val wifi=cm?.allNetworks?.any{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}==true
            fun pinned(origin:String):Boolean {
                val c=tls.open(origin,"/health",null)
                return try {c.responseCode in setOf(401,403)} catch(e:Exception){false} finally{c.disconnect()}
            }
            val known=routeCandidates(code.pc.endpoints,wifi)
            val origin=known.firstOrNull(::pinned) ?: if(wifi) {
                discoverForgeOrigins(ctx,code.pc.id).mapNotNull{runCatching{gatewayOrigin(it)}.getOrNull()}.firstOrNull(::pinned)
            } else null
            if(origin==null) throw IOException("指定された証明書のPCが見つかりません。Wi-Fi または Tailscale を確認してください")
            // Exactly one redemption. A lost response needs a new code issued by the PC.
            val c=tls.open(origin,"/pair",null)
            try {
                val body=JSONObject().put("secret",code.secret).put("device_name",Build.MODEL.take(64)).toString().toByteArray()
                c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use{it.write(body)}
                if(c.responseCode!=200)throw IOException("ペアリングできません。PCで新しいコードを発行してください")
                val reply=tinyJson(c)
                val token=reply.getString("token")
                require(reply.getInt("version")==1 && reply.getString("server_id")==code.pc.id && pairToken.matches(token)) {
                    "ペアリングの応答を確認できません"
                }
                SavedPair(code.pc,token)
            } catch(e:CancellationException){throw e}
            finally{c.disconnect()}
        }
    }
}
