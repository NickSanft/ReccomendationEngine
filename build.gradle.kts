plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

group = "com.recengine"
version = "0.1.0"

application {
    mainClass.set("com.recengine.ApplicationKt")
    applicationDefaultJvmArgs = listOf(
        "-Xmx2g",
        "-XX:+UseG1GC",
        "-Dfile.encoding=UTF-8"
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_23)
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "23"
    targetCompatibility = "23"
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content)
    implementation(libs.ktor.serialization)
    implementation(libs.ktor.server.metrics)
    implementation(libs.ktor.server.status)
    implementation(libs.ktor.server.sse)

    // Coroutines + Serialization
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.jdk8)
    implementation(libs.coroutines.reactive)
    implementation(libs.serialization.json)

    // Redis
    implementation(libs.lettuce.core)

    // Kafka
    implementation(libs.kafka.clients)

    // DI
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger)

    // Metrics + Logging + Config
    implementation(libs.micrometer.prometheus)
    implementation(libs.logback.classic)
    implementation(libs.janino)
    implementation(libs.kotlin.logging)
    implementation(libs.typesafe.config)

    // Test
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    // net.bytebuddy.experimental=true: lets MockK/Byte Buddy proxy classes compiled for
    // JDK 23+ (class format 67+) even though this Byte Buddy version officially targets JDK 21.
    jvmArgs("-Xmx1g", "-Dnet.bytebuddy.experimental=true")
}
