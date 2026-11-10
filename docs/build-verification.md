# CardDock 0.1.0-prototype · 交付验证

工程为原生 Android / Kotlin / Jetpack Compose 原型。2026-11-09 的交付记录保留在下方的构建组合、APK 与截图。测试总数和 Lint 计数于 2026-11-10（北京时间）按 main `c0a2248` 重数，来源写在对应小节。

## 实际执行

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

2026-11-10 19:30（北京时间）在本机用 JDK 17、Android SDK Platform 35 跑通上述命令。`:core:test` 46 个，`:app:testDebugUnitTest` 26 个，合计 **72 个测试通过，0 失败，0 跳过**。同一提交的 GitHub Actions run [42805389493](https://github.com/Life0423/carddock-android/actions/runs/42805389493)（2026-11-10 19:13 北京时间完成）里 `verification-reports` 的分类计数与此一致。2026-11-09 记录的 45 个没有计入后来加入的 `FailurePathsTest`、`FixroundBoundaryTest`、`AtomicFailureTest`、`FixroundCancelledCommitTest`。

| 测试类 | 测试数 | 结果 |
| --- | ---: | --- |
| `MockRuleCompilerTest` | 4 | 通过 |
| `AtomicFailureTest` | 8 | 通过 |
| `AtomicFileCardStoreTest` | 6 | 通过 |
| `FixroundCancelledCommitTest` | 1 | 通过 |
| `DemoDeviceGatewayTest` | 3 | 通过 |
| `CardDockUiTest` | 3 | 通过 |
| `MainActivitySmokeTest` | 1 | 通过 |
| `RuleEngineTest` | 12 | 通过 |
| `RuleSerializationTest` | 3 | 通过 |
| `FailurePathsTest` | 14 | 通过 |
| `FixroundBoundaryTest` | 4 | 通过 |
| `PrototypeControllerTest` | 13 | 通过 |

核心包括规则引擎、序列化、会话状态、取消、并发串行化、预览保存门禁和旧结果保留。Android 测试包括编译器、模拟设备、原子文件持久化、Compose 交互，以及通过真实 Application / MainActivity / AppContainer 刷新得到核验结果的入口测试。

## 静态检查

任务是 `:app:lintDebug`。`core` 是纯 Kotlin/JVM 模块，没有 Android Lint 任务。检查没有被屏蔽，也没有为了消掉警告而改依赖版本。

| 来源 | 时间（北京时间） | errors | warnings |
| --- | --- | ---: | ---: |
| 本地 `./gradlew :app:lintDebug`（JDK 17.0.21；SDK 仅 `platforms;android-35`、`build-tools;34.0.0`、`build-tools;35.0.0`） | 2026-11-10 19:28 | 0 | 18 |
| CI run [42805389493](https://github.com/Life0423/carddock-android/actions/runs/42805389493) 的 `verification-reports`（`lint-results-debug.txt`，lint 8.7.3） | 2026-11-10 19:13 | 0 | 19 |

两边都不是原先写的「0 错误、2 条 lifecycle 警告」。共同的 18 条都是 Warning，同一条文案各出现 3 次：`AndroidGradlePluginVersion`（AGP 8.7.3，提示 9.5.1），以及 `GradleDependency`（compose-bom 2024.12.01、activity-compose 1.9.3、lifecycle-runtime-compose 2.8.7、lifecycle-viewmodel-compose 2.8.7、core-ktx 1.15.0）。CI 多出的 1 条是 `OldTargetApi`，指向 `app/build.gradle.kts` 的 `targetSdk = 35`。本地最高平台就是 35，没有这条；CI 使用 GitHub 托管镜像里预装的 SDK（该 run 日志中的 `/usr/local/lib/android/sdk`），因此多出这一条。

## 构建组合

- JDK 17
- Gradle Wrapper 8.9（含发行包 SHA-256）
- Android Gradle Plugin 8.7.3
- Kotlin 2.1.10
- Compose BOM 2024.12.01
- compileSdk / targetSdk 35；minSdk 26
- Robolectric 4.14.1；界面测试使用 API 35 Native Graphics，存储测试使用 API 28

## APK

- 文件：`CardDock-0.1.0-prototype.apk`
- 版本：`0.1.0-prototype` / versionCode 1
- 安装包名：`com.life0423.carddock.prototype`
- 桌面名称：卡坞 · 原型
- 大小：18,715,692 字节
- SHA-256：`2ee7c4aa7ccde2f78b7874b7d3f449ef9f2dfcfe76f63b7169fda01451a3f292`

这是用于本地体验的调试签名 APK，不是应用商店发行包。

## 界面渲染

`docs/screenshots/` 包含 4 张经目视检查的 360dp 宽度、1080 × 2400 像素 Android / Compose 渲染：

- `01-space.png`：日常空间（固定测试样例）。
- `03-create-preview.png`：试运行结果与保存（固定测试样例）。
- `04-profile.png`：演示场景设置（固定测试样例）。
- `05-real-app-verified.png`：真实入口、模拟网关及规则引擎运行后的页面。

测试样例用来稳定检验界面，不是来自真实用户、真实账号或第三方应用的数据。

## 验证边界

2026-11-09 的交付完成了 JVM 核心测试、Robolectric Android 测试、Native Graphics 页面渲染、Lint 与 APK 构建。2026-11-10 在 main `c0a2248` 上重跑了同一条 Gradle 命令，并对照了 GitHub Actions run 42805389493。仍未在物理手机上安装验收，也未执行真实第三方应用、无障碍服务、真实 AI 或后台任务接入。

首次启动预置两张尚未刷新的卡片；按 README 的体验路径刷新和创建即可。
