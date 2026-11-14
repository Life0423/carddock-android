package com.life0423.carddock.core.model

import com.life0423.carddock.core.rule.RuleSpec
import kotlinx.serialization.Serializable

/** 三种内置演示。编译器按这个分流，不是用户可扩展的分类。 */
@Serializable
enum class TemplateKind { EXPRESS, DELIVERY, TRAVEL, SHOPPING }

/** 规则读出的一项。key 是 outputKey，label 给界面。引擎写入时会去掉 value 的首尾空白。 */
@Serializable
data class FieldValue(
    val key: String,
    val label: String,
    val value: String,
)

/**
 * Only verified results become snapshots; a failed refresh must retain the previous one.
 * 失败、取消、接管和中断都不换这份快照，只有引擎成功才由会话替换。sourceLabel 缺省即演示数据来源。
 */
@Serializable
data class CardSnapshot(
    val fields: List<FieldValue>,
    val fetchedAt: Long,
    val sourceLabel: String = "模拟设备 · 演示数据",
)

/**
 * 运行阶段。QUEUED 与 RUNNING 是进行中；进程还在时通常有活协程。
 * 启动恢复，以及读回内容不等于本次提交的失败对账，会把没有活协程的 QUEUED、RUNNING、NEEDS_USER 收成 INTERRUPTED。
 * 读回恰好等于本次提交时原样发布，不走这步收敛，没有活协程的 NEEDS_USER 也会留下。
 * NEEDS_USER 表示设备读取已经返回，取消不能只依赖仍在表里的 job。
 * INTERRUPTED 不是引擎的返回值。
 */
@Serializable
enum class RunPhase {
    IDLE,
    QUEUED,
    RUNNING,
    NEEDS_USER,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/**
 * 最近一次运行。协调器新建排队记录时 endedAt 为空，推进到运行中时也不填。
 * 成功、接管、取消由协调器写 endedAt。创建保存把试运行收成卡片时也会写。
 * 失败由协调器或集合的内存收尾写。中断由集合在装回磁盘时写。
 */
@Serializable
data class RunInfo(
    val id: String,
    val phase: RunPhase,
    val message: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val startedAt: Long = 0,
    val endedAt: Long? = null,
    val appPrice: Boolean = false,
)

/** 授权订单号仅用于本机核对，不进入规则和卡片展示字段。 */
@Serializable
data class LocalTarget(val orderNumber: String) {
    override fun toString(): String = "LocalTarget(已隐藏)"
}

/** 独立于公开身份的京东 App 展示价证据。空优惠字段表示条件未知。 */
@Serializable
data class AppPrice(
    val amount: String,
    val currency: String,
    val variant: String,
    val label: String,
    val discount: String?,
    val verifiedAt: Long,
)

/** 卡片的持久化形态。snapshot 为空表示还没有核验通过的结果。run 是最近一次尝试，可以没有。 */
@Serializable
data class CardRecord(
    val id: String,
    val title: String,
    val kind: TemplateKind,
    val rule: RuleSpec,
    val snapshot: CardSnapshot? = null,
    val run: RunInfo? = null,
    val localTarget: LocalTarget? = null,
    val appPrice: AppPrice? = null,
    val priceVerificationFailed: Boolean = false,
    val previousRule: RuleSpec? = null,
)

/** 一条可试运行的演示模板。examplePrompt 只负责预填，编译器仍按用户最终输入分流，不按模板 id。 */
@Serializable
data class TemplateDefinition(
    val id: String,
    val title: String,
    val description: String,
    val kind: TemplateKind,
    val examplePrompt: String,
    val rule: RuleSpec,
    val requiresOrderNumber: Boolean = false,
)
