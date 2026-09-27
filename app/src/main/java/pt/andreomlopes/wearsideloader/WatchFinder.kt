package pt.andreomlopes.wearsideloader

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Finds watches advertising Wireless debugging on the local network.
 *
 * libadb-android's own autoConnect() can't be used for this: its mDNS helper discards every
 * service whose IP isn't one of *this phone's* addresses, because it was written for apps that
 * connect to their own device. Here the filter is inverted — this phone's addresses are the ones
 * excluded.
 */
class WatchFinder(context: Context) {

    data class Found(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Blocks for up to [timeoutMs], collecting everything found. Returns as soon as a service on
     * [preferredHost] resolves, since that's almost certainly the watch used last time.
     */
    fun find(timeoutMs: Long, preferredHost: String?): List<Found> {
        val ownAddresses = ownIpv4Addresses()
        val found = LinkedHashMap<String, Found>()
        val preferredSeen = CountDownLatch(1)
        // Pre-API-34 NsdManager rejects a second resolve while one is in flight, so resolve serially.
        val toResolve = LinkedBlockingQueue<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            synchronized(toResolve) {
                if (resolving) return
                val next = toResolve.poll() ?: return
                resolving = true
                nsd.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = (info.host as? Inet4Address)?.hostAddress
                        if (host != null && host !in ownAddresses) {
                            synchronized(found) { found[info.serviceName] = Found(info.serviceName, host, info.port) }
                            if (host == preferredHost) preferredSeen.countDown()
                        }
                        done()
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()

                    private fun done() {
                        synchronized(toResolve) { resolving = false }
                        resolveNext()
                    }
                })
            }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.add(info)
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = preferredSeen.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        try {
            preferredSeen.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
        return synchronized(found) { found.values.toList() }
    }

    private fun ownIpv4Addresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    private companion object {
        const val SERVICE_TYPE = "_adb-tls-connect._tcp."
    }
}
