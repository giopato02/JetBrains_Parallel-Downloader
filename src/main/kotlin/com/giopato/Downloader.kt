package com.giopato

import kotlinx.coroutines.*
import java.io.File
import kotlinx.coroutines.delay

/**
 * Core downloader — splits a file into [chunkCount] equal parts,
 * downloads them all in parallel, then assembles the final file.
 */
class Downloader(
    private val httpClient: HttpClient,
    private val chunkCount: Int = 4
) {
    /**
     * Downloads the file at [url] and saves it to [outputFile].
     * Steps:
     *   1. HEAD request  → get total file size
     *   2. Split into N chunks
     *   3. Download all chunks in parallel using coroutines
     *   4. Write chunks to the output file in correct order
     */
    suspend fun download(url: String, outputFile: File) {
        // Step 1 — get file size
        val fileSize = httpClient.getFileSize(url)
            ?: error("Server does not support range requests for: $url")

        println("File size: $fileSize bytes — splitting into $chunkCount chunks")

        // Step 2 — calculate byte ranges for each chunk
        val ranges = calculateRanges(fileSize, chunkCount)

        // Step 3 — download all chunks in parallel
        val chunks: List<ByteArray> = coroutineScope {
            ranges.mapIndexed { index, (from, to) ->
                async(Dispatchers.IO) {
                    println("Downloading chunk $index: bytes $from-$to")
                    val bytes = downloadChunkWithRetry(url, from, to, index)
                    println("Chunk $index complete (${bytes.size} bytes)")
                    bytes
                }
            }.awaitAll()
        }

        // Step 4 — assemble and write to disk
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { out ->
            chunks.forEach { chunk -> out.write(chunk) }
        }

        println("Download complete: ${outputFile.absolutePath}")
    }

    /**
     * Attempts to download a chunk up to [maxRetries] times.
     * Waits [delayMs] milliseconds between attempts.
     * Throws the last exception if all attempts fail.
     */
    private suspend fun downloadChunkWithRetry(
        url: String,
        from: Long,
        to: Long,
        index: Int,
        maxRetries: Int = 3,
        delayMs: Long = 1000L
    ): ByteArray {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return httpClient.downloadChunk(url, from, to)
            } catch (e: Exception) {
                lastException = e
                println("Chunk $index failed (attempt ${attempt + 1}/$maxRetries): ${e.message}")
                if (attempt < maxRetries - 1) {
                    delay(delayMs)
                }
            }
        }
        throw lastException ?: error("Chunk $index failed after $maxRetries attempts")
    }

    /**
     * Splits [fileSize] bytes into [count] roughly equal ranges.
     * Returns a list of (from, to) pairs — both inclusive, like HTTP Range header.
     *
     * Example: 1000 bytes, 3 chunks → [(0,332), (333,665), (666,999)]
     */
    fun calculateRanges(fileSize: Long, count: Int): List<Pair<Long, Long>> {
        require(count > 0) { "Chunk count must be positive" }
        require(fileSize > 0) { "File size must be positive" }

        val chunkSize = fileSize / count
        return (0 until count).map { i ->
            val from = i * chunkSize
            val to = if (i == count - 1) fileSize - 1 else from + chunkSize - 1
            from to to
        }
    }
}