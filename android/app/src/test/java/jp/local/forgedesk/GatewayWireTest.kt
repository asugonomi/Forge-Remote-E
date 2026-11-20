package jp.local.forgedesk

import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GatewayWireTest {
    @Test fun aLostGenerationResponseIsNeverReplayedOnAnotherRoute()= runBlocking {
        val count=AtomicInteger()
        val failure=runCatching {
            withRouteRecovery(true,false){count.incrementAndGet();throw IOException("Test response lost")}
        }
        assertTrue(failure.isFailure)
        assertEquals(1,count.get())
        var reads=0
        val recovered=withRouteRecovery(true,true){if(++reads==1)throw IOException("Test network change");"ok"}
        assertEquals("ok",recovered)
        assertEquals(2,reads)
    }

    @Test fun mismatchedCertificatesReceiveNoHttpHeaders() {
        // Generate an ephemeral certificate for this loopback-only test. No signing key is shipped.
        val dir=Files.createTempDirectory("forge-test-pair")
        val store=dir.resolve("fixture.p12")
        val password="unit-test-only"
        val win=System.getProperty("os.name").startsWith("Windows")
        val tool=Paths.get(System.getProperty("java.home"),"bin",if(win)"keytool.exe" else "keytool").toString()
        var https:SSLServerSocket?=null
        try {
            val generation=ProcessBuilder(tool,"-genkeypair","-alias","test","-keyalg","EC","-validity","2",
                "-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password,
                "-dname","CN=Forge Remote E test").redirectErrorStream(true).start()
            check(generation.waitFor(25,TimeUnit.SECONDS) && generation.exitValue()==0){"Fixture certificate creation failed"}
            val ks=KeyStore.getInstance("PKCS12")
            Files.newInputStream(store).use{ks.load(it,password.toCharArray())}
            val cert=ks.getCertificate("test")
            val peerPin=MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(""){"%02x".format(it)}
            val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(ks,password.toCharArray())}
            val ssl=SSLContext.getInstance("TLS").apply{init(km.keyManagers,null,null)}
            val count=AtomicInteger()
            val authorization=AtomicReference<String>()
            val server=ssl.serverSocketFactory.createServerSocket(0,4,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            https=server
            server.soTimeout=6000
            val serving=thread(isDaemon=true,name="forge-local-test-tls"){
                while(!server.isClosed && count.get()==0) {
                    val socket=try{server.accept() as SSLSocket}catch(_:IOException){break}
                    socket.use{peer->
                        try{
                            peer.soTimeout=4000;peer.startHandshake()
                            val reader=peer.inputStream.bufferedReader(Charsets.US_ASCII)
                            if(reader.readLine()!="GET /health HTTP/1.1")return@use
                            var line=reader.readLine()
                            while(!line.isNullOrEmpty()){
                                if(line.startsWith("Authorization:",true))authorization.set(line.substringAfter(':').trim())
                                line=reader.readLine()
                            }
                            count.incrementAndGet()
                            peer.outputStream.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                            peer.outputStream.flush()
                        } catch(_:IOException){/* A rejected handshake must not reach HTTP. */}
                    }
                }
            }
            val local="https://127.0.0.1:"+server.localPort
            fun send(pin:String):Int {
                val c=GatewayTls(pin).open(local,"/health","test-token")
                return try {c.responseCode} finally{c.disconnect()}
            }
            assertTrue(runCatching{send("00".repeat(32))}.isFailure)
            assertEquals(0,count.get())
            assertEquals(204,send(peerPin))
            assertEquals("Bearer test-token",authorization.get())
            assertEquals(1,count.get())
            serving.join(2000)
        } finally {
            https?.close()
            Files.deleteIfExists(store)
            Files.deleteIfExists(dir)
        }
    }
}
