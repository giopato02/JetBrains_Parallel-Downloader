# JetBrains Parallel Downloader

A command-line file downloader written in **Kotlin** that downloads files in parallel chunks using HTTP Range requests. Built as part of the JetBrains internship application for the *Standalone Tool for Feature Usage Events Exploration* and *Interop of Reporting SDK with Python Clients* projects. Preferred project: **Standalone Tool for Feature Usage Events Exploration**.

## How It Works

Instead of downloading a file sequentially, the downloader:

1. Sends a `HEAD` request to get the total file size from `Content-Length`
2. Splits the file into N equal byte ranges
3. Downloads all chunks **simultaneously** using Kotlin Coroutines
4. Assembles the chunks in correct order into the final file

```
Thread 1: [======    ] bytes 0–1310719
Thread 2: [======    ] bytes 1310720–2621439    ← all running in parallel
Thread 3: [======    ] bytes 2621440–3932159
Thread 4: [======    ] bytes 3932160–5242879
...
```

Chunks arrive **out of order** but are always assembled correctly:
```
Starting chunk 4: bytes 5242880-6553599
Starting chunk 0: bytes 0-1310719             ← out of order = true parallelism
Starting chunk 6: bytes 7864320-9175039
Starting chunk 2: bytes 2621440-3932159
[==============================>] 100% (8/8 chunks) ~10485760 bytes
Download complete: output.bin
```

## Features

- **Parallel downloading** — all chunks download simultaneously using Kotlin Coroutines
- **Configurable chunk count** — specify how many chunks to split the file into
- **Configurable chunk size** — specify chunk size in MB or KB instead of count
- **Retry logic** — failed chunks are retried up to 3 times with a 1s delay
- **Live progress bar** — real-time progress shown in terminal
- **Binary file support** — works with any file type (text, images, zips, binaries)
- **Correct assembly** — chunks always written in correct order regardless of arrival order

## Tech Stack

| Technology | Purpose |
|---|---|
| Kotlin | Primary language |
| Kotlin Coroutines | Parallel chunk downloading |
| OkHttp | HTTP client (HEAD + Range GET requests) |
| MockWebServer | HTTP mocking for unit tests |
| JUnit 5 | Test framework |
| Gradle | Build system |

## Project Structure

```
src/
 ├── main/kotlin/com/giopato/
 │    ├── HttpClient.kt     # Interface abstraction over HTTP
 │    ├── Downloader.kt     # Core parallel download logic + retry + progress
 │    └── Main.kt           # CLI entry point + OkHttp implementation
 └── test/kotlin/com/giopato/
      └── DownloaderTest.kt # 13 unit tests
```

## Running the Downloader

### Prerequisites
- JDK 17+
- Docker (for local test server)

### Start a local test server

```bash
docker run --rm -p 8080:80 -v /path/to/your/files:/usr/local/apache2/htdocs/ httpd:latest
```

### Run the downloader

```bash
./gradlew run --args="<url> <outputFile> [chunkCount|chunkSize]"
```

**By chunk count:**
```bash
./gradlew run --args="http://localhost:8080/bigfile.bin output.bin 8"
```

**By chunk size in MB:**
```bash
./gradlew run --args="http://localhost:8080/bigfile.bin output.bin 2MB"
```

**By chunk size in KB:**
```bash
./gradlew run --args="http://localhost:8080/bigfile.bin output.bin 512KB"
```

**Output:**
```
Downloading: http://localhost:8080/bigfile.bin
Output:      /Users/giopato/output.bin
Chunk size:  2MB
File size: 10485760 bytes — splitting into 5 chunks
[==============================>] 100% (5/5 chunks) ~10485760 bytes
Download complete: /Users/giopato/output.bin
```

### Verify correctness

```bash
diff /path/to/original/file output.bin
```

No output = files are identical.

### Tested with a 10MB binary file

```bash
# Generate a 10MB test file
dd if=/dev/urandom of=/path/to/server/bigfile.bin bs=1M count=10

# Download it in parallel
./gradlew run --args="http://localhost:8080/bigfile.bin output.bin 8"

# Verify
diff /path/to/server/bigfile.bin output.bin
```

## Running Tests

```bash
./gradlew test
```

```
13 tests completed, 0 failed
BUILD SUCCESSFUL
```

### Test Coverage

| Test | What it verifies |
|---|---|
| `calculateRanges splits into correct number of chunks` | Correct chunk count |
| `calculateRanges last chunk ends at fileSize minus 1` | No byte is missed at the end |
| `calculateRanges chunks cover entire file without gaps` | No gaps between chunks |
| `calculateRanges works with single chunk` | Edge case: 1 chunk = full file |
| `calculateRanges throws on zero chunk count` | Invalid input handling |
| `calculateRanges throws on zero file size` | Invalid input handling |
| `download assembles chunks into correct file` | Full download correctness |
| `download creates output file` | File is written to disk |
| `download handles single chunk correctly` | Edge case: 1 chunk download |
| `download throws when server does not support range requests` | Server error handling |
| `download with MockWebServer returns correct content` | Real HTTP stack integration |
| `download retries failed chunk and succeeds` | Retry logic on network failure |
| `download with chunk size splits file correctly` | Configurable chunk size |
| `download handles binary content correctly` | Binary file correctness |

## Design Decisions

**Interface-based HttpClient** — The `HttpClient` interface decouples the download logic from the HTTP implementation. This makes the `Downloader` fully unit testable without real network calls — tests inject a `FakeHttpClient` instead of hitting a real server.

**Kotlin Coroutines** — Using `async/awaitAll` with `Dispatchers.IO` gives clean, readable parallel code without manual thread management. Each chunk runs on its own coroutine.

**Retry logic** — Each chunk is retried up to 3 times with a 1 second delay between attempts. Only the failing chunk is retried — other chunks are unaffected.

**Mutex for progress bar** — A `Mutex` synchronizes progress bar printing across coroutines, preventing garbled output from concurrent writes to stdout.

**Chunk assembly order** — Chunks are stored in a list indexed by position via `awaitAll()`, so even if they arrive out of order they are always written to the file in the correct sequence.

**Configurable chunk size** — Users can specify either a chunk count (`4`) or a chunk size (`2MB`, `512KB`). The downloader calculates the appropriate number of chunks automatically.

## Author

**Giorgi Pataridze**
- GitHub: [@giopato02](https://github.com/giopato02)
- Email: gpataridze@constructor.university