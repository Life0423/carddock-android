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

当前阶段不做空泛的「Agent」承诺；先把卡片空间与重组能力做扎实。

## 当前状态

项目处于**骨架阶段**：仓库刚建立，工程与界面仍在搭建。欢迎 Watch / Star 关注进度；正式可用版本尚未发布。

## 技术栈

| 项 | 说明 |
|---|---|
| 语言 | Kotlin（计划） |
| 平台 | Android 9.0（API 28）及以上 |
| 许可证 | Apache License 2.0 |

具体模块划分与依赖版本以工程落地后的 Gradle 配置为准。

## 如何构建

**【未验证】** 截至文档撰写时（2026-11-09，北京时间），[Life0423/carddock-android](https://github.com/Life0423/carddock-android) 尚无工程骨架与可运行的 Gradle 工程，因此这里**不写未跑通的构建命令**。

工程骨架合并后，本段将改为真实命令（例如 `./gradlew :app:assembleDebug`），并附上在本机实际跑通的输出证据。

本地开发环境预期（待与工程对齐后更新）：

- JDK（版本以 `build.gradle` / toolchain 为准）
- Android SDK，`minSdk` 不低于 28
- Android Studio 或命令行 Gradle

## 贡献

欢迎提 Issue 与 Pull Request。沟通默认使用中文，并请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 与仓库根目录的 [AGENTS.md](AGENTS.md)。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。
