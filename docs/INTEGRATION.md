# 分支归并与协作基线

2026-11-13 核对远端全部分支，整合基于 main `025a0a5` 和 dev `6344d40`。main 是团队共享基线；旧分支保留历史，不应再次把旧快照覆盖到 main。

## 分支去向

以下分支累计差异的 stable patch-id 与 main 的对应 squash 提交一致，内容已经完整归入，无需重复合并：

| 分支 | main 提交 |
| --- | --- |
| cursor/skeleton-review-fixes-b240 | 72b3b30 (#2) |
| feat/prototype-v0 | 0336535 (#3) |
| fix/atomic-test-isolation | c0a2248 (#4) |
| docs/gitignore-and-docs-fixes | efd88fa (#5) |
| docs/readme-rework | dd0bce3 (#6) |
| chore/cleanup-post-prototype | 6dec97c (#7) |
| fix/state-store-hardening | 99a637c (#8) |
| docs/chinese-comments | 025a0a5 (#9) |

`cursor/docs-readme-contributing-81ba` 是旧骨架时期的文档方案，已由 #6 的 README、贡献指南和横幅重写取代。不要恢复其中过时的工程描述。

`dev` 在 main 基础上新增两个提交，完整保留在此次整合中，包括 Mika 实验源码、许可证、测试和说明。

## 两个工程的边界

| 目录 | 职责 | 当前限制 |
| --- | --- | --- |
| 根目录 app / core | 模拟设备原型，验证规则、试运行、卡片状态和持久化 | 不代表真实第三方应用接入 |
| experiments/mika-android | 独立 Gradle 真机实验工程，复用 Mika 无障碍等能力 | 菜鸟空状态、美团历史订单、京东指定商品等受限场景 |

这次统一源码和协作入口，未把两个应用强行合成一个 APK，也未迁移数据模型。Android Studio 必须打开对应工程目录。真机实验的历史设备验证由原分支文档记录，本次归并不构成重新真机验收。

团队主线固定为根目录 `app → core`。实验工程保留为能力来源，不继续复制一套产品模型、UI 和持久化到主工程。根工作流 `android.yml` 验证主工程；`experiment.yml` 单独验证实验 debug 包和 CardDock JVM 用例，二者结果不可互相替代。已删除不会运行的嵌套上游 CI。

迁入时必须区分：主工程 `RuleSpec` 是只读快照规则，实验 `RuleTemplate` 含启动、点击、跳转；两者的 schemaVersion 不是同一协议。主工程 AtomicFile 与实验 Room v3 也没有数据兼容性。先移植可独立验证的页面证据识别，再接平台读取；点击和多阶段执行需要单独扩展协议。不得将“菜鸟明确空状态”包装成已支持取件码，或将美团历史订单包装成实时配送。
后续成员从最新 main 创建功能分支，通过 PR 回归 main；涉及模拟原型与真机工程的修改在 PR 中明确目录和验证范围。dev 是此次整合的来源，未删除或重写任何成员分支。

## 本次整合验证

已完成分支 patch-id 核对和独立只读审查；根 app/core 与 dev 归入前逐文件保持一致。根工程构建尝试在配置阶段失败：本机 Android Studio JBR 25.0.3 不被当前 Kotlin 工具链识别；未运行到测试阶段。本机也未安装文档要求的 SDK 35/36，仅有 SDK 37。需使用 JDK 17 和对应 SDK 重新执行两工程的验证命令。此次未进行真机验收。
