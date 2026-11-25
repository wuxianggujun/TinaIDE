package com.wuxianggujun.tinaide.core.compile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.network.executeCancellable
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxyConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxySettings
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VcpkgNetworkDeviceTest {
    @Test fun configureQaProxyAndProbeOfficialApi() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("qaProxyHost", "10.0.2.2")
        val port = arguments.getString("qaProxyPort", "7890").toInt()
        val report = JSONObject().put("runId", arguments.getString("regressionRunId", "manual"))
            .put("systemProxy", ProxySelector.getDefault()?.select(URI("https://api.github.com"))?.toString())
        try {
            GitHubRegistryProxyConfig.save(context, GitHubRegistryProxySettings(true, host, port))
            val client = GitHubRegistryHttpClientFactory.probe(context).newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            val request = Request.Builder().url("https://api.github.com/repos/microsoft/vcpkg/git/ref/heads/master").build()
            client.newCall(request).executeCancellable().use { response ->
                report.put("status", response.code)
                assertEquals(200, response.code)
            }
            report.put("passed", true)
        } finally {
            File(regressionEvidenceDirectory(context), "network-probe.json").writeText(report.toString(2), Charsets.UTF_8)
        }
    }
}
