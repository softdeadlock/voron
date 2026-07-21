plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.noise.java)
    implementation(libs.kotlinx.coroutines.core)
    // ML-KEM (FIPS 203) for the post-quantum hybrid X3DH handshake -- no JDK-provided
    // implementation exists yet, unlike the classical Curve25519/Ed25519/ChaCha20-Poly1305
    // primitives used everywhere else in e2ee/.
    implementation(libs.bouncycastle.provider)

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
