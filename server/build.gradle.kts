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
    mainClass.set("com.osori.server.app.MainKt")
}

// 운영자 비밀번호 재설정 CLI 태스크
tasks.register<JavaExec>("passwordReset") {
    group = "application"
    description = "운영자용 비밀번호 강제 재설정 — args=\"<userId> <newPassword>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.osori.server.auth.PasswordResetCliKt")
}

// 운영자 감사 로그 검색 CLI
tasks.register<JavaExec>("auditSearch") {
    group = "application"
    description = "운영자용 감사 로그 검색 — args=\"<event_type> [limit]\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.osori.server.audit.AuditSearchCliKt")
}

// 운영자 display_name 변경 CLI
tasks.register<JavaExec>("setDisplayName") {
    group = "application"
    description = "운영자용 표시명 변경 — args=\"<userId> <newDisplayName>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.osori.server.auth.SetDisplayNameCliKt")
}

// 2026-09-15 사용자 요구사항 — 허용 계정(admin/test1~10) 시딩, 비밀번호 전부 "1234"
tasks.register<JavaExec>("seedAllowedAccounts") {
    group = "application"
    description = "허용 계정(admin/test1~10) 생성 + 비밀번호 \"1234\" 로 재설정"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.osori.server.auth.SeedAllowedAccountsCliKt")
}

// 2026-09-17 사용자 요구사항 — 그룹방 기능 이전 옛날 1:1 대화(talk_messages)를 전부 Room 으로 이전
tasks.register<JavaExec>("migrateLegacyTalks") {
    group = "application"
    description = "레거시 1:1 대화(talk_messages)를 전부 대화방(Room) 시스템으로 이전"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.osori.server.migration.MigrateLegacyTalksCliKt")
}

dependencies {
    // Ktor 서버
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-websockets-jvm:2.3.12")
    implementation("io.ktor:ktor-server-call-logging-jvm:2.3.12")

    // 로깅
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    //: logback `<if>` 조건부 어펜더 활성화용 — 파일 로그 enable 토글
    implementation("org.codehaus.janino:janino:3.1.10")

    // Protobuf (Java 출력을 Kotlin에서 그대로 사용)
    implementation("com.google.protobuf:protobuf-java:3.25.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // DB — HikariCP 풀 + PostgreSQL JDBC
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.3")

    // 인증 — Argon2id 비밀번호 해시
    implementation("de.mkammerer:argon2-jvm:2.11")

    // 테스트
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.23")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.12")
    testImplementation("io.ktor:ktor-client-cio-jvm:2.3.12")
    testImplementation("io.ktor:ktor-client-websockets-jvm:2.3.12")
    testImplementation("io.mockk:mockk:1.13.10")
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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.named<Test>("test") {
    useJUnit()
}
