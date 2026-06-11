plugins {
    kotlin("jvm")
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
}
