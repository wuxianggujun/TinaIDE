package com.wuxianggujun.tinaide.core.network.registry

import android.content.Context
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient

object GitHubRegistryHttpClientFactory {
    fun probe(context: Context): OkHttpClient = withProxy(
        context = context,
        baseClient = OkHttpClientProvider.probe,
    )

    fun download(context: Context): OkHttpClient = withProxy(
        context = context,
        baseClient = OkHttpClientProvider.download,
    )

    private fun withProxy(
        context: Context,
        baseClient: OkHttpClient,
    ): OkHttpClient {
        val settings = GitHubRegistryProxyConfig.load(context)
        return baseClient.newBuilder()
            .apply {
                if (settings.isUsable) {
                    proxy(
                        Proxy(
                            Proxy.Type.HTTP,
                            InetSocketAddress(settings.host, settings.port),
                        )
                    )
                }
            }
            .addNetworkInterceptor { chain ->
                if (!chain.request().url.isHttps) {
                    throw IOException("Registry requests require HTTPS")
                }
                chain.proceed(chain.request()).also { response ->
                    if (!response.request.url.isHttps) {
                        response.close()
                        throw IOException("Registry redirect downgraded from HTTPS")
                    }
                }
            }
            .build()
    }
}
