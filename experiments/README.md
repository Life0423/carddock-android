# CardDock 真机实验原型

`mika-android/` 是独立的 Android Gradle 工程，基于 [Mika v0.1.38](https://github.com/wangzan101/Mika/releases/tag/v0.1.38)（`5d3c7f9c0ce521812632674776b780a1b4bbf58a`）。CardDock 根目录仍是模拟设备原型。打开 Android Studio 时选择要运行的工程目录。

## 当前功能

- 从桌面打开「功能重组器」。在模板页匹配菜鸟需求，经真机试运行和确认后保存卡片。空间支持单卡刷新、顺序刷新、详情和删除。
- 菜鸟规则在前台读取包名、页面节点与明确的「暂无待取包裹」锚点。查询不需要模型配置。Room 保存规则、卡片、运行记录和最近一次成功结果。刷新失败时保留旧结果与原成功时间。
- 「菜鸟规则实验」可以编辑并校验候选 JSON，试运行后确认保存，再重载已保存规则。
- 调试采样保留指定前台应用的节点父子关系、可见性、资源 ID 和文本，支持菜鸟、美团、京东、拼多多的包名校验。该入口需要 Android `DUMP` 权限，适合已授权的 ADB shell。

## 构建和运行

安装 JDK 17 和 Android SDK Platform 36，并设置 `JAVA_HOME` 与 `ANDROID_HOME`。建议把工程放在纯英文路径，避免 Windows 上的 protobuf 工具遇到非 ASCII 路径问题。

```powershell
Set-Location experiments/mika-android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest -PdevInstall --tests 'com.wangzan101.mika.experiment.*' --tests 'com.wangzan101.mika.ui.LicensesTest'
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 与 Linux 使用 `./gradlew`。该构建的包名为 `com.wangzan101.mika.dev`，最低支持 Android 11。安装后为 Mika Dev 启用无障碍服务，打开「功能重组器」。使用菜鸟 8.11.1105 且页面有明确空状态时，在模板页点「真实试运行」，核对成功结果后确认创建。若此前安装的同包名开发版使用其他签名，请保留原应用数据并使用原签名构建。

### 只读节点采样

先在手机上打开本次要采样的页面，再执行下列命令。多个设备连接时，为每条 ADB 命令加上 `-s <设备序列号>`。

```powershell
adb shell am broadcast --user 0 --receiver-foreground -n com.wangzan101.mika.dev/com.wangzan101.mika.experiment.PageProbeReceiver --es expectedPackage com.jingdong.app.mall --es sampleId jd-sample
adb shell cat /sdcard/Android/data/com.wangzan101.mika.dev/files/page-probe/status.json
```

`CAPTURED` 表示同目录下生成了 `jd-sample.json`。样本可能含页面私人信息，保存在设备本地，分享前请脱敏。

## 验证范围

vivo X100s（Android 16）上已验收菜鸟明确空状态。前一轮十次正常刷新全部核验成功，模型请求计数增量均为 0。该结果来自指定版本和设备。后续采样实验在美团历史订单及京东商品页取得过字段证据，京东直播浮窗会改变顶层窗口。

本版菜鸟规则覆盖明确空状态。真实取件码、美团及京东卡片、商品对比、中断恢复和原版 Mika 对照是后续实验项目。

## 源码来源

工程保留上游许可证、NOTICE、第三方声明及沙箱构建脚本。此源码快照省去了上游预编译 Linux 沙箱运行环境和原生沙箱程序。卡片和采样使用 Android 无障碍服务。Linux 沙箱功能需按[上游沙箱构建说明](mika-android/tools/sandbox/README.md)自行准备运行文件。
