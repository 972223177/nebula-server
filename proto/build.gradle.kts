import com.google.protobuf.gradle.*

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.protobuf")
}

dependencies {
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)
    implementation("javax.annotation:javax.annotation-api:1.3.2")
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
