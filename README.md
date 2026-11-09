<p align="center">
  <img src="assets/brand/readme-banner.png" alt="卡坞 CardDock 横幅" width="100%">
</p>

# 卡坞 · CardDock

把散落在各个 App 的功能，重组成属于你自己的卡片空间。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-9.0%2B-brightgreen.svg)](https://developer.android.com/)
[![GitHub](https://img.shields.io/badge/GitHub-Life0423%2Fcarddock--android-181717?logo=github)](https://github.com/Life0423/carddock-android)

开源、不上架、不收费。目标是把常用能力从各自 App 里抽出来，按你自己的方式摆成卡片，减少在多个应用之间来回翻找。

## 它解决什么

- **跨 App 找功能**：常用操作分散在不同应用里，打开路径长、记不住。卡坞希望把这些入口收到一处，按卡片组织。
- **中断后续办**：做事常被打断。把「下一步要做的事」落成卡片，回来时能接着办，而不是重新在各个 App 里找一遍。

目标是由 Agent 记住你常用的功能和中断的地方，帮你直达功能、接着办完。

## 当前状态

项目处于**骨架阶段**：仓库刚建立，工程与界面仍在搭建。欢迎 Watch / Star 关注进度；正式可用版本尚未发布。

## 技术栈

| 项 | 说明 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose |
| 平台 | Android 9.0（API 28）及以上；compileSdk / targetSdk 37 |
| 构建 | Android Gradle Plugin 9.5.1，Gradle 9.6.1 |
| JDK | 17（`jvmToolchain`） |
| 许可证 | Apache License 2.0 |

具体模块划分与其余依赖版本以 Gradle 配置为准。

## 如何构建

环境要求：

- JDK 17
- Android SDK，compileSdk / targetSdk 37，minSdk 28

克隆仓库后，在根目录构建并跑单测：

```bash
git clone https://github.com/Life0423/carddock-android.git
cd carddock-android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Debug APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

## 贡献

欢迎提 Issue 与 Pull Request。沟通默认使用中文，并请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 与仓库根目录的 [AGENTS.md](AGENTS.md)。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。
