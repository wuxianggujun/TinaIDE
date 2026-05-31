package com.wuxianggujun.tinaide.core.network

import org.junit.Test

/**
 * 验证 SmartDns 与 OkHttpClientProvider 之间的循环依赖已被打破。
 *
 * 修复前，以下调用链会无限递归导致 StackOverflowError：
 *   SmartDns (lazy) → DohDnsResolver.<init> → OkHttpClientProvider.probe (lazy)
 *   → SmartDns → DohDnsResolver → ... → StackOverflowError
 *
 * 修复后，DohDnsResolver 使用独立的 OkHttpClient（Dns.SYSTEM），
 * 不再引用 OkHttpClientProvider.probe，循环被打破。
 */
class SmartDnsCircularDependencyTest {

    /**
     * DohDnsResolver 应能正常实例化，不触发 StackOverflowError。
     * 修复前此测试会导致无限递归崩溃。
     */
    @Test
    fun dohDnsResolver_canBeInstantiatedWithoutStackOverflow() {
        val resolver = DohDnsResolver()
        // 实例化成功即通过，无需断言
        assert(resolver != null)
    }

    /**
     * SmartDns 全局 lazy 实例应能正常初始化，不触发 StackOverflowError。
     * 修复前访问 SmartDns 会触发 DohDnsResolver → probe → SmartDns 的循环。
     */
    @Test
    fun smartDns_lazyInitializationDoesNotCauseInfiniteRecursion() {
        val dns = SmartDns
        assert(dns != null)
    }

    /**
     * OkHttpClientProvider.probe 应能正常初始化，不触发 StackOverflowError。
     * 修复前 probe 会引用 SmartDns，而 SmartDns 初始化时又会引用 probe。
     */
    @Test
    fun probeClient_canBeInitializedWithoutStackOverflow() {
        val client = OkHttpClientProvider.probe
        assert(client != null)
    }

    /**
     * SmartDnsResolver 应能正常解析域名（使用系统 DNS 回退）。
     */
    @Test
    fun smartDnsResolver_resolveLocalhost() {
        val resolver = SmartDnsResolver()
        val result = resolver.resolve("localhost")

        assert(result.systemAddresses.isNotEmpty()) {
            "localhost 应至少解析到一个地址"
        }
    }
}
