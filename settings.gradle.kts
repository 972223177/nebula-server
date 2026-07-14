pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    @Suppress("UnstableApiUsage")
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "nebula-server"

include(
    ":proto",
    ":common",
    ":repository",
    ":service",
    ":gateway",
    ":server"
)
