package com.giopato

import kotlinx.coroutines.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Core downloader — splits a file into [chunkCount] equal parts,
 * downloads them all in parallel, then assembles the final file.
 */
class Downloader(
    private val httpClient: HttpClient,
    private val chunkCount: Int = 4,
    private val showProgress: Boolean = true,
    private val chunkSizeBytes: Long? = null
) {
    private val printMutex = Mutex()
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

        val actualChunkCount = if (chunkSizeBytes != null) {
            maxOf(1, (fileSize / chunkSizeBytes).toInt())
        } else {
            chunkCount
        }

        println("File size: $fileSize bytes — splitting into $actualChunkCount chunks")

        // Step 2 — calculate byte ranges for each chunk
        val ranges = calculateRanges(fileSize, actualChunkCount)

        // Step 3 — download all chunks in parallel
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val chunks: List<ByteArray> = coroutineScope {
            ranges.mapIndexed { index, (from, to) ->
                async(Dispatchers.IO) {
                    val bytes = downloadChunkWithRetry(url, from, to, index)
                    val done = completed.incrementAndGet()
                    if (showProgress) {
                        printMutex.withLock {
                            printProgress(done, actualChunkCount, fileSize)  // ← change here
                        }
                    }
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
     * Prints a live progress bar to the console.
     * Example: [=========>    ] 60% (3/5 chunks) 85 bytes
     */
    private fun printProgress(completed: Int, total: Int, fileSize: Long) {
        val percent = (completed * 100) / total
        val barWidth = 30
        val filled = (barWidth * completed) / total
        val bar = "=".repeat(filled) + ">" + " ".repeat(barWidth - filled)
        val approxBytes = (fileSize * completed) / total
        print("\r[$bar] $percent% ($completed/$total chunks) ~$approxBytes bytes")
        if (completed == total) println() // new line when done
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

    companion object {
        /**
         * Creates a Downloader where chunk count is derived from a desired chunk size.
         * Example: 10MB file with 2MB chunks = 5 chunks
         */
        fun withChunkSize(
            httpClient: HttpClient,
            chunkSizeBytes: Long,
            showProgress: Boolean = true
        ): Downloader {
            // We don't know file size yet — chunk count will be calculated at download time
            return Downloader(httpClient, chunkSizeBytes = chunkSizeBytes, showProgress = showProgress)
        }
    }

}