# CardDock 真机实验原型

`mika-android/` 是独立的 Android Gradle 工程，基于 [Mika v0.1.38](https://github.com/wangzan101/Mika/releases/tag/v0.1.38)（`5d3c7f9c0ce521812632674776b780a1b4bbf58a`）。CardDock 根目录仍是模拟设备原型。打开 Android Studio 时选择要运行的工程目录。

## 当前功能

- 从桌面打开「功能重组器」。模板提供菜鸟待取空状态、美团历史订单和京东 A/B。试运行核对结果后确认建卡。空间支持单卡刷新、顺序刷新、详情和删除。
- 菜鸟使用明确的「暂无待取包裹」锚点。美团用本机提供的订单路由和订单号码，进入详情后核对同一订单。京东公开页可自动核对 SKU 和规格。App 展示价需先从卡片详情点「核验京东 App 展示价」，再按提示切到匹配的京东商品页。此操作会复制公开商品链接到剪贴板，用于本次 SKU 核对。
- 「选购」可对照两款已支持的京东商品、保存一份带备注的任务并在重开后重新核验身份。价格条件不同或未知时提示无法直接比较到手价。公开页自动刷新保留上次 App 展示价和原核验时间。
- 配置 Mika Dev 主模型后，可为菜鸟空状态生成候选规则。候选需通过固定动作和选择器校验、真实试运行和确认才能保存。已保存的菜鸟模板规则和模型规则可以经试运行后替换或回退。后续刷新直接执行本地规则。
- Room v3 保存规则、卡片、运行历史、选购任务和上次规则引用，提供从 v1 和 v2 升级的迁移。刷新失败时保留旧结果与原成功时间。
- 「菜鸟规则实验」可以编辑并校验候选 JSON，试运行后确认保存，再重载已保存规则。
- 调试采样保留指定前台应用的节点父子关系、可见性、资源 ID 和文本，支持菜鸟、美团、京东、拼多多的包名校验。该入口需要 Android `DUMP` 权限，适合已授权的 ADB shell。

## 构建和运行

安装 JDK 17 和 Android SDK Platform 36，并设置 `JAVA_HOME` 与 `ANDROID_HOME`。建议把工程放在纯英文路径，避免 Windows 上的 protobuf 工具遇到非 ASCII 路径问题。

```powershell
Set-Location experiments/mika-android
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest -PdevInstall --tests 'com.wangzan101.mika.experiment.*' --tests 'com.wangzan101.mika.ui.LicensesTest'
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 与 Linux 使用 `./gradlew`。该构建的包名为 `com.wangzan101.mika.dev`，最低支持 Android 11。安装后为 Mika Dev 启用无障碍服务，打开「功能重组器」。已有同包名开发版时，使用与原安装一致的签名覆盖安装并保留原数据。美团订单路由及号码由测试者在本机提供。模型账号在 Mika Dev 中配置。

公开仓库的 JVM 用例可直接运行。原始节点及商品响应回放仅在本机设置 `CARDDOCK_SAMPLE_DIR` 且具备相应证据时执行。Android 测试包构建和运行分别记录。

### 只读节点采样

先在手机上打开本次要采样的页面，再执行下列命令。多个设备连接时，为每条 ADB 命令加上 `-s <设备序列号>`。

```powershell
adb shell am broadcast --user 0 --receiver-foreground -n com.wangzan101.mika.dev/com.wangzan101.mika.experiment.PageProbeReceiver --es expectedPackage com.jingdong.app.mall --es sampleId jd-sample
adb shell cat /sdcard/Android/data/com.wangzan101.mika.dev/files/page-probe/status.json
```

`CAPTURED` 表示同目录下生成了 `jd-sample.json`。样本可能含页面私人信息，保存在设备本地，分享前请脱敏。

## 验证范围

2026-11-12 在 vivo X100s、Android 16 上验收。菜鸟为 8.11.1105，美团为 12.68.402，京东为 16.1.50。

| 验收项 | 已观察结果 |
| --- | --- |
| 菜鸟和美团 O1 | 一组连续 10 轮顺序刷新各 10/10 正确，每轮美团从首页开始。菜鸟仅露首页图标时也核验到明确空状态。 |
| 京东 A/B | 公开页 SKU 与所选规格各自动核验 10/10。错商品的分享链接被拒绝，错误详情不会覆盖价格缓存。 |
| App 价格 | 两款商品各完成一次引导式展示价核验，价格条件不同，比较页提示无法直接比较。 |
| 模型规则 | 1 次模型请求生成有效菜鸟候选，随后通过试运行、保存和本地刷新。 |
| 持久化与恢复 | 选购任务和备注在进程重开后恢复。菜鸟原卡完成规则替换及回退。最终五卡顺序刷新成功，逐条模型请求增量均为 0。 |

结果覆盖上述设备和应用版本。菜鸟当前只处理明确空状态，美团当前只验证历史已完成订单。京东 App 最新展示价仍需要用户先切到目标商品详情。跨品牌、真实取件码和实时配送需要进一步验证。

## 源码来源

工程保留上游许可证、NOTICE、第三方声明及沙箱构建脚本。此源码快照省去了上游预编译 Linux 沙箱运行环境和原生沙箱程序。卡片和采样使用 Android 无障碍服务。Linux 沙箱功能需按[上游沙箱构建说明](mika-android/tools/sandbox/README.md)自行准备运行文件。
