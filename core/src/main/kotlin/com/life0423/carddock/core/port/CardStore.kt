package com.life0423.carddock.core.port

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.SpaceRecords

/**
 * Persists verified card configuration and its last known execution state.
 * 保存的是已核验的卡片和它最后一次运行状态。实现必须让部分写入对读者不可见。
 */
interface CardStore {
    /**
     * Null means first launch; an empty list means the user removed every card.
     * 读失败要抛出。伪装成 null 会让调用方当成没有文件，用种子覆盖原文件。
     * 空列表必须原样返回，表示用户已经删光，不能当成损坏。
     */
    suspend fun load(): List<CardRecord>?

    /**
     * Replace the collection atomically, or throw without committing a partial file.
     * 抛出表示调用方不能假设内存里的新列表已经可见；提交后确认失败时，磁盘上可能已经是新内容。
     */
    suspend fun save(cards: List<CardRecord>)

    /** 兼容只保存卡片的旧适配器；正式存储覆盖此方法，整体读取卡片和组。 */
    suspend fun loadSpace(): SpaceRecords? = load()?.let { SpaceRecords(it) }

    /** 旧适配器不能静默丢弃组。支持组的实现必须整体原子写入。 */
    suspend fun saveSpace(space: SpaceRecords) {
        check(space.groups.isEmpty()) { "This store does not support comparison groups" }
        save(space.cards)
    }
}
