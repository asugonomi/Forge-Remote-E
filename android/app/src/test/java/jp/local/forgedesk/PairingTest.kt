package jp.local.forgedesk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class PairingTest {
    private val id="9076dc1d-6cac-4316-85ec-df480bf40f08"
    private val lan="https://192.168.1.40:17873"
    private val tail="https://100.65.1.2:17873"
    private fun payload()=JSONObject().put("v",1).put("server_id",id).put("name","Test PC")
        .put("pin","ab".repeat(32)).put("secret","t".repeat(43))
        .put("endpoints",JSONArray(listOf(lan,tail)))
    private fun code(json:JSONObject)="FER1."+Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())

    @Test fun linkAndPastedCodeUseSameStablePcIdentity() {
        val raw=code(payload())
        val link=SetupCode.parse(PAIR_LINK+raw)
        val plain=SetupCode.parse("  $raw  ")
        assertEquals(plain.pc.id,link.pc.id)
        assertEquals("paired:$id",link.pc.origin)
        assertEquals(listOf(lan,tail),link.pc.endpoints)
        assertEquals("Test PC",link.pc.name)
        val changed=payload().put("endpoints",JSONArray(listOf(tail)))
        assertEquals(link.pc.origin,SetupCode.parse(code(changed)).pc.origin)
        assertFalse(link.pc.json().has("secret"))
        assertFalse(link.pc.json().has("token"))
    }
    @Test fun rejectsMalformedPairingWithoutEchoingItsSecret() {
        for(data in listOf(payload().put("pin","oops"),payload().put("secret","oops"),payload().put("server_id","oops"),
                payload().put("v",2),payload().put("endpoints",JSONArray()),payload().put("endpoints",JSONArray(listOf("http://10.1.2.3:17873"))))) {
            val e=runCatching{SetupCode.parse(code(data))}.exceptionOrNull()
            assertTrue(e is IllegalArgumentException)
            assertFalse(e?.message.orEmpty().contains("t".repeat(43)))
        }
    }
    @Test fun pinAuthorizationIsBoundToAnApprovedPrivateOrigin() {
        for(origin in listOf("https://127.0.0.1:17873","https://169.254.169.254:17873","https://8.8.8.8:17873",
            "https://bad.example:443","https://login@192.168.1.1:17873","$lan/next","$lan?host=x","$tail#secret",
            "http://192.168.1.2:17873","https://[::1]:17873","https://010.1.2.3:443","https://100.128.0.1:443")) {
            assertTrue(origin,runCatching{gatewayOrigin(origin)}.isFailure)
        }
        for(origin in listOf(lan,tail,"https://172.16.2.3:1234","https://[fd7a:115c:a1e0::1]:17873")){
            assertEquals(origin,gatewayOrigin(origin))
        }
        assertEquals(listOf(lan,tail),routeCandidates(listOf(tail,lan),true))
        assertEquals(listOf(tail,lan),routeCandidates(listOf(lan,tail),false))
    }
    @Test fun rejectsEvenAnotherPlatformTrustedCertificateUnlessItIsThePairedLeaf() {
        val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(null as KeyStore?)}
        val leaves=trust.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
            .filter{runCatching{it.checkValidity()}.isSuccess}
        val one=leaves.first()
        val other=leaves.first{!it.encoded.contentEquals(one.encoded)}
        val hex=MessageDigest.getInstance("SHA-256").digest(one.encoded).joinToString(""){"%02x".format(it)}
        val verifier=LeafPin(hex)
        verifier.checkServerTrusted(arrayOf(one),"RSA")
        assertTrue(runCatching{verifier.checkServerTrusted(arrayOf(other,one),"RSA")}.exceptionOrNull() is CertificateException)
        assertTrue(runCatching{verifier.checkServerTrusted(emptyArray(),"RSA")}.exceptionOrNull() is CertificateException)
    }
    @Test fun pairedPathsCoverExtensionAndGalleryButNeverAnotherAuthorityOrForgeAdministration() {
        for(path in listOf("/adetailer/v1/schema","/adetailer/v1/ad_model","/sdapi/v1/sd-modules",
            "/forge-remote/v1/gallery/id/thumb","/tacapi/v1/lora-info/name%20with%20space",
            "/forge-remote/v1/tags?q=%E3%81%82&formatted=true","/internal/progress"))assertTrue(path,pairedApiPath(path))
        for(path in listOf("//someone/sdapi/v1/options","http://elsewhere/forge-remote/v1/x",
            "/sdapi/v1/interrupt","/forge-remote/v1/%2e%2e/private","/adetailer/v1/other"))assertFalse(path,pairedApiPath(path))
    }
}
