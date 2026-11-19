package com.life0423.carddock.data

import android.content.Context
import android.util.AtomicFile
import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.port.TemplateStore
import com.life0423.carddock.core.rule.RuleValidator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 只持久化模板库结构。一次写入同时提交可用版本和编辑草稿。 */
internal class AtomicTemplateStore(context: Context, private val json: Json) : TemplateStore {
    private val file = AtomicFile(File(context.filesDir, "personal-templates-v1.json"))

    override suspend fun load(): List<PersonalTemplate> = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext emptyList()
        json.decodeFromString<List<PersonalTemplate>>(file.readFully().decodeToString()).also { templates ->
            require(templates.map { it.id }.distinct().size == templates.size)
            for (entry in templates) {
                entry.published?.let { require(it.id == entry.id && it.revision > 0) }
                for (definition in listOfNotNull(entry.published, entry.edit?.candidate)) {
                    require(RuleValidator.validate(definition.rule) == null)
                }
            }
        }
    }

    override suspend fun save(templates: List<PersonalTemplate>) = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(templates).encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: java.io.IOException) {
            file.failWrite(output)
            throw error
        }
        if (!file.readFully().contentEquals(bytes)) throw java.io.IOException("Template write failed")
    }
}
