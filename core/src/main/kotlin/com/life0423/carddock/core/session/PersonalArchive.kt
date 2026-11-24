package com.life0423.carddock.core.session

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.model.TemplateEdit
import com.life0423.carddock.core.rule.RuleSpec
import com.life0423.carddock.core.rule.RuleValidator
import kotlinx.serialization.Serializable

/** 用户选择的本地迁移文件。私有订单授权、页面识别入口及运行记录均须在新设备上重新取得。 */
@Serializable
data class PersonalArchive(val version: Int = 1, val templates: List<PersonalTemplate>, val cards: List<CardRecord>) {
    fun validate() {
        require(version == 1) { "无法读取这个版本的迁移文件。" }
        require(templates.all { it.id.isNotBlank() } && templates.distinctBy { it.id }.size == templates.size &&
            cards.all { it.id.isNotBlank() && it.title.isNotBlank() } && cards.distinctBy { it.id }.size == cards.size) { "迁移文件中的条目标识无效。" }
        for (entry in templates) {
            require(entry.published != null || entry.edit != null)
            entry.published?.let { require(it.id == entry.id && it.revision > 0) }
            for (definition in listOfNotNull(entry.published, entry.edit?.candidate)) require(RuleValidator.validate(definition.rule) == null)
        }
        require(cards.all { RuleValidator.validate(it.rule) == null }) { "文件含有无法识别的卡片规则。" }
    }

    /** 写文件和读文件共用同一策略。尚未在本机识别的读取路线作为草稿，之后由用户重新试运行。 */
    fun portable(known: (RuleSpec) -> Boolean): PersonalArchive {
        val entries = templates.map { entry ->
            val active = entry.published?.takeIf { it.pickupEntry == null && !it.opensPickupList && it.rule.officialAction == null && known(it.rule) }
            val draft = entry.edit?.let { e -> e.copy(theme = e.candidate?.theme ?: e.theme, candidate = e.candidate?.takeIf { it.pickupEntry == null && !it.opensPickupList && it.rule.officialAction == null && known(it.rule) }) }
                ?: entry.published?.takeIf { active == null }?.let { TemplateEdit(it.title, it.description, null, it.examplePrompt, it.theme) }
            entry.copy(published = active, edit = draft)
        }.toMutableList()
        val saved = cards.mapNotNull { card ->
            if (card.localTarget != null || card.pickupEntry != null || card.opensPickupList || card.rule.officialAction != null || !known(card.rule)) {
                val id = card.sourceTemplateId ?: "migrated-${card.id}"
                if (entries.none { it.id == id }) entries += PersonalTemplate(id, edit = TemplateEdit(card.title, "", null, card.title, card.theme))
                null
            } else card.copy(localTarget = null, appPrice = null, priceVerificationFailed = false, previousRule = null,
                run = null, pickupEntry = null, opensPickupList = false, snapshot = card.snapshot?.takeIf {
                    card.rule.sourceId == "apple.cn.display" || card.rule.sourceId.startsWith("demo.")
                })
        }
        return copy(templates = entries, cards = saved)
    }
}
