package com.giopato

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * OkHttp-backed implementation of HttpClient.
 * This is the real implementation used in production.
 * Tests use a fake implementation instead.
 */
class OkHttpClientAdapter : HttpClient {
    private val client = OkHttpClient()

    override fun getFileSize(url: String): Long? {
        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val acceptsRanges = response.header("Accept-Ranges")
            if (acceptsRanges != "bytes") return null
            return response.header("Content-Length")?.toLongOrNull()
        }
    }

    override fun downloadChunk(url: String, from: Long, to: Long): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$from-$to")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful || response.code == 206) {
                "Unexpected response code: ${response.code}"
            }
            return response.body?.bytes()
                ?: error("Empty response body for range $from-$to")
        }
    }
}

/**
 * CLI entry point.
 * Usage: ./gradlew run --args="<url> <outputFile> [chunkCount]"
 * Example: ./gradlew run --args="http://localhost:8080/file.txt output.txt 4"
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: <url> <outputFile> [chunkCount|chunkSize]")
        println("Examples:")
        println("  http://localhost:8080/file.txt output.txt 4       (4 chunks)")
        println("  http://localhost:8080/file.txt output.txt 2MB     (2MB per chunk)")
        println("  http://localhost:8080/file.txt output.txt 512KB   (512KB per chunk)")
        return
    }

    val url        = args[0]
    val outputFile = File(args[1])
    val thirdArg   = args.getOrNull(2)

    // Third arg can be:
    //   "4"     → 4 chunks
    //   "2MB"   → 2MB per chunk
    //   "512KB" → 512KB per chunk
    val httpClient = OkHttpClientAdapter()
    val downloader = when {
        thirdArg == null -> {
            println("Chunks: 4 (default)")
            Downloader(httpClient, chunkCount = 4)
        }
        thirdArg.endsWith("MB", ignoreCase = true) -> {
            val mb = thirdArg.dropLast(2).toLongOrNull() ?: 1L
            println("Chunk size: ${mb}MB")
            Downloader(httpClient, chunkSizeBytes = mb * 1024 * 1024)
        }
        thirdArg.endsWith("KB", ignoreCase = true) -> {
            val kb = thirdArg.dropLast(2).toLongOrNull() ?: 512L
            println("Chunk size: ${kb}KB")
            Downloader(httpClient, chunkSizeBytes = kb * 1024)
        }
        else -> {
            val count = thirdArg.toIntOrNull() ?: 4
            println("Chunks: $count")
            Downloader(httpClient, chunkCount = count)
        }
    }

    runBlocking {
        downloader.download(url, outputFile)
    }
}