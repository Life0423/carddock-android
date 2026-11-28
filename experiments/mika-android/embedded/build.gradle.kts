plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 单 APK 复用原版宿主的生产源码；实验工具与独立应用的 debug 入口不进入产品。
android {
    namespace = "com.wangzan101.mika"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        buildConfigField("boolean", "EMBEDDED", "true")
        buildConfigField("String", "VERSION_NAME", "\"0.1.38\"")
        buildConfigField("String", "GIT_HASH", "\"5d3c7f9\"")
        buildConfigField("boolean", "BETA", "true")
    }
    sourceSets.getByName("main") {
        setRoot("../app/src/main")
        assets.srcDir("src/main/assets")
        jniLibs.srcDir("src/main/jniLibs")
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    implementation(libs.protobuf.lite)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.zxing.core)
}
