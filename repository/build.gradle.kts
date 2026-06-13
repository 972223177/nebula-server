plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    api(project(":common"))
    implementation(project(":proto"))

    // JPA + Hibernate ORM (D-01)
    implementation(libs.hibernate.core)
    implementation(libs.spring.data.jpa)
    implementation(libs.spring.tx)

    // Kotlin 反射 — Spring Data JPA 需要用于 Kotlin 协程/挂起函数检测
    implementation(libs.kotlin.reflect)

    // 日志框架
    implementation(libs.kotlin.logging)

    // Lettuce Redis 客户端 (D-03) — 在此模块声明便于 03-02 使用
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    // kotlinx.serialization — PrivacyRepository 使用 JSON 序列化（D-09）
    implementation(libs.kotlinx.serialization.json)

    // Flyway 数据库迁移 (D-02)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(kotlin("stdlib"))

    // Koin DI — 模块初始化器需要 KoinComponent 和 Koin API
    implementation(libs.koin.core)

    // Test
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
repositories {
    mavenCentral()
}
allOpen {
    annotation("javax.persistence.Entity")
    annotation("javax.persistence.Embeddable")
    annotation("javax.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}
