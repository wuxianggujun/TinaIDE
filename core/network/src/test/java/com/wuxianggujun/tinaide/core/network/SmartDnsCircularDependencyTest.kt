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
     * 测试通过条件：方法执行完成不抛异常。
     */
    @Test
    fun dohDnsResolver_canBeInstantiatedWithoutStackOverflow() {
        DohDnsResolver()
    }

    /**
     * SmartDns 全局 object 实例应能正常初始化，不触发 StackOverflowError。
     * 修复前访问 SmartDns 会触发 DohDnsResolver → probe → SmartDns 的循环。
     * 测试通过条件：方法执行完成不抛异常。
     */
    @Test
    fun smartDns_lazyInitializationDoesNotCauseInfiniteRecursion() {
        SmartDns.toString() // 触发 object 初始化
    }

    /**
     * OkHttpClientProvider.probe 应能正常初始化，不触发 StackOverflowError。
     * 修复前 probe 会引用 SmartDns，而 SmartDns 初始化时又会引用 probe。
     * 测试通过条件：方法执行完成不抛异常。
     */
    @Test
    fun probeClient_canBeInitializedWithoutStackOverflow() {
        OkHttpClientProvider.probe.toString()
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
