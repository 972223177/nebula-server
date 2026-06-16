plugins {
    alias(libs.plugins.kotlin.jvm)
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

    // Phase 3 新增 — 持久化层（测试依赖，生产代码通过 gateway 间接访问）
    testImplementation(project(":repository"))
    testImplementation(project(":service"))
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hibernate.core)
    implementation(libs.spring.tx)
    implementation(libs.hikaricp)
    implementation(libs.spring.data.jpa)

    // Phase 4: Handler Framework — Koin 启动
    implementation(libs.koin.core)

    // Phase 6: Koin 验证测试
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
