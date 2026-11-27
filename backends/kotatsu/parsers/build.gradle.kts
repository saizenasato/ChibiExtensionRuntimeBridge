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
    // Compile-time stand-ins: android/androidx stubs plus the Aniyomi API subset the
    // Keiyoushi parsers are written against. :backends:aniyomi packages the real
    // Aniyomi classes, so these must never be published by this module.
    create("shim")
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
    // Keiyoushi parsers use the real RxJava 1 and Injekt; no local copies ship.
    compileOnly(libs.rxjava)
    compileOnly(libs.injekt)

    "shimImplementation"(libs.kotlinx.coroutines.core)
    "shimImplementation"(libs.okhttp)
    "shimImplementation"(libs.jsoup)
    "shimImplementation"(libs.rxjava)
    "shimCompileOnly"(libs.org.json)

    compileOnly(sourceSets["shim"].output)
    testImplementation(sourceSets["shim"].output)
}

// Belt and braces: nothing from the stand-ins may reach consumers.
tasks.withType<Jar> {
    exclude("android/**", "androidx/**", "eu/kanade/**")
}
