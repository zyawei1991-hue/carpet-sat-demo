rootProject.name = "carpet-sat-demo"

pluginManagement {
    repositories {
        // 国内镜像放前面，直连 repo1/maven central 在这台机器上太慢
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

include(":core")
include(":app")
project(":app").projectDir = file("apps/android")
