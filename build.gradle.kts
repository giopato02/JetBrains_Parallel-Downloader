plugins {
    kotlin("jvm") version "2.0.0"
    application
}

group = "com.giopato"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin coroutines for parallel downloading
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // OkHttp for HTTP requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Unit testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // MockWebServer for testing HTTP without a real server
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("com.giopato.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}