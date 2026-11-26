plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lagradost.cloudstream3.runtime"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { freeCompilerArgs += "-Xcontext-receivers" }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    api(project(":backend-api"))
    api(project(":runtime-core"))
    api(libs.androidx.appcompat)
    api(libs.androidx.preference)
    api(libs.androidx.core.ktx)
    api(libs.androidx.compose.runtime)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    api(libs.okhttp)
    api(libs.okhttp.logging)
    api(libs.okhttp.doh)
    api(libs.okhttp.brotli)
    api(libs.okhttp.zstd)
    api(libs.okio)
    api(libs.jsoup)
    api(libs.injekt)
    api(libs.kotlinx.serialization.json)
    api(libs.gson)
    api(libs.jackson.databind)
    api(libs.jackson.kotlin)
    api(libs.rxjava)
    api(libs.rxandroid)
    api(libs.newpipe)
    api(libs.retrofit)
    api(libs.retrofit.jackson)
    api(libs.tmdb)
    api(libs.fuzzywuzzy)
    api(libs.nicehttp)
    api(libs.rhino)
    implementation(kotlin("reflect"))
    compileOnly(libs.androidx.annotation)
}

configurations.all {
    resolutionStrategy {
        force("com.squareup.okhttp3:okhttp:${libs.versions.okhttp.get()}")
        force("com.squareup.okhttp3:logging-interceptor:${libs.versions.okhttp.get()}")
        force("com.squareup.okhttp3:okhttp-dnsoverhttps:${libs.versions.okhttp.get()}")
        force("com.squareup.okhttp3:okhttp-brotli:${libs.versions.okhttp.get()}")
        force("com.squareup.okhttp3:okhttp-zstd:${libs.versions.okhttp.get()}")
        force("com.squareup.okio:okio:${libs.versions.okio.get()}")
        force("com.google.code.gson:gson:${libs.versions.gson.get()}")
    }
}
