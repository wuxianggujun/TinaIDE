package com.wuxianggujun.tinaide.core.network.aegis

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real HTTP exchanges against loopback, with synthetic credentials and bounded fixture files. */
class AegisHubClientTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: HttpServer
    private val requests = LinkedBlockingQueue<CapturedRequest>()
    private val responses = LinkedBlockingQueue<Pair<Int, String>>()

    @Before fun setUp() {
        mockkObject(AppStrings)
        every { AppStrings.get(any(), *anyVararg()) } returns "Localized error"
        every { AppStrings.get(any(), *anyVararg()) } returns "Localized error"
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(CapturedRequest(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("Content-Type"), exchange.requestBody.readBytes()))
            val (status, response) = responses.poll() ?: (500 to "{}")
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        AegisHubEnvironment.initialize("http://127.0.0.1:${server.address.port}", APP_ID,
            "test-ingest-key", "1.0.0", "test-build", "development")
    }

    @After fun tearDown() {
        server.stop(0)
        AegisHubEnvironment.replaceForTesting(null)
        unmockkObject(AppStrings)
    }

    @Test fun sendsTheIngestContractAndPreservesTheTelemetryReceipt() = runBlocking {
        responses.add(202 to """{"id":"$REPORT_ID","status":"telemetry"}""")
        val result = AegisHubClient.submitReport(report())
        assertThat((result as ApiResult.Success).data.status).isEqualTo("telemetry")
        val request = requests.remove()
        assertThat(request.path).isEqualTo("/ingest/v1/apps/$APP_ID/reports")
        assertThat(request.authorization).isEqualTo("Bearer test-ingest-key")
        assertThat(request.contentType).contains("application/json")
        val json = JsonSerializer.default.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject
        assertThat(json["event_id"]?.jsonPrimitive?.content).isEqualTo(EVENT_ID)
        assertThat(json["type_key"]?.jsonPrimitive?.content).isEqualTo("diagnostic")
        assertThat(json["message"]?.jsonPrimitive?.content).isEqualTo("验收🙂")
    }

    @Test fun transientFailureRetriesTheSameEventAndPayload() = runBlocking {
        responses.add(503 to "{}")
        responses.add(202 to """{"id":"$REPORT_ID","status":"pending"}""")
        assertThat(AegisHubClient.submitReport(report())).isInstanceOf(ApiResult.Success::class.java)
        assertThat(requests).hasSize(2)
        val first = requests.remove()
        assertThat(requests.remove().body.toList()).containsExactlyElementsIn(first.body.toList()).inOrder()
    }

    @Test fun schemaRejectionIsSurfacedWithoutRetrying() = runBlocking {
        responses.add(400 to """{"error":{"message":"Unknown report type"}}""")
        val result = AegisHubClient.submitReport(report()) as ApiResult.Error
        assertThat(result.code).isEqualTo(400)
        assertThat(result.message).isEqualTo("Unknown report type")
        assertThat(requests).hasSize(1)
    }

    @Test fun attachmentUsesMultipartFileAndAnOpaqueArchiveMimeType() = runBlocking {
        val file = temporaryFolder.newFile("诊断🙂.zip").apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }
        responses.add(200 to """{"id":"$EVENT_ID","report_id":"$REPORT_ID","file_name":"fixture.zip","content_type":"application/octet-stream","size_bytes":4,"sha256":"hash","created_at":"2026-11-15T00:00:00Z"}""")
        assertThat(AegisHubClient.uploadAttachment(REPORT_ID, file).isSuccess).isTrue()
        val request = requests.remove()
        assertThat(request.path).isEqualTo("/ingest/v1/apps/$APP_ID/reports/$REPORT_ID/attachments")
        assertThat(request.contentType).startsWith("multipart/form-data;")
        val body = request.body.toString(Charsets.UTF_8)
        assertThat(body).contains("name=\"file\"; filename=\"诊断🙂.zip\"")
        assertThat(body).contains("Content-Type: application/octet-stream")
    }

    @Test fun missingConfigurationAndOversizeFilesNeverSendARequest() = runBlocking {
        val file = temporaryFolder.newFile("oversize.zip")
        RandomAccessFile(file, "rw").use { it.setLength(AegisHubClient.MAX_ATTACHMENT_BYTES + 1) }
        assertThat(AegisHubClient.uploadAttachment(REPORT_ID, file).isError).isTrue()
        AegisHubEnvironment.replaceForTesting(null)
        assertThat(AegisHubClient.submitReport(report()).isError).isTrue()
        assertThat(requests).isEmpty()
    }

    private fun report() = AegisIngestReport(eventId = EVENT_ID, typeKey = "diagnostic", release = "1.0.0",
        environment = "development", occurredAt = "2026-11-15T00:00:00Z", message = "验收🙂")

    private data class CapturedRequest(val path: String, val authorization: String?, val contentType: String?, val body: ByteArray)

    companion object {
        private const val APP_ID = "00000000-0000-0000-0000-000000000001"
        private const val REPORT_ID = "00000000-0000-0000-0000-000000000002"
        private const val EVENT_ID = "00000000-0000-0000-0000-000000000003"
    }
}
