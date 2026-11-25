package com.wuxianggujun.tinaide.core.network.aegis

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class AegisHubEnvironmentTest {
    @After fun reset() = AegisHubEnvironment.replaceForTesting(null)

    @Test fun acceptsHttpsAndLoopbackDevelopmentWithAnOptionalPathPrefix() {
        initialize(" https://reports.example.com/proxy/ ")
        assertThat(AegisHubEnvironment.config?.baseUrl).isEqualTo("https://reports.example.com/proxy")
        initialize("http://127.0.0.1:8081")
        assertThat(AegisHubEnvironment.config).isNotNull()
    }

    @Test fun invalidOrMissingConfigurationDisablesReporting() {
        listOf("", "http://reports.example.com", "https:///missing-host", "https://user@reports.example.com",
            "https://reports.example.com?token=value", "https://reports.example.com/#fragment",
            "https://reports.example.com:0", "https://reports.example.com:99999").forEach { url ->
            initialize(url)
            assertThat(AegisHubEnvironment.config).isNull()
        }
        initialize("https://reports.example.com", appId = "../other-app")
        assertThat(AegisHubEnvironment.config).isNull()
        initialize("https://reports.example.com", ingestKey = "")
        assertThat(AegisHubEnvironment.config).isNull()
        initialize("https://reports.example.com", ingestKey = "key\nheader")
        assertThat(AegisHubEnvironment.config).isNull()
    }

    @Test fun utf8LimitsKeepWholeChineseCharactersAndEmoji() {
        assertThat(AegisFeedback.truncateUtf8Bytes("中文🙂", 5)).isEqualTo("中")
        assertThat(AegisFeedback.truncateUtf8Bytes("中文🙂", 9)).isEqualTo("中文")
        assertThat(AegisFeedback.truncateUtf8Bytes("中文🙂", 10)).isEqualTo("中文🙂")
        assertThat(AegisFeedback.normalizeMessage(" 中文🙂 ")).isEqualTo("中文🙂")
    }

    private fun initialize(
        url: String,
        appId: String = "00000000-0000-0000-0000-000000000001",
        ingestKey: String = "test-ingest-key",
    ) = AegisHubEnvironment.initialize(url, appId, ingestKey, "1.0.0", "test-build", "development")
}
