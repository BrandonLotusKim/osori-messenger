plugins {
    kotlin("jvm") version "1.9.23"
    id("com.google.protobuf") version "0.9.4"
    application
}

group = "com.osori"
version = "0.0.1"

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.osori.client.test.MainKt")
}

dependencies {
    // Ktor 클라이언트 (WebSocket)
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")

    // Protobuf
    implementation("com.google.protobuf:protobuf-java:3.25.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // 로깅 (콘솔만)
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

sourceSets {
    main {
        proto {
            srcDir("../proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
}

kotlin {
    jvmToolchain(17)
}
