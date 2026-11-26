plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.chibi.bridge.ipc"
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
    compileOnly(libs.androidx.annotation)
}
