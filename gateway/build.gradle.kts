plugins {
    alias(libs.plugins.kotlin.jvm)
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
    maxHeapSize = "4g"
    maxParallelForks = 1
    // 禁用 fork 复用，每个测试类独立 JVM
    forkEvery = 1
}

dependencies {
    implementation(project(":service"))
    implementation(project(":proto"))
    implementation(project(":common"))

    // Phase 4: Handler Framework
    implementation(libs.koin.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.serialization.json)

    // Phase 5: User Authentication — BCrypt 密码哈希（T-05-SC）
    implementation(libs.spring.security.crypto)

    // Phase 5: UserRepository 编译时依赖（repository 模块只以 implementation 导出 spring-data-jpa）
    implementation(libs.spring.data.jpa)

    // Phase 6: JPA EntityManagerFactory — 手动事务管理（D-09）
    implementation(libs.jakarta.persistence.api)

    // Phase 5: ChatService gRPC 双向流服务
    implementation(libs.grpc.api)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)

    // Phase 6: Chat & Message — Redis 直接操作（DedupStep Redis SETNX、WriteStep 会话元更新）
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.reactive)

    // Test
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.protobuf.java)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.lettuce.core)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
