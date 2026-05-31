package com.wuxianggujun.tinaide.core.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

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

    // 使用独立的 OkHttpClient，避免通过 OkHttpClientProvider.probe 间接引用 SmartDns 导致循环初始化
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Dns.SYSTEM)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
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
            } catch (_: Throwable) {
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
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
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

private fun String.toInetAddressOrNull(): InetAddress? {
    return runCatching { InetAddress.getByName(this) }.getOrNull()
}

/**
 * 全局 SmartDns 实例
 * 用于 OkHttpClient 的 DNS 配置
 */
internal val SmartDns: Dns by lazy {
    val dohResolver = DohDnsResolver()
    val smartResolver = SmartDnsResolver(dohResolver)
    OkHttpSmartDns(smartResolver)
}

