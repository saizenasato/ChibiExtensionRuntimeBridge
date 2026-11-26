plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.chibi.bridge.backend"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":bridge-models"))
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    api(libs.jsoup)
    compileOnly(libs.androidx.annotation)
}
