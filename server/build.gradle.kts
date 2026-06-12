plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.nebula.server.NebulaServerKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":gateway"))
    implementation(project(":proto"))

    // Phase 2 新增
    implementation(project(":common"))
    implementation(libs.typesafe.config)
    implementation(libs.kotlin.logging)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.services)
    implementation(libs.grpc.api)
    implementation(libs.netty.tcnative)

    // Phase 3 新增 — 持久化层
    implementation(project(":repository"))
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hibernate.core)

    // Phase 4: Handler Framework — Koin 启动
    implementation(libs.koin.core)
}
