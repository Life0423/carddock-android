package com.life0423.carddock.core.port

import com.life0423.carddock.core.model.PersonalTemplate

/** 本机模板库的原子读写。读取失败时抛出，控制器保留原文件。 */
interface TemplateStore {
    suspend fun load(): List<PersonalTemplate>
    suspend fun save(templates: List<PersonalTemplate>)
}
