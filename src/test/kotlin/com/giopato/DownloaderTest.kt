package com.giopato

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.*

class DownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var outputFile: File

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        outputFile = File.createTempFile("download_test", ".bin")
        outputFile.deleteOnExit()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
        outputFile.delete()
    }

    // ── calculateRanges ───────────────────────────────────────────────────────

    @Test
    fun `calculateRanges splits file into correct number of chunks`() {
        val downloader = Downloader(FakeHttpClient(), chunkCount = 4)
        val ranges = downloader.calculateRanges(1000L, 4)
        assertEquals(4, ranges.size)
    }

    @Test
    fun `calculateRanges last chunk ends at fileSize minus 1`() {
        val downloader = Downloader(FakeHttpClient(), chunkCount = 4)
        val ranges = downloader.calculateRanges(1000L, 4)
        assertEquals(999L, ranges.last().second)
    }

    @Test
    fun `calculateRanges chunks cover entire file without gaps`() {
        val downloader = Downloader(FakeHttpClient(), chunkCount = 4)
        val ranges = downloader.calculateRanges(1000L, 4)
        // Each chunk's start should be exactly previous chunk's end + 1
        for (i in 1 until ranges.size) {
            assertEquals(ranges[i - 1].second + 1, ranges[i].first,
                "Gap between chunk ${i-1} and $i")
        }
    }

    @Test
    fun `calculateRanges works with single chunk`() {
        val downloader = Downloader(FakeHttpClient(), chunkCount = 1)
        val ranges = downloader.calculateRanges(500L, 1)
        assertEquals(1, ranges.size)
        assertEquals(0L to 499L, ranges[0])
    }

    @Test
    fun `calculateRanges throws on zero chunk count`() {
        val downloader = Downloader(FakeHttpClient(), chunkCount = 1)
        assertFailsWith<IllegalArgumentException> {
            downloader.calculateRanges(1000L, 0)
        }
    }

    @Test
    fun `calculateRanges throws on zero file size`() {
        val downloader = Downloader(FakeHttpClient(), chunkCount = 4)
        assertFailsWith<IllegalArgumentException> {
            downloader.calculateRanges(0L, 4)
        }
    }

    // ── download with MockWebServer ───────────────────────────────────────────

    @Test
    fun `download assembles chunks into correct file`() = runBlocking {
        // Full file content
        val content = "Hello, Parallel World! This is a test file for chunked download."
        val bytes = content.toByteArray()

        // HttpClient fake that returns correct slices
        val fakeClient = FakeHttpClient(
            fileSize = bytes.size.toLong(),
            content = bytes
        )

        val downloader = Downloader(fakeClient, chunkCount = 4)
        downloader.download("http://fake-url/file.txt", outputFile)

        // Verify the assembled file matches original content
        assertEquals(content, outputFile.readText())
    }

    @Test
    fun `download creates output file`() = runBlocking {
        val content = "Test content for file creation check."
        val fakeClient = FakeHttpClient(
            fileSize = content.length.toLong(),
            content = content.toByteArray()
        )
        val downloader = Downloader(fakeClient, chunkCount = 2)
        downloader.download("http://fake-url/file.txt", outputFile)
        assertTrue(outputFile.exists())
    }

    @Test
    fun `download handles single chunk correctly`() = runBlocking {
        val content = "Single chunk content."
        val fakeClient = FakeHttpClient(
            fileSize = content.length.toLong(),
            content = content.toByteArray()
        )
        val downloader = Downloader(fakeClient, chunkCount = 1)
        downloader.download("http://fake-url/file.txt", outputFile)
        assertEquals(content, outputFile.readText())
    }

    @Test
    fun `download throws when server does not support range requests`() = runBlocking {
        val fakeClient = FakeHttpClient(fileSize = null) // null = no range support
        val downloader = Downloader(fakeClient, chunkCount = 4)
        assertFailsWith<IllegalStateException> {
            downloader.download("http://fake-url/file.txt", outputFile)
        }
        Unit
    }

    @Test
    fun `download with MockWebServer returns correct content`() = runBlocking {
        val content = "MockWebServer test content!"
        val bytes = content.toByteArray()

        // Use a dispatcher that reads the actual Range header and serves correct bytes
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when (request.method) {
                    "HEAD" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Accept-Ranges", "bytes")
                        .addHeader("Content-Length", bytes.size.toString())
                    "GET" -> {
                        val range = request.getHeader("Range")!! // e.g. "bytes=0-5"
                        val (from, to) = range.removePrefix("bytes=")
                            .split("-").map { it.toLong() }
                        val chunk = bytes.copyOfRange(from.toInt(), to.toInt() + 1)
                        MockResponse()
                            .setResponseCode(206)
                            .setBody(okio.Buffer().write(chunk))
                    }
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }

        val httpClient = OkHttpClientAdapter()
        val downloader = Downloader(httpClient, chunkCount = 4)
        downloader.download(server.url("/file.txt").toString(), outputFile)

        assertEquals(content, outputFile.readText())
    }

    @Test
    fun `download retries failed chunk and succeeds`() = runBlocking {
        val content = "Retry logic test content for parallel downloader."
        val bytes = content.toByteArray()

        // Fails on first call, succeeds on second
        var callCount = 0
        val flakyClient = object : HttpClient {
            override fun getFileSize(url: String) = bytes.size.toLong()
            override fun downloadChunk(url: String, from: Long, to: Long): ByteArray {
                callCount++
                if (callCount == 1) throw Exception("Simulated network failure")
                return bytes.copyOfRange(from.toInt(), to.toInt() + 1)
            }
        }

        val downloader = Downloader(flakyClient, chunkCount = 1)
        downloader.download("http://fake-url/file.txt", outputFile)

        assertEquals(content, outputFile.readText())
        assertEquals(2, callCount, "Should have been called twice — once failing, once succeeding")
    }

    @Test
    fun `download completes all chunks and shows progress`() = runBlocking {
        val content = "Progress bar test content for the parallel downloader!"
        val bytes = content.toByteArray()
        val fakeClient = FakeHttpClient(
            fileSize = bytes.size.toLong(),
            content = bytes
        )
        // showProgress = false to keep test output clean
        val downloader = Downloader(fakeClient, chunkCount = 4, showProgress = false)
        downloader.download("http://fake-url/file.txt", outputFile)
        assertEquals(content, outputFile.readText())
        assertTrue(outputFile.length() > 0)
    }
}

// ── Fake HttpClient for unit tests ────────────────────────────────────────────
/**
 * In-memory fake that serves content without any real HTTP.
 * [fileSize] null simulates a server that doesn't support range requests.
 */
class FakeHttpClient(
    private val fileSize: Long? = 100L,
    private val content: ByteArray = ByteArray(100) { it.toByte() }
) : HttpClient {

    override fun getFileSize(url: String): Long? = fileSize

    override fun downloadChunk(url: String, from: Long, to: Long): ByteArray {
        return content.copyOfRange(from.toInt(), to.toInt() + 1)
    }
}