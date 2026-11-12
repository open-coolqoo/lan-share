package com.lanshare.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal data class LanDiscoveredService(
    val serviceName: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
)

/** The bit of NSD the room session needs, so JVM tests can fake it. */
internal interface LanAdvertiser {
    fun advertise(serviceName: String, port: Int, txt: Map<String, String>): Boolean
    fun stop()
    suspend fun discover(timeoutMs: Long, match: (LanDiscoveredService) -> Boolean): LanDiscoveredService?
}

internal class LanDiscovery(context: Context) : LanAdvertiser {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registration: NsdManager.RegistrationListener? = null

    override fun advertise(serviceName: String, port: Int, txt: Map<String, String>): Boolean {
        val manager = nsd ?: return false
        stop()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = LAN_SERVICE_TYPE
            setPort(port)
            txt.forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        return runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.isSuccess
    }

    override fun stop() {
        val manager = nsd ?: return
        val listener = registration ?: return
        registration = null
        runCatching { manager.unregisterService(listener) }
    }

    /**
     * Browses until [match] accepts one service or [timeoutMs] elapses. NSD
     * callbacks are delivered on the calling thread's looper, so the browse is
     * started on the main dispatcher and the result is returned to the caller's
     * context.
     */
    override suspend fun discover(
        timeoutMs: Long,
        match: (LanDiscoveredService) -> Boolean,
    ): LanDiscoveredService? = withContext(Dispatchers.Main.immediate) {
        val manager = nsd ?: return@withContext null
        suspendCancellableCoroutine { continuation ->
            val pending = ArrayDeque<NsdServiceInfo>()
            var resolving = false
            var finished = false
            var listener: NsdManager.DiscoveryListener? = null
            val handler = Handler(Looper.getMainLooper())

            fun stop() {
                listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
                listener = null
            }

            fun finish(service: LanDiscoveredService?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                stop()
                if (continuation.isActive) continuation.resume(service)
            }

            fun resolveNext() {
                if (finished || resolving) return
                val next = pending.pollFirst() ?: return
                resolving = true
                @Suppress("DEPRECATION")
                runCatching {
                    manager.resolveService(next, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolving = false
                            resolveNext()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            resolving = false
                            val host = serviceInfo.host?.hostAddress
                            if (host.isNullOrBlank() || serviceInfo.port !in 1..65535) {
                                resolveNext()
                                return
                            }
                            val txt = runCatching {
                                serviceInfo.attributes
                                    .orEmpty()
                                    .mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
                            }.getOrDefault(emptyMap())
                            val discovered = LanDiscoveredService(
                                serviceName = serviceInfo.serviceName.orEmpty(),
                                host = host.substringBefore('%'),
                                port = serviceInfo.port,
                                txt = txt,
                            )
                            if (match(discovered)) {
                                finish(discovered)
                            } else {
                                resolveNext()
                            }
                        }
                    })
                }.onFailure {
                    resolving = false
                    resolveNext()
                }
            }

            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (finished) return
                    if (!serviceInfo.serviceType.orEmpty().contains(LAN_SERVICE_TYPE)) return
                    pending.addLast(serviceInfo)
                    resolveNext()
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            listener = discovery
            handler.postDelayed({ finish(null) }, timeoutMs)
            continuation.invokeOnCancellation { finish(null) }
            runCatching {
                manager.discoverServices(LAN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            }.onFailure { finish(null) }
        }
    }
}
