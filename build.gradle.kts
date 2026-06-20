plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "com.nebula"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}
