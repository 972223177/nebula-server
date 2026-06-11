plugins {
    kotlin("jvm")
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

dependencies {
    implementation(project(":gateway"))
    implementation(project(":proto"))
}
