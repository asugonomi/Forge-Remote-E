package jp.local.forgedesk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal val pairToken=Regex("[A-Za-z0-9_-]{43}")
internal const val PAIR_LINK="forgeremote://pair#"
private val hexPin=Regex("[0-9a-f]{64}")

internal fun gatewayOrigin(value:String):String {
    val uri=URI(value)
    val host=uri.host?.removeSurrounding("[","]") ?: error("接続先アドレスを読み取れません")
    require(uri.scheme=="https" && uri.userInfo==null && uri.rawPath.orEmpty().isEmpty() &&
        uri.query==null && uri.fragment==null && uri.port in 1..65535) {"PCのHTTPS接続先が不正です"}
    val local=if(":" in host && '%' !in host && host.matches(Regex("[0-9a-fA-F:]+"))) {
        val ip=InetAddress.getByName(host)
        ip is Inet6Address && (ip.address[0].toInt() and 0xfe)==0xfc
    } else {
        val octets=host.split(".").map { it.toIntOrNull()?.takeIf { n->n in 0..255 && n.toString()==it } ?: -1 }
        octets.size==4 && -1 !in octets && (octets[0]==10 || octets[0]==192 && octets[1]==168 ||
            octets[0]==172 && octets[1] in 16..31 || octets[0]==100 && octets[1] in 64..127)
    }
    require(local) {"LANまたはTailscaleのIPアドレスを含むペアリングコードが必要です"}
    return uri.toASCIIString()
}

internal class PairedPc(val id:String,val name:String,val pin:String,val endpoints:List<String>) {
    val origin:String get()="paired:"+id
    fun json():JSONObject=JSONObject().put("v",1).put("server_id",id).put("name",name)
        .put("pin",pin).put("endpoints",JSONArray(endpoints))

    companion object {
        fun parse(data:JSONObject):PairedPc {
            require(data.getInt("v")==1)
            val id=data.getString("server_id")
            require(UUID.fromString(id).toString()==id)
            val pin=data.getString("pin")
            require(hexPin.matches(pin))
            val raw=data.getJSONArray("endpoints")
            require(raw.length() in 1..8)
            val endpoints=List(raw.length()){gatewayOrigin(raw.getString(it))}.distinct()
            val name=data.optString("name").filterNot(Char::isISOControl).take(64).ifBlank {"登録したPC"}
            return PairedPc(id,name,pin,endpoints)
        }
    }
}

internal class SetupCode(val pc:PairedPc,val secret:String) {
    companion object {
        fun parse(raw:String):SetupCode {
            try {
                val code=raw.trim().removePrefix(PAIR_LINK)
                require(code.length in 6..8192 && code.startsWith("FER1."))
                val data=JSONObject(String(Base64.getUrlDecoder().decode(code.drop(5)),Charsets.UTF_8))
                val secret=data.getString("secret")
                require(pairToken.matches(secret))
                return SetupCode(PairedPc.parse(data),secret)
            } catch(e:Exception) { throw IllegalArgumentException("PCの新しいペアリングコードを貼り付けてください") }
        }
    }
}

internal class SavedPair(val pc:PairedPc,val token:String)

internal class PairVault(ctx:Context) {
    private val prefs=ctx.getSharedPreferences("forge_pairing_v1",Context.MODE_PRIVATE)
    private val alias="forge-remote-e-device-token-v1"
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    fun prepare(){key()}
    fun save(pair:SavedPair) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val data=pair.pc.json().put("token",pair.token).toString().toByteArray(Charsets.UTF_8)
        val buf=cipher.iv+cipher.doFinal(data)
        check(prefs.edit().putString("sealed",Base64.getEncoder().encodeToString(buf)).commit()){"接続情報を保存できません"}
    }
    fun load():SavedPair? {
        val sealed=prefs.getString("sealed",null) ?: return null
        val buf=Base64.getDecoder().decode(sealed)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,buf.copyOfRange(0,12)))
        val obj=JSONObject(String(cipher.doFinal(buf,12,buf.size-12),Charsets.UTF_8))
        return SavedPair(PairedPc.parse(obj),obj.getString("token").also {require(pairToken.matches(it))})
    }
    fun forget(){check(prefs.edit().remove("sealed").commit())}
}

internal class LeafPin(private val hex:String):X509TrustManager {
    private val digest=ByteArray(32){ i->hex.substring(i*2,i*2+2).toInt(16).toByte() }
    fun accepts(cert:X509Certificate):Boolean=MessageDigest.isEqual(digest,MessageDigest.getInstance("SHA-256").digest(cert.encoded))
    override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?) {
        val cert=chain?.firstOrNull() ?: throw CertificateException("PC certificate missing")
        cert.checkValidity()
        if(!accepts(cert)) throw CertificateException("PC certificate does not match the pairing code")
    }
    override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?):Unit=
        throw CertificateException("Client certificates unsupported")
    override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
    fun socketFactory()=SSLContext.getInstance("TLS").apply {
        init(null,arrayOf(this@LeafPin),null)
    }.socketFactory
}
