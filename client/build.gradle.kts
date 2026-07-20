plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("messenger.client.MainKt")
}

dependencies {
    implementation(project(":common"))

    // Desktop harness picks the CIO client engine; common only brings the
    // engine-agnostic client-core/websockets API.
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    // Serves the local test-UI page for this client instance.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)

    testImplementation(project(":server"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}

// Lets the standalone exploit PoCs under exploit/ (not part of the app, never referenced by
// Main.kt) be run individually: `./gradlew :client:runExploit -PmainClass=<FQCN>`.
tasks.register<JavaExec>("runExploit") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(providers.gradleProperty("mainClass"))
}
