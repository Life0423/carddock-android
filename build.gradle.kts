// 根构建脚本只声明插件版本，不在根工程应用。
// 具体的 Android 配置放在 app 模块，避免根工程被当成要编译的 Android 模块。
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
}
