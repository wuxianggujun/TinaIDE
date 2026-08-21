package com.wuxianggujun.tinaide.core.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal object SuspiciousIp {
    fun isSuspicious(address: InetAddress): Boolean {
        if (address is Inet6Address) return false
        val bytes = (address as? Inet4Address)?.address ?: return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF

        // 198.18.0.0/15 (RFC 2544)
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
        // 198.51.100.0/24 (TEST-NET-2)
        if (b0 == 198 && b1 == 51 && b2 == 100) return true
        // 203.0.113.0/24 (TEST-NET-3)
        if (b0 == 203 && b1 == 0 && b2 == 113) return true
        // 127.0.0.0/8 loopback
        if (b0 == 127) return true
        // 0.0.0.0/8
        if (b0 == 0) return true
        // 100.64.0.0/10 (CGNAT)
        if (b0 == 100 && b1 in 64..127) return true

        return false
    }
}

internal class DohDnsResolver {

    private data class Endpoint(
        val name: String,
        val buildUrl: (host: String, type: String) -> String,
        val accept: String? = null,
    )

    private val endpoints = listOf(
        Endpoint(
            name = "dns.google",
            buildUrl = { host, type ->
                "https://dns.google/resolve?name=$host&type=$type"
            },
        ),
        Endpoint(
            name = "cloudflare-dns.com",
            buildUrl = { host, type ->
                "https://cloudflare-dns.com/dns-query?name=$host&type=$type"
            },
            accept = "application/dns-json",
        ),
    )

    // 独立的 OkHttpClient：不能复用 OkHttpClientProvider.probe，
    // 因为 probe 绑定的是 SmartDns，而 SmartDns 的初始化又依赖本类——
    // 通过 probe 引用会触发 SmartDns lazy 的递归初始化（StackOverflowError）。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Dns.SYSTEM)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Result(
        val addresses: List<InetAddress>,
        val source: String,
    )

    fun resolve(host: String): Result? {
        val addresses = LinkedHashMap<String, InetAddress>()

        for (endpoint in endpoints) {
            try {
                // 先查 A，再查 AAAA（避免无谓的 IPv6 失败阻塞）
                for (addr in query(endpoint, host, "A")) {
                    val ip = addr.hostAddress ?: continue
                    addresses[ip] = addr
                }
                if (addresses.isNotEmpty()) return Result(addresses.values.toList(), endpoint.name)

                for (addr in query(endpoint, host, "AAAA")) {
                    val ip = addr.hostAddress ?: continue
                    addresses[ip] = addr
                }
                if (addresses.isNotEmpty()) return Result(addresses.values.toList(), endpoint.name)
            } catch (_: Exception) {
                // ignore
            }
        }

        return null
    }

    private fun query(endpoint: Endpoint, host: String, type: String): List<InetAddress> {
        val url = endpoint.buildUrl(host, type)
        val request =
            Request.Builder()
                .get()
                .url(url)
                .apply { endpoint.accept?.let { header("Accept", it) } }
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.request.url.isHttps) return emptyList()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.readUtf8Limited(MAX_DOH_RESPONSE_BYTES).orEmpty()
            if (body.isBlank()) return emptyList()

            val json = JSONObject(body)
            if (json.optInt("Status", -1) != 0) return emptyList()

            val answers = json.optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<InetAddress>(answers.length())
            for (i in 0 until answers.length()) {
                val ans = answers.optJSONObject(i) ?: continue
                val data = ans.optString("data").trim()
                if (data.isBlank()) continue
                // data 可能包含 CNAME 等，过滤掉非 IP
                val addr = data.toInetAddressOrNull() ?: continue
                out.add(addr)
            }
            return out
        }
    }

    private companion object {
        private const val MAX_DOH_RESPONSE_BYTES = 256 * 1024
    }
}

class SmartDnsResolver internal constructor(
    private val dohDnsResolver: DohDnsResolver? = null,
    private val cacheTtlMs: Long = 5 * 60 * 1000L,
) {
    data class Resolution(
        val host: String,
        val systemAddresses: List<InetAddress>,
        val dohAddresses: List<InetAddress>,
        val dohAttempted: Boolean,
        val usedDoh: Boolean,
        val dohSource: String?,
    ) {
        val chosenAddresses: List<InetAddress> = if (usedDoh) dohAddresses else systemAddresses

        val systemSuspicious: List<InetAddress> = systemAddresses.filter { SuspiciousIp.isSuspicious(it) }
        val systemNonSuspicious: List<InetAddress> = systemAddresses.filterNot { SuspiciousIp.isSuspicious(it) }
    }

    private data class CacheEntry(val resolution: Resolution, val createdAtMs: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun resolve(host: String): Resolution {
        val now = System.currentTimeMillis()
        cache[host]?.let { entry ->
            if (now - entry.createdAtMs <= cacheTtlMs) return entry.resolution
        }

        val systemAddresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { emptyList() }
        val hasNonSuspicious = systemAddresses.any { !SuspiciousIp.isSuspicious(it) }

        if (hasNonSuspicious) {
            val r = Resolution(
                host = host,
                systemAddresses = systemAddresses,
                dohAddresses = emptyList(),
                dohAttempted = false,
                usedDoh = false,
                dohSource = null,
            )
            cache[host] = CacheEntry(r, now)
            return r
        }

        val doh = dohDnsResolver?.resolve(host)
        val dohAttempted = dohDnsResolver != null
        val r =
            if (doh != null && doh.addresses.isNotEmpty()) {
                Resolution(
                    host = host,
                    systemAddresses = systemAddresses,
                    dohAddresses = doh.addresses,
                    dohAttempted = dohAttempted,
                    usedDoh = true,
                    dohSource = doh.source,
                )
            } else {
                Resolution(
                    host = host,
                    systemAddresses = systemAddresses,
                    dohAddresses = emptyList(),
                    dohAttempted = dohAttempted,
                    usedDoh = false,
                    dohSource = null,
                )
            }

        cache[host] = CacheEntry(r, now)
        return r
    }
}

internal class OkHttpSmartDns(private val resolver: SmartDnsResolver) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val r = resolver.resolve(hostname)
        val chosen = r.chosenAddresses
        if (chosen.isNotEmpty()) return chosen
        throw UnknownHostException(hostname)
    }
}

private fun String.toInetAddressOrNull(): InetAddress? = runCatching { InetAddress.getByName(this) }.getOrNull()

/**
 * 全局 SmartDns 单例。
 *
 * 设计要点（用于杜绝循环初始化导致的 StackOverflowError）：
 *
 *  1. 本类是 `object`，引用它本身**不触发**任何懒加载——任何 OkHttpClient.Builder
 *     上 `.dns(SmartDns)` 的调用代价为零。
 *  2. 内部 [delegate]（真正持有 DoH 客户端等重资源）在第一次 [lookup] 时才构造。
 *     这意味着 OkHttpClient 的构造阶段绝不会触发本类内部的网络栈初始化。
 *  3. `delegate` 的构造路径**严禁**引用 [OkHttpClientProvider.probe] 或任何会
 *     回链到 SmartDns 的 client，否则会在第一次 lookup 时再次出现循环。
 *     当前 [DohDnsResolver] 自己持有独立 OkHttpClient（见该类内部注释）。
 *  4. 双检锁的 synchronized 是可重入的；只要规则 (3) 被遵守，就不会重入。
 *     如果规则被破坏，会在 build() 内部立刻栈溢出而不是延迟到任意请求路径，
 *     便于排查。
 */
internal object SmartDns : Dns {

    @Volatile private var delegate: Dns? = null
    private val initLock = Any()

    override fun lookup(hostname: String): List<InetAddress> {
        val d = delegate ?: synchronized(initLock) {
            delegate ?: build().also { delegate = it }
        }
        return d.lookup(hostname)
    }

    private fun build(): Dns {
        val dohResolver = DohDnsResolver()
        val smartResolver = SmartDnsResolver(dohResolver)
        return OkHttpSmartDns(smartResolver)
    }
}
