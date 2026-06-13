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
    api(project(":repository"))
    api(project(":proto"))
    implementation(project(":common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.spring.security.crypto)
    implementation(libs.kotlin.reflect)
    implementation(libs.protobuf.java)

    // Spring Data JPA — 访问 Repository 接口（JpaRepository 超类）
    implementation(libs.spring.data.jpa)
    implementation(libs.spring.tx)

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
}

tasks.test {
    useJUnitPlatform()
}
