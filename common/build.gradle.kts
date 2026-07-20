plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.noise.java)
    implementation(libs.kotlinx.coroutines.core)

    // MessengerClient is transport/HTTP-engine agnostic; callers (desktop
    // harness, Android app) supply their own HttpClient with an engine and
    // the WebSockets plugin installed.
    api(libs.ktor.client.core)
    api(libs.ktor.client.websockets)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
