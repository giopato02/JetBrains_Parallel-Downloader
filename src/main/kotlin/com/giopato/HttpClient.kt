package com.giopato

/**
 * Abstraction over HTTP operations.
 * Using an interface makes the downloader fully testable — in tests we swap
 * this out for a fake implementation without ever touching a real network.
 */
interface HttpClient {
    /**
     * Fetches the total size of the file at [url] in bytes.
     * Sends a HEAD request and reads the Content-Length header.
     * Returns null if the server doesn't support range requests.
     */
    fun getFileSize(url: String): Long?

    /**
     * Downloads a specific byte range of the file at [url].
     * Sends a GET request with the Range header set to "bytes=from-to".
     * Returns the raw bytes of that chunk.
     */
    fun downloadChunk(url: String, from: Long, to: Long): ByteArray
}