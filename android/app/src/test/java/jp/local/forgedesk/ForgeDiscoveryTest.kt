package jp.local.forgedesk

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeDiscoveryTest {
    private val id="ad8122a0-0c8d-484b-802a-60f6dd6d42e7"
    private fun ip(a:Int,b:Int,c:Int,d:Int)=InetAddress.getByAddress(byteArrayOf(a.toByte(),b.toByte(),c.toByte(),d.toByte()))
    @Test fun requiresFullExpectedIdAndAUsablePort(){
        val local=listOf(ip(192,168,4,10))
        for (other in listOf(null,id.take(12)," "+id,id.uppercase())){
            assertTrue(resolvedForgeOrigins(id,other?.toByteArray(),17873,local).isEmpty())
        }
        assertTrue(resolvedForgeOrigins(id,id.toByteArray(),0,local).isEmpty())
        assertTrue(resolvedForgeOrigins(id,id.toByteArray(),65536,local).isEmpty())
    }
    @Test fun acceptsOnlyDistinctPrivateLanOriginsFromResolvedAddresses(){
        val local=ip(192,168,4,10)
        val addresses=listOf(local,ip(127,0,0,1),ip(169,254,169,254),ip(100,64,1,5),
            ip(239,0,0,1),ip(198,51,100,1),local)
        assertEquals(listOf("https://192.168.4.10:17873"),resolvedForgeOrigins(id,id.toByteArray(),17873,addresses))
    }
}
