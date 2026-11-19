package com.life0423.carddock.core.model

import kotlinx.serialization.Serializable

/** 编辑副本和可用版本共享 ID。原始需求仅在本机草稿恢复，发布的模板只含可执行定义。 */
@Serializable
data class TemplateEdit(
    val title: String,
    val description: String,
    val candidate: TemplateDefinition?,
    val prompt: String? = null,
)

@Serializable
data class PersonalTemplate(
    val id: String,
    val published: TemplateDefinition? = null,
    val edit: TemplateEdit? = null,
)
