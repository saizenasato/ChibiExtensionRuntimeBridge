pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "ChibiExtensionRuntimeBridge"

include(
    ":bridge-models",
    ":bridge-api",
    ":backend-api",
    ":runtime-core",
    ":runtime-loader",
    ":runtime-host",
    ":javascript-runtime",
    ":torrserver",
    ":bridge-sdk",
    ":sample-host",
    ":backends:aniyomi",
    ":backends:cloudstream",
    ":backends:kotatsu",
    ":backends:kotatsu-parsers",
    ":backends:legado",
    ":backends:ireader",
    ":backends:tsundoku",
    ":backends:lnreader",
    ":backends:sora",
    ":backends:mangayomi",
)

project(":backends:kotatsu-parsers").projectDir = file("backends/kotatsu/parsers")
