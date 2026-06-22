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
    implementation(project(":repository"))
    implementation(project(":proto"))
    implementation(project(":common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.spring.security.crypto)
    implementation(libs.kotlin.reflect)
    implementation(libs.protobuf.java)

    // Koin DI — 模块聚合需要 org.koin.core.module.Module 类型
    implementation(libs.koin.core)

    // Jakarta Persistence — OptimisticLockException 等异常类型
    implementation(libs.jakarta.persistence.api)

    // Lettuce Redis — 访问 Redis Repository
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.reactive)

    // kotlinx.serialization — 反序列化 Redis JSON 数据
    implementation(libs.kotlinx.serialization.json)

    // ─── 测试依赖 ───
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    // BCryptPasswordEncoder 运行时需要 commons-logging（spring-security-crypto 的传递依赖，
    // 需显式声明为 implementation 以确保运行时可见）
    implementation("commons-logging:commons-logging:1.3.2")

    // Testcontainers — Redis 集成测试容器
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    // Docker 29.x requires minimum API version 1.44, but docker-java defaults to 1.32
    // Ref: https://github.com/testcontainers/testcontainers-java/issues/11212
    systemProperty("api.version", "1.44")
}
