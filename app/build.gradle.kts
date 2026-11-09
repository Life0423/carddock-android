// app 模块：卡坞的安装包。首版只有静态主屏，暂不拆模块。
// 不实现无障碍服务，也不依赖 Mika。
// minSdk 28 对应 Android 9。
// compileSdk / targetSdk 37 对应 Android 17，最低需要 AGP 9.1.1。
// https://developer.android.com/studio/releases#api-level-support
// 模板自带 AGP 9.0.1，兼容性表只到 compileSdk 36.1，所以插件用 9.5.1。
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.life0423.carddock"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.life0423.carddock"
    minSdk = 28
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  // AGP 9.5 配置 Compose Preview 任务时要求单测打包 Android 资源。
  // 保持默认 false 时，`./gradlew tasks` 会在创建 generateDebugComposePreviewRunfiles 失败。
  // JDK 17 跑 Robolectric 时必须打开这些内部包，否则换一台同样是 JDK 17 的机器，沙箱可能在创建阶段失败。
  // https://robolectric.org/getting-started/
  testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.all {
      it.jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
      )
    }
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)
  testImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // 本地单测直接组合真实的 MainScreen。Compose 界面在 JVM 上需要 Robolectric 提供资源环境。
  testImplementation(libs.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.robolectric)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
