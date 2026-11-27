plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.anymex.runtimehost.kotatsu"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    api(project(":backend-api"))
    api(project(":runtime-core"))
    // The Keiyoushi parsers are written against the Aniyomi API; the real classes
    // come from :backends:aniyomi, the parsers module only compiles against stubs.
    api(project(":backends:aniyomi"))
    api(project(":backends:kotatsu-parsers"))
    api(libs.androidx.appcompat)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    api(libs.okhttp)
    api(libs.okio)
    api(libs.jsoup)
    api(libs.kotlinx.serialization.json)
    compileOnly(libs.androidx.annotation)
}
