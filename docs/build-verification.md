# CardDock 0.1.0-prototype · 交付验证

验证日期：2026-11-09。工程为原生 Android / Kotlin / Jetpack Compose 原型。

## 实际执行

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

最终 Gradle 构建成功。共 **45 个测试通过，0 失败，0 错误，0 跳过**。

| 测试类 | 测试数 | 结果 |
| --- | ---: | --- |
| `MockRuleCompilerTest` | 4 | 通过 |
| `AtomicFileCardStoreTest` | 6 | 通过 |
| `DemoDeviceGatewayTest` | 3 | 通过 |
| `CardDockUiTest` | 3 | 通过 |
| `MainActivitySmokeTest` | 1 | 通过 |
| `RuleEngineTest` | 12 | 通过 |
| `RuleSerializationTest` | 3 | 通过 |
| `PrototypeControllerTest` | 13 | 通过 |

核心包括规则引擎、序列化、会话状态、取消、并发串行化、预览保存门禁和旧结果保留。Android 测试包括编译器、模拟设备、原子文件持久化、Compose 交互，以及通过真实 Application / MainActivity / AppContainer 刷新得到核验结果的入口测试。

## 静态检查

Android Lint：**0 错误、2 警告**。两条均为 lifecycle 依赖存在更新版本的提示；本原型保留已完整验证的固定版本组合，没有屏蔽检查。

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

本轮完成 JVM 核心测试、Robolectric Android 测试、Native Graphics 页面渲染、Lint 与 APK 构建。未在物理手机上安装验收；未执行真实第三方应用、无障碍服务、真实 AI 或后台任务接入；未在用户 GitHub 仓库运行 CI 或合并 PR。

首次启动预置两张尚未刷新的卡片；按 README 的体验路径刷新和创建即可。
