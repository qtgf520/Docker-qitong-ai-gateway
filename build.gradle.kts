import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
}

group = "com.qitong.gateway"
version = "3.18.22"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server (Netty) — 生产用
    implementation("io.ktor:ktor-server-netty:3.1.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.0")
    implementation("io.ktor:ktor-server-call-logging:3.1.0")
    implementation("io.ktor:ktor-server-websockets:3.1.0")
    implementation("io.ktor:ktor-server-cors:3.1.0")
    implementation("io.ktor:ktor-server-status-pages:3.1.0")
    implementation("io.ktor:ktor-server-default-headers:3.1.0")
    implementation("io.ktor:ktor-server-forwarded-header:3.1.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Kotlinx Serialization + Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // SQLite (JDBC) — 代替 Room，纯JVM
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")

    // BCrypt 密码哈希
    implementation("org.mindrot:jbcrypt:0.4")

    // 日志
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // 测试
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.qitong.gateway.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.qitong.gateway.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

// 专为 Docker 多阶段构建的最小发行版
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("qitong-gateway")
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.qitong.gateway.MainKt"
        attributes["Implementation-Version"] = project.version
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}