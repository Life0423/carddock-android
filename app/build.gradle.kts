import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val squareEndpoint = providers.gradleProperty("carddockSquareEndpoint").orNull
require(squareEndpoint == null || URI(squareEndpoint).run {
    scheme == "https" && host != null && userInfo == null && query == null && fragment == null
}) { "carddockSquareEndpoint 必须是 HTTPS 服务地址" }

android {
    namespace = "com.life0423.carddock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.life0423.carddock"
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 35
        versionCode = providers.gradleProperty("carddockVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("carddockVersionName").orNull ?: "0.1.0-prototype"
        resValue("string", "square_endpoint", squareEndpoint ?: "")
    }

    signingConfigs {
        if (System.getenv("CARDDOCK_BETA_KEYSTORE") != null) create("internalBeta") {
            storeFile = file(System.getenv("CARDDOCK_BETA_KEYSTORE"))
            storePassword = System.getenv("CARDDOCK_BETA_PASSWORD")
            keyAlias = "carddock-beta"
            keyPassword = storePassword
        }
    }
    buildTypes {
        debug {
            // Keep the demo installation separate from a future production app.
            applicationIdSuffix = ".prototype"
            resValue("string", "square_endpoint", squareEndpoint ?: "http://127.0.0.1:8765")
        }
        create("beta") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("internalBeta")
            versionNameSuffix = "-beta.37"
            resValue("string", "app_name", "卡坞内测")
            matchingFallbacks += "release"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { jniLibs { useLegacyPackaging = true } }
    androidResources { noCompress += "xz" }

    buildFeatures {
        compose = true
        aidl = true
    }

    testBuildType = providers.gradleProperty("carddockTestBuildType").orElse("debug").get()
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.wangzan101.mika:embedded:0.1.38")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)
    testImplementation(testFixtures(project(":core")))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test)
}
