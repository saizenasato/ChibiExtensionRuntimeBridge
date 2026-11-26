plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.draken"
version = "1.0.3"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi"
        )
    }
}

// The parser library is a pure-JVM library; the Android-only helpers that upstream
// keeps in this tree are compiled by the :backends:kotatsu Android module instead.
sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    api(libs.okhttp.doh)
    api(libs.okio)
    api(libs.jsoup)
    api(libs.androidx.annotation)
    api(libs.androidx.collection.ktx)
}

tasks.withType<Jar> {
    exclude("android/**", "androidx/**")
}
