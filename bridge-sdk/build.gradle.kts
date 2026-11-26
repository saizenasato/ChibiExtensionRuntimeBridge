plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.chibi.bridge.sdk"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":bridge-models"))
    api(project(":bridge-api"))
    api(project(":runtime-loader"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    compileOnly(libs.androidx.annotation)
    testImplementation(libs.junit)
}
