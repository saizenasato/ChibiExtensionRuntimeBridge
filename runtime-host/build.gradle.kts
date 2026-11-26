plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.anymex.runtimehost"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anymex.runtimehost"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0-chibi"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { freeCompilerArgs += "-Xcontext-receivers" }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation(project(":bridge-api"))
    implementation(project(":bridge-models"))
    implementation(project(":backend-api"))
    implementation(project(":runtime-core"))
    implementation(project(":runtime-loader"))
    implementation(project(":javascript-runtime"))
    implementation(project(":torrserver"))
    implementation(project(":backends:aniyomi"))
    implementation(project(":backends:cloudstream"))
    implementation(project(":backends:kotatsu"))
    implementation(project(":backends:legado"))
    implementation(project(":backends:ireader"))
    implementation(project(":backends:tsundoku"))
    implementation(project(":backends:lnreader"))
    implementation(project(":backends:sora"))
    implementation(project(":backends:mangayomi"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
}
