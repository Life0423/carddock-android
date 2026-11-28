package com.life0423.carddock

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.VisionSelection
import com.wangzan101.mika.settings.verifyAndActivateVision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实设备验收。只输出候选模型名和服务商本地 ID，不打印地址、密钥、响应或截图。 */
@RunWith(AndroidJUnit4::class)
class VisionConfigurationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ProviderStore.get(context)

    @Test fun listConnectedModels() = runBlocking {
        assumeTrue("请单独指定此测试方法", InstrumentationRegistry.getArguments().getString("class")?.endsWith("#listConnectedModels") == true)
        val providers = store.providers.value
        assertTrue("尚未连接服务商", providers.isNotEmpty())
        for (p in providers) {
            val listed = store.listModels(p)
            val snapshot = if (listed is ModelListResult.Ok) p.copy(models = listed.models) else p
            val options = ModelOptions.ofProvider(snapshot, store.list())
            Log.i(TAG, "providerId=${p.id} source=${if (listed is ModelListResult.Ok) "live" else "saved"} count=${options.size}")
            for (option in options) {
                Log.i(TAG, "providerId=${p.id} modelId=${option.id.replace(Regex("[\\r\\n]"), "")} listedVision=${option.declaredVision ?: "unknown"} imageTested=${option.imageTested}")
            }
        }
    }

    /** 定向执行此方法才允许改变视觉用途；普通 connectedAndroidTest 不会动用用户的模型。 */
    @Test fun verifyAndActivateSelectedModel() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("请单独指定此测试方法", args.getString("class")?.endsWith("#verifyAndActivateSelectedModel") == true)
        val before = store.roles.value
        val active = store.configFor(ModelRole.Vision)
        val model = args.getString("modelId")
        val provider = args.getString("providerId")
        require((model == null) == (provider == null)) { "providerId 和 modelId 必须同时指定" }
        val target = if (model != null && provider != null) VisionSelection(provider, model)
            else {
                val selected = active ?: store.configFor(ModelRole.Main) ?: error("请先配置主模型，或指定候选服务商与模型")
                VisionSelection(selected.providerId, selected.model)
            }
        val grounding = store.list().firstOrNull { it.providerId == target.providerId && it.model == target.model }
            ?.capabilities?.groundingVerified ?: false
        val failure = store.verifyAndActivateVision(target)
        assertEquals(before.main, store.roles.value.main)
        assertEquals(before.light, store.roles.value.light)
        if (failure != 0) {
            assertEquals(before.vision, store.roles.value.vision)
            throw AssertionError(context.getString(failure))
        }
        val verified = store.configFor(ModelRole.Vision)!!
        assertEquals(target.providerId, verified.providerId)
        assertEquals(target.model, verified.model)
        assertEquals(grounding, verified.capabilities.groundingVerified)
        assertNotNull(store.modelFor(ModelRole.Vision))
        Log.i(TAG, "verified=true providerId=${verified.providerId} modelId=${verified.model}")
    }

    private companion object { const val TAG = "CardDockVisionTest" }
}
