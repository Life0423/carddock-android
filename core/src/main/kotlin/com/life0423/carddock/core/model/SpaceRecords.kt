package com.life0423.carddock.core.model

import kotlinx.serialization.Serializable

/** 对比组仅保存有序卡片引用；成员仍各自持有规则和结果。 */
@Serializable
data class CompareGroup(
    val id: String,
    val name: String,
    val memberCardIds: List<String>,
)

/** 单个空间的原子保存单元。cards 的顺序同时决定首页位置，组成员连续排列。 */
data class SpaceRecords(
    val cards: List<CardRecord>,
    val groups: List<CompareGroup> = emptyList(),
)
