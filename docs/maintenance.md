# 维护与接入

## 修改界面

颜色在 `app/src/main/kotlin/com/life0423/carddock/ui/theme/CardDockTheme.kt` 的 `DockColors`。`CardDockTheme` 使用 Material3 默认 `Typography()`，没有单独的字号表。间距写在各个 Composable 里（例如 `FunctionCard` 的 `padding(18.dp)`），不在主题文件中。

`FunctionCard` 在 `app/src/main/kotlin/com/life0423/carddock/ui/FunctionCard.kt`。`SnapshotFields` 和 `StatusBadge` 在 `app/src/main/kotlin/com/life0423/carddock/ui/UiComponents.kt`，都是 `internal`，只在 app 模块内复用。页面只消费 `com.life0423.carddock.core.session.PrototypeState`，并调用同文件的 `PrototypeActions`。

不要在 Composable 的函数体、状态收集回调或页面切换中启动读取。通过明确按钮事件调用动作，避免重组造成重复执行。

## 修改演示内容

1. 在 `app/src/main/kotlin/com/life0423/carddock/demo/TemplateCatalog.kt` 的 `TemplateCatalog` 中调整规则和字段标签。
2. 在 `app/src/main/kotlin/com/life0423/carddock/demo/DemoDeviceGateway.kt` 中调整对应固定对象的字段。场景是 `com.life0423.carddock.core.session.DemoScenario`：`NORMAL`、`NEEDS_USER`、`READ_FAILURE`、`WRONG_TARGET`。
3. 在 `app/src/main/kotlin/com/life0423/carddock/ai/MockRuleCompiler.kt` 中维护受支持的关键词。
4. 更新该类场景的测试，确认字段缺失、目标错配会失败。

模板中 `sourceId` 和 `targetKey` 必须对应实际模拟对象。设备响应返回自身观察到的目标，不能原样回显请求的目标来绕过核验。

## 接入真实需求编译器

`RuleCompiler` 在 `core/src/main/kotlin/com/life0423/carddock/core/port/RuleCompiler.kt`。新实现放在 `app/src/main/kotlin/com/life0423/carddock/ai/`，并在 `app/src/main/kotlin/com/life0423/carddock/AppContainer.kt` 替换现在的 `MockRuleCompiler`。返回值只有 `CompileResult.Success` 和 `CompileResult.Unsupported`，不能把任意代码或脚本作为可执行结果。

不符合规则或匹配不到模板时，返回 `CompileResult.Unsupported`。`RuleValidator`（`core/src/main/kotlin/com/life0423/carddock/core/rule/RuleValidator.kt`）仍会在读取设备之前拒绝未知 `schemaVersion`。API 密钥不应放进 Android 源码或 APK；真实服务接入和凭据架构需要单独设计。

## 接入真实设备

`DeviceGateway.inspect` 返回 `DeviceResponse` 的 `Ready`、`NeedsUser` 或 `Failed`，定义在 `core/src/main/kotlin/com/life0423/carddock/core/port/DeviceGateway.kt`。`DeviceSnapshot` 必须带上观察到的 `sourceId`、`targetKey` 和 `fields`；无法可靠识别目标时不要返回 `Ready`。

当前适配器是 `app/src/main/kotlin/com/life0423/carddock/demo/DemoDeviceGateway.kt`。新的平台实现放在 `app/src/main/kotlin/com/life0423/carddock/` 下已经有代码的包里（现在是 `ai`、`data`、`demo`、`ui`）。真正涉及权限、无障碍服务和页面交互时才增加相应组件，并保留中断、取消、错误对象与失败不覆盖旧结果的测试。

当前协议只支持读取。如果需要点击或多阶段任务，应先设计动作的确认、取消点、幂等性与恢复规则，再扩展核心协议。

## 替换持久化

实现 `core/src/main/kotlin/com/life0423/carddock/core/port/CardStore.kt` 即可更换存储。保持三个约定：

- `load()` 返回 `null` 只代表首次使用；空列表代表用户选择保留空空间。
- `save(cards)` 完整替换集合；失败抛出异常，不能报告部分成功。
- 不会静默丢弃无法识别的内容；升级时显式迁移。

现用实现是 `app/src/main/kotlin/com/life0423/carddock/data/AtomicFileCardStore.kt`。文件在应用私有目录，名为 `cards-v1.json`。JSON 字段是 `version`（`STORAGE_VERSION = 1`），不是一份叫 `schemaVersion` 的文件头。规则自身的版本是 `RuleSpec.schemaVersion`，`RuleValidator.SUPPORTED_SCHEMA_VERSION` 目前为 1。存储版本或规则版本不匹配时直接失败，没有历史迁移。增加字段时同时考虑 `@Serializable` 默认值与旧文件兼容测试。`AppContainer` 构造 `Json` 时设置了 `ignoreUnknownKeys = false`。

## 在本仓库继续开发

这个原型已经在仓库 `main` 上，不是还要迁入的外部工程。模块只有 `settings.gradle.kts` 里的 `:app` 和 `:core`。包名根是 `com.life0423.carddock`。`app/build.gradle.kts` 中 `applicationId` 为 `com.life0423.carddock`，debug 增加后缀 `.prototype`，`versionName` 为 `0.1.0-prototype`，`versionCode` 为 1，`minSdk` 为 26，`compileSdk` / `targetSdk` 为 35。

从最新 `main` 拉分支。仓库没有 `scripts/` 目录。验证命令与 `.github/workflows/android.yml` 的 `verify` 任务相同：

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

工作流使用 JDK 17，并安装 `platforms;android-35`、`build-tools;34.0.0` 和 `build-tools;35.0.0`。本地把 `JAVA_HOME` 指到 JDK 17，用 `ANDROID_HOME` 或未入库的 `local.properties` 中的 `sdk.dir` 指向 SDK。

提交前确认 diff 里没有 `.gradle/`、`**/build/`、`local.properties`、`*.jks`、`*.keystore`、`keystore.properties`、`signing.properties`、`google-services.json`、`*.apk`、`*.aab`。

## 代码约定

- 一个文件聚焦一种职责；不为单次调用增加空接口或继承层。
- 新能力先确定业务接口，再添加平台实现。
- 把时间、ID、设备和存储作为依赖注入，测试不依赖真实账号或当前时钟。
- 保留 `CancellationException`，不要用宽泛异常处理吞掉取消。
- 新增测试针对可观察行为，例如重复点击只执行一次、失败保留旧结果。
- 新增包只为实际代码创建，不使用空目录占位。
