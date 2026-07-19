pluginManagement {
    val preferOfficialRepositories = System.getenv("CI").equals("true", ignoreCase = true)
    repositories {
        if (!preferOfficialRepositories) {
            maven("https://maven.aliyun.com/repository/google") {
                name = "AliyunGoogleMirror"
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven.aliyun.com/repository/public") {
                name = "AliyunPublicMirror"
            }
            maven("https://maven.aliyun.com/repository/gradle-plugin") {
                name = "AliyunGradlePluginMirror"
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val preferOfficialRepositories = System.getenv("CI").equals("true", ignoreCase = true)
    repositories {
        if (!preferOfficialRepositories) {
            maven("https://maven.aliyun.com/repository/google") {
                name = "AliyunGoogleMirror"
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven.aliyun.com/repository/public") {
                name = "AliyunPublicMirror"
            }
            maven("https://maven.aliyun.com/repository/gradle-plugin") {
                name = "AliyunGradlePluginMirror"
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "tina-build-logic"
include(":convention")
