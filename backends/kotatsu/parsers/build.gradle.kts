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
    // Android supplies org.json at runtime; the pure-JVM build needs it for compile + tests.
    compileOnly(libs.org.json)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    api(libs.okhttp.doh)
    api(libs.okio)
    api(libs.jsoup)
    api(libs.androidx.annotation)
    api(libs.androidx.collection.ktx)
}

// The eu.kanade.tachiyomi classes in this tree are compile-time stand-ins for the
// Aniyomi API. :backends:aniyomi packages the real implementations, so keep the
// stand-ins out of the jar to avoid duplicate classes when both modules are dexed.
tasks.withType<Jar> {
    exclude("android/**", "androidx/**", "eu/kanade/**")
}
