plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":proto"))
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Phase 2 新增 — 基础设施依赖
    implementation(libs.typesafe.config)
    implementation(libs.hikaricp)
    implementation(libs.mysql.connector)
    implementation(libs.grpc.netty.shaded)

    // Koin DI — 模块初始化器需要 KoinComponent 和 Koin API
    implementation(libs.koin.core)
}
