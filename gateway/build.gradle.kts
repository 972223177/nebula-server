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

tasks.register<Test>("koinDiTest") {
    useJUnitPlatform {
        includeTags("koin-di")
    }
    forkEvery = 0
}

tasks.test {
    useJUnitPlatform {
        excludeTags("koin-di")
    }
    // 使用单 fork 执行，避免并行 fork 进程在全部测试通过后无法正常退出
    // （与 service/server 模块行为一致，确保 JVM 能干净关闭）
    maxParallelForks = 1
}

dependencies {
    implementation(project(":service"))
    implementation(project(":proto"))
    implementation(project(":common"))

    // Phase 4: Handler Framework
    implementation(libs.koin.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.serialization.json)

    // Phase 5: User Authentication — BCrypt 密码哈希（T-05-SC）
    implementation(libs.spring.security.crypto)

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
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.protobuf.java)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.lettuce.core)
    testImplementation(project(":repository"))
    testImplementation(project(":service"))
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
