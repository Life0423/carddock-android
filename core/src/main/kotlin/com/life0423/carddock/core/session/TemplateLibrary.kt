package com.life0423.carddock.core.session

import com.life0423.carddock.core.model.PersonalTemplate
import com.life0423.carddock.core.port.TemplateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 模板元数据和规则独立于卡片，读失败后禁止覆盖存储。 */
internal class TemplateLibrary(private val store: TemplateStore?) {
    val state = MutableStateFlow<List<PersonalTemplate>>(emptyList())
    private var readable = false
    private val writes = Mutex()

    suspend fun initialize(): Boolean {
        return try {
            state.value = store?.load().orEmpty()
            readable = true
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun update(transform: (List<PersonalTemplate>) -> List<PersonalTemplate>): Boolean = writes.withLock {
        if (!readable) return@withLock false
        val updated = transform(state.value)
        try {
            withContext(NonCancellable) {
                store?.save(updated)
                state.value = updated
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
