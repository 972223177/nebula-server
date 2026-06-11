import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)
    implementation(libs.javax.annotation.api)
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                maybeCreate("java")
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    sourceSets {
        main {
            java.srcDir("build/generated/source/proto/main/java")
        }
    }
}
