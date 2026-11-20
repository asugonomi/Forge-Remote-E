package jp.local.forgedesk

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val FORGE_SERVICE="_forgeremote._tcp."
private val discoveryLock=Mutex()

/** DNS-SD supplies addresses only. The caller verifies the already-paired certificate and authenticated health. */
internal suspend fun discoverForgeOrigins(context:Context, serverId:String):List<String> {
    if(runCatching {UUID.fromString(serverId).toString()}.getOrNull()!=serverId)return emptyList()
    val nsd=context.applicationContext.getSystemService(NsdManager::class.java) ?: return emptyList()
    return withTimeoutOrNull(2350L){
        discoveryLock.withLock {ForgeDiscovery(nsd,serverId).firstMatch()}
    }.orEmpty()
}

internal fun resolvedForgeOrigins(expected:String, advertised:ByteArray?, port:Int, addresses:List<InetAddress>):List<String> {
    if(advertised?.toString(Charsets.US_ASCII)!=expected || port !in 1..65535)return emptyList()
    return addresses.mapNotNull {address ->
        val ipv6=address is Inet6Address
        val ula=ipv6 && (address.address[0].toInt() and 0xfe)==0xfc
        if(!address.isSiteLocalAddress && !ula)return@mapNotNull null
        val literal=address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
        val host=if(ipv6)"[$literal]" else literal
        runCatching {gatewayOrigin("https://$host:$port")}.getOrNull()
    }.distinct()
}

@Suppress("DEPRECATION")
private class ForgeDiscovery(private val nsd:NsdManager,private val expected:String) {
    private val inbox=Channel<NsdServiceInfo>(32)
    private val present=ConcurrentHashMap<String,NsdServiceInfo>()
    private val stopped=AtomicBoolean(false)

    private fun key(info:NsdServiceInfo):String {
        val network=if(Build.VERSION.SDK_INT>=33)info.network?.networkHandle else null
        return "${info.serviceName}:$network"
    }
    private val listener=object:NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type:String) {
            if(stopped.get())runCatching {nsd.stopServiceDiscovery(this)}
        }
        override fun onDiscoveryStopped(type:String){inbox.close()}
        override fun onStartDiscoveryFailed(type:String,error:Int){inbox.close()}
        override fun onStopDiscoveryFailed(type:String,error:Int){inbox.close()}
        override fun onServiceFound(info:NsdServiceInfo) {
            val type=info.serviceType.orEmpty().trim('.').removeSuffix(".local")
            if(stopped.get() || !type.equals(FORGE_SERVICE.trim('.'),true))return
            val advertised=info.attributes["id"]
            if(advertised!=null && advertised.toString(Charsets.US_ASCII)!=expected)return
            val key=key(info)
            if(present.size>=32 || present.putIfAbsent(key,info)!=null)return
            if(!inbox.trySend(info).isSuccess)present.remove(key,info)
        }
        override fun onServiceLost(info:NsdServiceInfo){present.remove(key(info))}
    }

    suspend fun firstMatch():List<String> {
        try {
            nsd.discoverServices(FORGE_SERVICE,NsdManager.PROTOCOL_DNS_SD,listener)
            for(candidate in inbox){
                val key=key(candidate)
                if(present[key]!==candidate)continue
                val resolved=resolve(candidate) ?: continue
                if(present[key]!==candidate)continue
                val addresses=if(Build.VERSION.SDK_INT>=34)resolved.hostAddresses else listOfNotNull(resolved.host)
                val origins=resolvedForgeOrigins(expected,resolved.attributes["id"],resolved.port,addresses)
                if(origins.isNotEmpty())return origins
            }
        } catch(e:CancellationException){throw e}
          catch(_:SecurityException){/* Discovery is optional. Saved routes still work. */}
          catch(_:IllegalArgumentException){/* NSD can become unavailable during a network change. */}
          catch(_:IllegalStateException){/* A disabled system NSD service may reject the request. */}
        finally {
            stopped.set(true)
            runCatching {nsd.stopServiceDiscovery(listener)}
            inbox.cancel()
            present.clear()
        }
        return emptyList()
    }

    private suspend fun resolve(candidate:NsdServiceInfo):NsdServiceInfo? {
        val result=CompletableDeferred<NsdServiceInfo?>()
        val resolution=object:NsdManager.ResolveListener {
            override fun onServiceResolved(info:NsdServiceInfo){result.complete(info)}
            override fun onResolveFailed(info:NsdServiceInfo,error:Int){result.complete(null)}
        }
        nsd.resolveService(candidate,resolution)
        return try {result.await()} finally {
            if(!result.isCompleted && Build.VERSION.SDK_INT>=34)runCatching {nsd.stopServiceResolution(resolution)}
            result.cancel()
        }
    }
}
