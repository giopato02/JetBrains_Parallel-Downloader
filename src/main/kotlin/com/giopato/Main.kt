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
        println("Usage: <url> <outputFile> [chunkCount]")
        println("Example: http://localhost:8080/file.txt output.txt 4")
        return
    }

    val url        = args[0]
    val outputFile = File(args[1])
    val chunkCount = args.getOrNull(2)?.toIntOrNull() ?: 4

    println("Downloading: $url")
    println("Output:      ${outputFile.absolutePath}")
    println("Chunks:      $chunkCount")

    val httpClient = OkHttpClientAdapter()
    val downloader = Downloader(httpClient, chunkCount)

    runBlocking {
        downloader.download(url, outputFile)
    }
}