# JetBrains Parallel Downloader

A command-line file downloader written in **Kotlin** that downloads files in parallel chunks using HTTP Range requests. Built as part of the JetBrains internship application for the *Standalone Tool for Feature Usage Events Exploration* project.

## How It Works

Instead of downloading a file sequentially, the downloader:

1. Sends a `HEAD` request to get the total file size from `Content-Length`
2. Splits the file into N equal byte ranges
3. Downloads all chunks **simultaneously** using Kotlin Coroutines
4. Assembles the chunks in correct order into the final file

```
Thread 1: [======    ] bytes 0–34
Thread 2: [======    ] bytes 35–69    ← all running in parallel
Thread 3: [======    ] bytes 70–104
Thread 4: [======    ] bytes 105–141
```

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
 │    ├── Downloader.kt     # Core parallel download logic
 │    └── Main.kt           # CLI entry point + OkHttp implementation
 └── test/kotlin/com/giopato/
      └── DownloaderTest.kt # 11 unit tests
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
./gradlew run --args="<url> <outputFile> [chunkCount]"
```

**Example:**
```bash
./gradlew run --args="http://localhost:8080/testfile.txt output.txt 4"
```

**Output:**
```
Downloading: http://localhost:8080/testfile.txt
Output:      /Users/giopato/output.txt
Chunks:      4
File size: 142 bytes — splitting into 4 chunks
Downloading chunk 1: bytes 35-69
Downloading chunk 3: bytes 105-141
Downloading chunk 0: bytes 0-34
Downloading chunk 2: bytes 70-104
Chunk 0 complete (35 bytes)
Chunk 3 complete (37 bytes)
Chunk 2 complete (35 bytes)
Chunk 1 complete (35 bytes)
Download complete: /Users/giopato/output.txt
```

Notice chunks download **out of order** — proving true parallelism.

### Verify correctness

```bash
diff /path/to/original/file output.txt
```

No output = files are identical

## Running Tests

```bash
./gradlew test
```

```
11 tests completed, 0 failed
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

## Design Decisions

**Interface-based HttpClient** — The `HttpClient` interface decouples the download logic from the HTTP implementation. This makes the `Downloader` fully unit testable without real network calls — tests inject a `FakeHttpClient` instead.

**Kotlin Coroutines** — Using `async/awaitAll` with `Dispatchers.IO` gives clean, readable parallel code without manual thread management.

**Chunk assembly order** — Chunks are stored in a list indexed by position, so even if they arrive out of order they are always written to the file in the correct sequence.

## Author

**Giorgi Pataridze**
- GitHub: [@giopato02](https://github.com/giopato02)
- Email: gpataridze@constructor.university
